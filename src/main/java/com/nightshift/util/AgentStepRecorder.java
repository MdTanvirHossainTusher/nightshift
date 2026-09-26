package com.nightshift.util;

import com.nightshift.model.entity.AgentStep;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.StepType;
import com.nightshift.repository.AgentStepRepository;
import com.nightshift.service.llm.LlmClient;
import com.nightshift.service.llm.LlmRequest;
import com.nightshift.service.llm.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Wraps every model and tool call, measures wall-clock latency, and persists an
 * {@link AgentStep} row for audit and evaluation replay.
 *
 * <p>Usage:
 * <pre>{@code
 * LlmResponse response = recorder.recordModelCall(
 *         scanRun, incident, AgentRole.TRIAGE, 0,
 *         client, request, inputSummary);
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStepRecorder {

    private static final String STATUS_OK    = "OK";
    private static final String STATUS_ERROR = "ERROR";

    private final AgentStepRepository agentStepRepository;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calls {@link LlmClient#complete(LlmRequest)}, records the result as an
     * {@link AgentStep} with {@code step_type = MODEL}, and returns the
     * {@link LlmResponse}.
     *
     * <p>If the call throws, an error step is recorded and the exception is re-thrown.
     *
     * @param scanRun      the owning scan run (may be {@code null} in tests)
     * @param incident     the incident being processed (may be {@code null} for scan-level steps)
     * @param role         the agent role (e.g. {@code TRIAGE})
     * @param stepIndex    0-based index within this agent's current execution
     * @param client       the LLM client to call
     * @param request      the prompt payload
     * @param inputSummary ≤ 200-char summary of the input (for the audit row)
     * @return the model response
     */
    public LlmResponse recordModelCall(ScanRun scanRun,
                                       Incident incident,
                                       AgentRole role,
                                       int stepIndex,
                                       LlmClient client,
                                       LlmRequest request,
                                       String inputSummary) {
        long start = System.currentTimeMillis();
        try {
            LlmResponse response = client.complete(request);
            long latencyMs = System.currentTimeMillis() - start;

            AgentStep step = AgentStep.builder()
                    .scanRun(scanRun)
                    .incident(incident)
                    .agentRole(role)
                    .stepIndex(stepIndex)
                    .stepType(StepType.MODEL)
                    .provider(client.provider())
                    .model(client.model())
                    .inputSummary(truncate(inputSummary, 2000))
                    .outputSummary(truncate(response.responseText(), 2000))
                    .tokensIn(response.tokensIn())
                    .tokensOut(response.tokensOut())
                    .latencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE))
                    .status(STATUS_OK)
                    .build();
            agentStepRepository.save(step);

            log.debug("AgentStep recorded: role={} stepIndex={} provider={} model={} latencyMs={}",
                    role, stepIndex, client.provider(), client.model(), latencyMs);
            return response;

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;

            AgentStep step = AgentStep.builder()
                    .scanRun(scanRun)
                    .incident(incident)
                    .agentRole(role)
                    .stepIndex(stepIndex)
                    .stepType(StepType.MODEL)
                    .provider(client.provider())
                    .model(client.model())
                    .inputSummary(truncate(inputSummary, 2000))
                    .latencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE))
                    .status(STATUS_ERROR)
                    .errorMessage(e.getMessage())
                    .build();
            agentStepRepository.save(step);

            throw e;
        }
    }

    /**
     * Records a tool call step without invoking any LLM.
     *
     * @param scanRun       the owning scan run (may be {@code null})
     * @param incident      the incident being processed (may be {@code null})
     * @param role          the agent role
     * @param stepIndex     0-based index within this agent's current execution
     * @param toolName      name of the tool invoked (e.g. {@code "code_locator"})
     * @param inputSummary  short description of the tool input
     * @param outputSummary short description of the tool output
     * @param latencyMs     elapsed time in milliseconds
     * @param error         if non-null, recorded as an error step
     */
    public void recordToolCall(ScanRun scanRun,
                               Incident incident,
                               AgentRole role,
                               int stepIndex,
                               String toolName,
                               String inputSummary,
                               String outputSummary,
                               long latencyMs,
                               Exception error) {
        AgentStep step = AgentStep.builder()
                .scanRun(scanRun)
                .incident(incident)
                .agentRole(role)
                .stepIndex(stepIndex)
                .stepType(StepType.TOOL)
                .toolName(toolName)
                .inputSummary(truncate(inputSummary, 2000))
                .outputSummary(truncate(outputSummary, 2000))
                .latencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE))
                .status(error == null ? STATUS_OK : STATUS_ERROR)
                .errorMessage(error != null ? error.getMessage() : null)
                .build();
        agentStepRepository.save(step);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
