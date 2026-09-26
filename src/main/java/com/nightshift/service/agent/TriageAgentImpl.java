package com.nightshift.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.IncidentOccurrence;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.Category;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;
import com.nightshift.repository.IncidentOccurrenceRepository;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.service.llm.LlmClient;
import com.nightshift.service.llm.LlmClientRegistry;
import com.nightshift.service.llm.LlmRequest;
import com.nightshift.service.llm.LlmResponse;
import com.nightshift.util.AgentStepRecorder;
import com.nightshift.util.SecretMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TriageAgent}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Fetch up to 5 sampled raw occurrences for the incident and mask secrets.</li>
 *   <li>Build a user-turn prompt with the incident metadata and the masked evidence.</li>
 *   <li>Call the active {@link LlmClient} via {@link AgentStepRecorder} (step 0).</li>
 *   <li>Parse the JSON response and populate incident fields.</li>
 *   <li>On a parse failure retry once with a repair instruction appended (step 1).</li>
 *   <li>If the second attempt also fails, set {@code status = TRIAGE_FAILED}.</li>
 *   <li>Save the updated incident.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriageAgentImpl implements TriageAgent {

    /**
     * Shared, thread-safe mapper. Not injected from the Spring context so this class
     * works in {@code @DataJpaTest} slices without web-layer auto-configuration.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int    MAX_EVIDENCE_LINES = 5;
    private static final String SYSTEM_PROMPT_PATH = "prompts/triage.md";
    private static final String REPAIR_INSTRUCTION =
            "\n\nYour previous response was not valid JSON. Return ONLY a valid JSON object " +
            "conforming to the schema in the system prompt — no prose, no fences.";

    private final LlmClientRegistry            llmRegistry;
    private final IncidentOccurrenceRepository occurrenceRepository;
    private final IncidentRepository           incidentRepository;
    private final AgentStepRecorder            recorder;
    private final SecretMasker                 masker;

    // ── TriageAgent ───────────────────────────────────────────────────────────

    @Override
    @Transactional(noRollbackFor = Exception.class)
    public void triage(Incident incident, ScanRun scanRun) {
        log.debug("Triaging incident id={} fingerprint={}", incident.getId(), incident.getFingerprint());

        LlmClient client = llmRegistry.resolve();
        String userPrompt = buildUserPrompt(incident);
        String inputSummary = "fingerprint=" + incident.getFingerprint()
                + " level=" + incident.getLogLevel()
                + " occurrences=" + incident.getOccurrenceCount();

        LlmResponse response = null;
        try {
            // Attempt 1
            response = recorder.recordModelCall(
                    scanRun, incident, AgentRole.TRIAGE, 0,
                    client, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary);
        } catch (Exception e) {
            log.warn("Triage call failed for provider '{}': {}. Falling back to heuristic...", client.provider(), e.getMessage());
            if (!"heuristic".equalsIgnoreCase(client.provider())) {
                try {
                    client = llmRegistry.resolve("heuristic");
                    response = recorder.recordModelCall(
                            scanRun, incident, AgentRole.TRIAGE, 0,
                            client, LlmRequest.of(SYSTEM_PROMPT_PATH, userPrompt), inputSummary + " [heuristic-fallback]");
                } catch (Exception fallbackEx) {
                    log.error("Heuristic fallback also failed: {}", fallbackEx.getMessage());
                }
            }
        }

        if (response == null) {
            incident.setStatus(IncidentStatus.TRIAGE_FAILED);
            incident.setTriageProvider(client.provider());
            incident.setTriageModel(client.model());
            incidentRepository.save(incident);
            return;
        }

        JsonNode parsed = tryParse(response.responseText());

        if (parsed == null) {
            log.warn("Triage JSON parse failed on attempt 1 for incident {}; retrying", incident.getId());
            // Attempt 2 — append repair instruction
            String repairedPrompt = userPrompt + REPAIR_INSTRUCTION;
            try {
                LlmResponse repairResponse = recorder.recordModelCall(
                        scanRun, incident, AgentRole.TRIAGE, 1,
                        client, LlmRequest.of(SYSTEM_PROMPT_PATH, repairedPrompt), inputSummary + " [repair]");
                parsed = tryParse(repairResponse.responseText());
            } catch (Exception e) {
                log.warn("Triage repair call failed: {}", e.getMessage());
            }
        }

        if (parsed == null) {
            log.error("Triage JSON unparseable after 2 attempts for incident {}; " +
                      "setting TRIAGE_FAILED (LLM_RESPONSE_UNPARSEABLE)", incident.getId());
            incident.setStatus(IncidentStatus.TRIAGE_FAILED);
            incident.setTriageProvider(client.provider());
            incident.setTriageModel(client.model());
            incidentRepository.save(incident);
            return;
        }

        applyTriageResult(incident, parsed, client.provider(), client.model());
        incidentRepository.save(incident);

        log.info("Triage complete: incident={} severity={} category={}",
                incident.getId(), incident.getSeverity(), incident.getCategory());
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildUserPrompt(Incident incident) {
        List<IncidentOccurrence> samples = occurrenceRepository
                .findTopByIncidentId(incident.getId(), PageRequest.of(0, MAX_EVIDENCE_LINES));

        String evidence = samples.stream()
                .map(occ -> masker.mask(occ.getRawLine()))
                .filter(line -> line != null && !line.isBlank())
                .collect(Collectors.joining("\n"));

        StringBuilder sb = new StringBuilder(512);
        sb.append("fingerprint: ").append(incident.getFingerprint()).append('\n');
        sb.append("service: ").append(nullSafe(incident.getServiceName())).append('\n');
        sb.append("logger: ").append(nullSafe(incident.getLoggerName())).append('\n');
        sb.append("level: ").append(nullSafe(incident.getLogLevel())).append('\n');
        sb.append("exception_type: ").append(nullSafe(incident.getExceptionType())).append('\n');
        sb.append("occurrence_count: ").append(incident.getOccurrenceCount()).append('\n');
        sb.append("first_seen: ").append(incident.getFirstSeenAt()).append('\n');
        sb.append("last_seen: ").append(incident.getLastSeenAt()).append('\n');
        sb.append("normalized_message: ").append(masker.mask(incident.getNormalizedMessage())).append('\n');

        if (!evidence.isBlank()) {
            sb.append('\n').append("evidence (up to 5 sampled log lines — secrets already redacted):\n");
            sb.append(evidence);
        }

        // Include sample stacktrace if available, masked
        if (incident.getSampleStacktrace() != null && !incident.getSampleStacktrace().isBlank()) {
            sb.append('\n').append("sample_stacktrace:\n");
            sb.append(masker.mask(incident.getSampleStacktrace()));
        }

        return sb.toString();
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) return null;
        String stripped = stripFences(text.strip());
        try {
            JsonNode node = MAPPER.readTree(stripped);
            if (!node.isObject()) return null;
            return node;
        } catch (Exception e) {
            log.debug("JSON parse error: {}", e.getMessage());
            return null;
        }
    }

    /** Strips optional markdown code fences (```json ... ``` or ``` ... ```) */
    private String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence    = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }

    // ── Entity mapping ────────────────────────────────────────────────────────

    private void applyTriageResult(Incident incident, JsonNode json, String provider, String model) {
        String title = textOrNull(json, "title");
        if (title != null) incident.setTitle(title);

        incident.setSeverity(parseSeverity(json));
        incident.setSeverityRationale(textOrNull(json, "severity_rationale"));
        incident.setCategory(parseCategory(json));
        incident.setRootCause(textOrNull(json, "root_cause"));
        incident.setFutureImpact(textOrNull(json, "future_impact"));
        incident.setRecommendedAction(textOrNull(json, "recommended_action"));
        incident.setConfidence(parseConfidence(json));
        incident.setTriageProvider(provider);
        incident.setTriageModel(model);
        incident.setTriagedAt(Instant.now());
        incident.setStatus(IncidentStatus.TRIAGED);
    }

    private Severity parseSeverity(JsonNode json) {
        try {
            return Severity.valueOf(json.path("severity").asText("MINOR").toUpperCase());
        } catch (IllegalArgumentException e) {
            return Severity.MINOR;
        }
    }

    private Category parseCategory(JsonNode json) {
        try {
            return Category.valueOf(json.path("category").asText("UNCATEGORIZED").toUpperCase());
        } catch (IllegalArgumentException e) {
            return Category.UNCATEGORIZED;
        }
    }

    private BigDecimal parseConfidence(JsonNode json) {
        JsonNode node = json.path("confidence");
        if (node.isMissingNode() || node.isNull()) return null;
        try {
            double d = node.asDouble(0.5);
            d = Math.max(0.0, Math.min(1.0, d));
            return BigDecimal.valueOf(Math.round(d * 100.0) / 100.0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        String val = node.asText("").strip();
        return val.isEmpty() ? null : val;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "null";
    }
}
