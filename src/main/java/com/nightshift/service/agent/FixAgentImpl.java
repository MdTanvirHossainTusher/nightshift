package com.nightshift.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.PatchRejectedException;
import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.repository.PatchProposalRepository;
import com.nightshift.service.llm.LlmClient;
import com.nightshift.service.llm.LlmClientRegistry;
import com.nightshift.service.llm.LlmRequest;
import com.nightshift.service.llm.LlmResponse;
import com.nightshift.util.AgentStepRecorder;
import com.nightshift.util.PatchGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Default implementation of {@link FixAgent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixAgentImpl implements FixAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT_PATH = "prompts/fix.md";

    private final NightshiftProperties props;
    private final LlmClientRegistry llmRegistry;
    private final PatchProposalRepository patchProposalRepository;
    private final PatchGuard patchGuard;
    private final AgentStepRecorder recorder;

    @Override
    @Transactional
    public Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation) {
        return proposeFix(incident, codeLocation, null, null);
    }

    @Override
    @Transactional
    public Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation, ScanRun scanRun) {
        return proposeFix(incident, codeLocation, scanRun, null);
    }

    @Override
    @Transactional
    public Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation, ScanRun scanRun, Path targetRepoPath) {
        if (props.getPatch() != null && !props.getPatch().isEnabled()) {
            log.info("Patch generation disabled by nightshift.patch.enabled");
            return Optional.empty();
        }

        if (codeLocation == null) {
            throw new PatchRejectedException(ErrorCodes.SOURCE_FILE_NOT_LOCATED,
                    "Cannot generate fix: no code location for incident " + incident.getFingerprint());
        }

        if (codeLocation.getConfidence() == ConfidenceLevel.LOW) {
            throw new PatchRejectedException(ErrorCodes.SOURCE_FILE_NOT_LOCATED,
                    "Cannot generate fix: code location confidence is LOW for incident " + incident.getFingerprint());
        }

        Path repoPath = resolveRepoPath(codeLocation, targetRepoPath);
        LlmClient client = llmRegistry.resolve();

        String userPrompt = buildUserPrompt(incident, codeLocation);
        String inputSummary = "fingerprint=" + incident.getFingerprint() + " file=" + codeLocation.getFilePath();

        // Step 0: Model call to generate the patch
        LlmResponse response = recorder.recordModelCall(
                scanRun, incident, AgentRole.FIX, 0,
                client, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary);

        FixResult fixResult = parseFixResponse(response.responseText(), incident);

        if (fixResult.unifiedDiff == null || fixResult.unifiedDiff.isBlank()) {
            log.info("Fix agent produced no diff for incident {}", incident.getFingerprint());
            return Optional.empty();
        }

        long guardStart = System.currentTimeMillis();
        PatchGuard.PatchAnalysis analysis = null;

        try {
            analysis = patchGuard.validate(fixResult.unifiedDiff, repoPath);

            long guardLatency = System.currentTimeMillis() - guardStart;
            recorder.recordToolCall(scanRun, incident, AgentRole.FIX, 1,
                    "patch_guard", inputSummary,
                    "PASSED: files=" + analysis.filesChanged() + " lines=" + (analysis.linesAdded() + analysis.linesRemoved()),
                    guardLatency, null);

            PatchProposal proposal = PatchProposal.builder()
                    .incident(incident)
                    .status(PatchStatus.DRAFT)
                    .unifiedDiff(fixResult.unifiedDiff)
                    .rationale(fixResult.rationale)
                    .testPlan(fixResult.testPlan)
                    .filesChanged(analysis.filesChanged())
                    .linesAdded(analysis.linesAdded())
                    .linesRemoved(analysis.linesRemoved())
                    .provider(client.provider())
                    .build();

            proposal = patchProposalRepository.save(proposal);
            log.info("Patch proposal saved for incident {}: id={} status={}",
                    incident.getFingerprint(), proposal.getId(), proposal.getStatus());

            return Optional.of(proposal);

        } catch (PatchRejectedException e) {
            long guardLatency = System.currentTimeMillis() - guardStart;
            recorder.recordToolCall(scanRun, incident, AgentRole.FIX, 1,
                    "patch_guard", inputSummary, "REJECTED: " + e.getCode() + " - " + e.getMessage(),
                    guardLatency, e);

            PatchProposal rejected = PatchProposal.builder()
                    .incident(incident)
                    .status(PatchStatus.REJECTED)
                    .unifiedDiff(fixResult.unifiedDiff)
                    .rationale(fixResult.rationale)
                    .testPlan(fixResult.testPlan)
                    .rejectionCode(e.getCode())
                    .verifierNotes(e.getMessage())
                    .filesChanged(analysis != null ? analysis.filesChanged() : 0)
                    .linesAdded(analysis != null ? analysis.linesAdded() : 0)
                    .linesRemoved(analysis != null ? analysis.linesRemoved() : 0)
                    .provider(client.provider())
                    .build();

            patchProposalRepository.save(rejected);
            throw e;
        }
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Path resolveRepoPath(CodeLocation location, Path explicit) {
        if (explicit != null) return explicit;
        if (location.getTargetRepo() != null) {
            Path p = Path.of(location.getTargetRepo());
            if (Files.exists(p)) return p;
        }
        Path demoTarget = Path.of("demo/target-repo");
        if (Files.exists(demoTarget)) return demoTarget;
        if (props.getWorkspace() != null) {
            Path ws = Path.of(props.getWorkspace());
            if (Files.exists(ws)) return ws;
        }
        return demoTarget;
    }

    private String buildUserPrompt(Incident incident, CodeLocation codeLocation) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("fix_request\n");
        sb.append("fingerprint: ").append(incident.getFingerprint()).append('\n');
        sb.append("title: ").append(nullSafe(incident.getTitle())).append('\n');
        sb.append("service: ").append(nullSafe(incident.getServiceName())).append('\n');
        sb.append("logger: ").append(nullSafe(incident.getLoggerName())).append('\n');
        sb.append("exception_type: ").append(nullSafe(incident.getExceptionType())).append('\n');
        sb.append("normalized_message: ").append(nullSafe(incident.getNormalizedMessage())).append('\n');
        sb.append("root_cause: ").append(nullSafe(incident.getRootCause())).append('\n');
        sb.append("recommended_action: ").append(nullSafe(incident.getRecommendedAction())).append('\n');
        sb.append("target_file: ").append(codeLocation.getFilePath()).append('\n');
        sb.append("lines: ").append(codeLocation.getStartLine()).append("-").append(codeLocation.getEndLine()).append('\n');
        sb.append("\nsource_snippet:\n").append(nullSafe(codeLocation.getSnippet())).append('\n');
        return sb.toString();
    }

    private FixResult parseFixResponse(String text, Incident incident) {
        if (text == null || text.isBlank()) {
            return new FixResult("", null, null);
        }

        String stripped = stripFences(text.strip());

        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            try {
                JsonNode json = MAPPER.readTree(stripped);
                String diff = json.path("unified_diff").asText("");
                String rationale = json.path("rationale").asText(incident.getRecommendedAction());
                String testPlan = json.path("test_plan").asText("Verify incident pattern no longer recurs in logs.");
                return new FixResult(diff, rationale, testPlan);
            } catch (Exception ignored) {
            }
        }

        // Raw unified diff
        return new FixResult(stripped, incident.getRecommendedAction(), "Verify incident pattern no longer recurs in logs.");
    }

    private String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private record FixResult(String unifiedDiff, String rationale, String testPlan) {}
}