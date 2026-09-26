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
import com.nightshift.util.EditDiffBuilder;
import com.nightshift.util.PatchGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link FixAgent}.
 *
 * <p>The model is asked for search/replace {@code edits}; the unified diff is computed here
 * against the real file by {@link EditDiffBuilder}, so hunk line numbers and counts are always
 * right. A model (or the heuristic client) that still returns a raw {@code unified_diff} has its
 * hunk headers realigned by {@link PatchGuard#realign} before validation. When the patch does
 * not apply, the rejection is fed back to the model for one retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixAgentImpl implements FixAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT_PATH = "prompts/fix.md";
    /** One retry when the patch does not apply, with the rejection fed back to the model. */
    private static final int MAX_ATTEMPTS = 2;
    private static final Pattern FRAME_LINE = Pattern.compile(":(\\d+)\\)?\\s*$");
    private static final String DEFAULT_TEST_PLAN = "Verify incident pattern no longer recurs in logs.";

    private final NightshiftProperties props;
    private final LlmClientRegistry llmRegistry;
    private final PatchProposalRepository patchProposalRepository;
    private final PatchGuard patchGuard;
    private final EditDiffBuilder editDiffBuilder;
    private final AgentStepRecorder recorder;

    @Override
    @Transactional(noRollbackFor = Exception.class)
    public Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation) {
        return proposeFix(incident, codeLocation, null, null);
    }

    @Override
    @Transactional(noRollbackFor = Exception.class)
    public Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation, ScanRun scanRun) {
        return proposeFix(incident, codeLocation, scanRun, null);
    }

    @Override
    @Transactional(noRollbackFor = Exception.class)
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

        String basePrompt = buildUserPrompt(incident, codeLocation);
        String inputSummary = "fingerprint=" + incident.getFingerprint() + " file=" + codeLocation.getFilePath();

        String retryFeedback = null;
        String previousAttempt = null;
        int stepIndex = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String userPrompt = retryFeedback == null
                    ? basePrompt
                    : basePrompt + buildRetrySection(retryFeedback, previousAttempt);
            String attemptSummary = attempt == 1 ? inputSummary : inputSummary + " [retry " + attempt + "]";

            // Model call to generate the patch
            ModelCall call = callModel(scanRun, incident, stepIndex++, client, userPrompt, attemptSummary);
            if (call == null) {
                return Optional.empty();
            }
            client = call.client();

            FixResult fixResult = parseFixResponse(call.response().responseText(), incident, codeLocation);

            long guardStart = System.currentTimeMillis();
            String diff = null;
            PatchGuard.PatchAnalysis analysis = null;

            try {
                diff = materializeDiff(fixResult, codeLocation, repoPath);
                if (diff == null || diff.isBlank()) {
                    log.info("Fix agent produced no diff for incident {}", incident.getFingerprint());
                    return Optional.empty();
                }

                analysis = patchGuard.validate(diff, repoPath);

                long guardLatency = System.currentTimeMillis() - guardStart;
                recorder.recordToolCall(scanRun, incident, AgentRole.FIX, stepIndex++,
                        "patch_guard", attemptSummary,
                        "PASSED: files=" + analysis.filesChanged() + " lines=" + (analysis.linesAdded() + analysis.linesRemoved()),
                        guardLatency, null);

                PatchProposal proposal = PatchProposal.builder()
                        .incident(incident)
                        .status(PatchStatus.DRAFT)
                        .unifiedDiff(diff)
                        .rationale(fixResult.rationale())
                        .testPlan(fixResult.testPlan())
                        .filesChanged(analysis.filesChanged())
                        .linesAdded(analysis.linesAdded())
                        .linesRemoved(analysis.linesRemoved())
                        .provider(client.provider())
                        .model(client.model())
                        .attempt(attempt)
                        .build();

                proposal = patchProposalRepository.save(proposal);
                log.info("Patch proposal saved for incident {}: id={} status={} attempt={}",
                        incident.getFingerprint(), proposal.getId(), proposal.getStatus(), attempt);

                return Optional.of(proposal);

            } catch (PatchRejectedException e) {
                long guardLatency = System.currentTimeMillis() - guardStart;
                recorder.recordToolCall(scanRun, incident, AgentRole.FIX, stepIndex++,
                        "patch_guard", attemptSummary, "REJECTED: " + e.getCode() + " - " + e.getMessage(),
                        guardLatency, e);

                String storedDiff = diff != null && !diff.isBlank() ? diff : fixResult.describe();
                PatchProposal rejected = PatchProposal.builder()
                        .incident(incident)
                        .status(PatchStatus.REJECTED)
                        .unifiedDiff(storedDiff)
                        .rationale(fixResult.rationale())
                        .testPlan(fixResult.testPlan())
                        .rejectionCode(e.getCode())
                        .verifierNotes(e.getMessage())
                        .filesChanged(analysis != null ? analysis.filesChanged() : 0)
                        .linesAdded(analysis != null ? analysis.linesAdded() : 0)
                        .linesRemoved(analysis != null ? analysis.linesRemoved() : 0)
                        .provider(client.provider())
                        .model(client.model())
                        .attempt(attempt)
                        .build();
                patchProposalRepository.save(rejected);

                boolean retryable = ErrorCodes.PATCH_DOES_NOT_APPLY.equals(e.getCode())
                        && !"heuristic".equalsIgnoreCase(client.provider());
                if (attempt < MAX_ATTEMPTS && retryable) {
                    log.info("Patch for incident {} did not apply ({}); retrying with feedback",
                            incident.getFingerprint(), e.getMessage());
                    retryFeedback = e.getMessage();
                    previousAttempt = storedDiff;
                    continue;
                }
                throw e;
            }
        }
        return Optional.empty();
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    /** Model call with the heuristic fallback; null when both fail. */
    private ModelCall callModel(ScanRun scanRun, Incident incident, int stepIndex,
                                LlmClient client, String userPrompt, String inputSummary) {
        try {
            LlmResponse response = recorder.recordModelCall(
                    scanRun, incident, AgentRole.FIX, stepIndex,
                    client, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary);
            return response != null ? new ModelCall(client, response) : null;
        } catch (Exception e) {
            log.warn("Fix model call failed with provider '{}': {}. Falling back to heuristic...", client.provider(), e.getMessage());
            if ("heuristic".equalsIgnoreCase(client.provider())) {
                return null;
            }
            try {
                LlmClient fallback = llmRegistry.resolve("heuristic");
                LlmResponse response = recorder.recordModelCall(
                        scanRun, incident, AgentRole.FIX, stepIndex,
                        fallback, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary + " [heuristic-fallback]");
                return response != null ? new ModelCall(fallback, response) : null;
            } catch (Exception fallbackEx) {
                log.error("Heuristic fallback for fix failed: {}", fallbackEx.getMessage());
                return null;
            }
        }
    }

    /**
     * Produces the diff to validate: edits are diffed against the real file; a raw unified
     * diff has its hunk headers realigned to where the hunks actually sit.
     */
    private String materializeDiff(FixResult fixResult, CodeLocation codeLocation, Path repoPath) {
        if (!fixResult.edits().isEmpty()) {
            return editDiffBuilder.build(fixResult.edits(), repoPath, frameLine(codeLocation));
        }
        if (fixResult.unifiedDiff() == null || fixResult.unifiedDiff().isBlank()) {
            return "";
        }
        return patchGuard.realign(fixResult.unifiedDiff(), repoPath);
    }

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
        sb.append("frame_line: ").append(frameLine(codeLocation)).append('\n');
        sb.append("lines: ").append(codeLocation.getStartLine()).append("-").append(codeLocation.getEndLine()).append('\n');
        sb.append("\nsource_snippet (each line is prefixed with its line number and \"| \"; the prefix is not part of the code):\n")
          .append(numberLines(codeLocation.getSnippet(), codeLocation.getStartLine()));
        return sb.toString();
    }

    private String buildRetrySection(String feedback, String previousAttempt) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("\nprevious_attempt_rejected: ").append(feedback).append('\n');
        if (previousAttempt != null && !previousAttempt.isBlank()) {
            sb.append("previous_attempt:\n").append(previousAttempt).append('\n');
        }
        sb.append("instruction: return `edits` again. Copy every `old_code` verbatim from source_snippet, ")
          .append("without the line-number prefix, and include every line being replaced.\n");
        return sb.toString();
    }

    /** Line the stack frame points at, from a signature like {@code Foo.bar(Foo.java:22)}. */
    private int frameLine(CodeLocation codeLocation) {
        String sig = codeLocation.getFrameSignature();
        if (sig != null) {
            Matcher m = FRAME_LINE.matcher(sig);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return codeLocation.getStartLine() != null ? codeLocation.getStartLine() : 1;
    }

    private String numberLines(String snippet, Integer startLine) {
        if (snippet == null || snippet.isEmpty()) return "";
        int n = startLine != null && startLine > 0 ? startLine : 1;
        StringBuilder sb = new StringBuilder(snippet.length() + 256);
        for (String line : snippet.lines().toList()) {
            sb.append(String.format("%4d| ", n++)).append(line).append('\n');
        }
        return sb.toString();
    }

    private FixResult parseFixResponse(String text, Incident incident, CodeLocation codeLocation) {
        if (text == null || text.isBlank()) {
            return new FixResult(List.of(), "", null, null);
        }

        String stripped = stripFences(text.strip());

        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            try {
                JsonNode json = MAPPER.readTree(stripped);
                List<EditDiffBuilder.Edit> edits = new ArrayList<>();
                for (JsonNode e : json.path("edits")) {
                    String file = e.path("file").asText("");
                    if (file.isBlank()) file = codeLocation.getFilePath();
                    edits.add(new EditDiffBuilder.Edit(file, e.path("old_code").asText(""), e.path("new_code").asText("")));
                }
                String diff = json.path("unified_diff").asText("");
                String rationale = json.path("rationale").asText(incident.getRecommendedAction());
                String testPlan = json.path("test_plan").asText(DEFAULT_TEST_PLAN);
                return new FixResult(edits, diff, rationale, testPlan);
            } catch (Exception ignored) {
            }
        }

        // Raw unified diff
        return new FixResult(List.of(), stripped, incident.getRecommendedAction(), DEFAULT_TEST_PLAN);
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

    private record FixResult(List<EditDiffBuilder.Edit> edits, String unifiedDiff, String rationale, String testPlan) {
        /** Readable form of what the model proposed, stored when no diff could be built. */
        String describe() {
            if (edits.isEmpty()) return unifiedDiff;
            StringBuilder sb = new StringBuilder();
            for (EditDiffBuilder.Edit e : edits) {
                sb.append("# edit ").append(e.file()).append("\n<<<<<<< old_code\n").append(e.oldCode())
                  .append("\n=======\n").append(e.newCode()).append("\n>>>>>>> new_code\n");
            }
            return sb.toString();
        }
    }

    private record ModelCall(LlmClient client, LlmResponse response) {}
}
