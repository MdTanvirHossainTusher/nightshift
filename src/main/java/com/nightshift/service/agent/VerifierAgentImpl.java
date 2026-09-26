package com.nightshift.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.model.enums.VerifierVerdict;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.PatchProposalRepository;
import com.nightshift.service.llm.LlmClient;
import com.nightshift.service.llm.LlmClientRegistry;
import com.nightshift.service.llm.LlmRequest;
import com.nightshift.service.llm.LlmResponse;
import com.nightshift.util.AgentStepRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link VerifierAgent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifierAgentImpl implements VerifierAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT_PATH = "prompts/verify.md";

    private final LlmClientRegistry llmRegistry;
    private final PatchProposalRepository patchProposalRepository;
    private final IncidentRepository incidentRepository;
    private final AgentStepRecorder recorder;

    @Override
    @Transactional
    public boolean verify(PatchProposal proposal, Incident incident, CodeLocation codeLocation) {
        return verify(proposal, incident, codeLocation, null);
    }

    @Override
    @Transactional
    public boolean verify(PatchProposal proposal, Incident incident, CodeLocation codeLocation, ScanRun scanRun) {
        log.info("Verifying patch proposal {} for incident {}", proposal.getId(), incident.getFingerprint());

        LlmClient client = llmRegistry.resolve();
        String userPrompt = buildUserPrompt(proposal, incident, codeLocation);
        String inputSummary = "incident=" + incident.getFingerprint() + " patch_id=" + proposal.getId();

        LlmResponse response = null;
        try {
            response = recorder.recordModelCall(
                    scanRun, incident, AgentRole.VERIFY, 0,
                    client, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary);
        } catch (Exception e) {
            log.warn("Verifier call failed for provider '{}': {}. Falling back to heuristic...", client.provider(), e.getMessage());
            if (!"heuristic".equalsIgnoreCase(client.provider())) {
                try {
                    client = llmRegistry.resolve("heuristic");
                    response = recorder.recordModelCall(
                            scanRun, incident, AgentRole.VERIFY, 0,
                            client, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary + " [heuristic-fallback]");
                } catch (Exception fallbackEx) {
                    log.error("Heuristic fallback for verifier failed: {}", fallbackEx.getMessage());
                    return false;
                }
            } else {
                return false;
            }
        }

        if (response == null) {
            return false;
        }

        VerifyResult result = parseVerifyResponse(response.responseText());

        if (result.verdict == VerifierVerdict.PASS) {
            proposal.setStatus(PatchStatus.VERIFIED);
            proposal.setVerifierVerdict(VerifierVerdict.PASS);
            proposal.setVerifierNotes(result.notes);
            patchProposalRepository.save(proposal);

            incident.setStatus(IncidentStatus.FIX_VERIFIED);
            incidentRepository.save(incident);

            log.info("Patch proposal {} VERIFIED: {}", proposal.getId(), result.notes);
            return true;
        } else {
            proposal.setStatus(PatchStatus.REJECTED);
            proposal.setVerifierVerdict(VerifierVerdict.FAIL);
            proposal.setVerifierNotes(result.notes);
            proposal.setRejectionCode(ErrorCodes.EVIDENCE_REJECTED);
            patchProposalRepository.save(proposal);

            incident.setStatus(IncidentStatus.TRIAGED_PATCH_REJECTED);
            incidentRepository.save(incident);

            log.warn("Patch proposal {} REJECTED by verifier: {}", proposal.getId(), result.notes);
            return false;
        }
    }

    private String buildUserPrompt(PatchProposal proposal, Incident incident, CodeLocation codeLocation) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("verify_request\n");
        sb.append("fingerprint: ").append(incident.getFingerprint()).append('\n');
        sb.append("title: ").append(nullSafe(incident.getTitle())).append('\n');
        sb.append("root_cause: ").append(nullSafe(incident.getRootCause())).append('\n');
        sb.append("future_impact: ").append(nullSafe(incident.getFutureImpact())).append('\n');
        sb.append("recommended_action: ").append(nullSafe(incident.getRecommendedAction())).append('\n');

        if (codeLocation != null) {
            sb.append("target_file: ").append(codeLocation.getFilePath()).append('\n');
            sb.append("lines: ").append(codeLocation.getStartLine()).append("-").append(codeLocation.getEndLine()).append('\n');
            sb.append("\noriginal_source_snippet:\n").append(nullSafe(codeLocation.getSnippet())).append('\n');
        }

        sb.append("\nproposed_patch:\n").append(nullSafe(proposal.getUnifiedDiff())).append('\n');
        sb.append("rationale: ").append(nullSafe(proposal.getRationale())).append('\n');
        sb.append("test_plan: ").append(nullSafe(proposal.getTestPlan())).append('\n');

        return sb.toString();
    }

    private VerifyResult parseVerifyResponse(String text) {
        if (text == null || text.isBlank()) {
            return new VerifyResult(VerifierVerdict.FAIL, "Empty response from verifier model");
        }

        String stripped = stripFences(text.strip());
        try {
            JsonNode json = MAPPER.readTree(stripped);
            String verdictStr = json.path("verdict").asText("FAIL").toUpperCase();
            VerifierVerdict verdict = "PASS".equals(verdictStr) ? VerifierVerdict.PASS : VerifierVerdict.FAIL;
            String notes = json.path("notes").asText("Verification verdict: " + verdict);
            return new VerifyResult(verdict, notes);
        } catch (Exception e) {
            log.warn("Failed to parse verifier response as JSON: {}", e.getMessage());
            return new VerifyResult(VerifierVerdict.FAIL, "Unparseable verifier response: " + e.getMessage());
        }
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

    private record VerifyResult(VerifierVerdict verdict, String notes) {}
}