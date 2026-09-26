package com.nightshift.payload.response;

import com.nightshift.model.entity.AgentStep;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.StepType;

import java.time.Instant;
import java.util.UUID;

public record AgentStepResponse(
        UUID id,
        UUID scanRunId,
        UUID incidentId,
        String incidentTitle,
        AgentRole agentRole,
        int stepIndex,
        StepType stepType,
        String toolName,
        String provider,
        String model,
        String inputSummary,
        String outputSummary,
        Integer tokensIn,
        Integer tokensOut,
        Integer latencyMs,
        String status,
        String errorMessage,
        Instant createdAt
) {
    public static AgentStepResponse from(AgentStep s) {
        if (s == null) return null;
        return new AgentStepResponse(
                s.getId(),
                s.getScanRun() != null ? s.getScanRun().getId() : null,
                s.getIncident() != null ? s.getIncident().getId() : null,
                s.getIncident() != null ? s.getIncident().getTitle() : null,
                s.getAgentRole(),
                s.getStepIndex(),
                s.getStepType(),
                s.getToolName(),
                s.getProvider(),
                s.getModel(),
                s.getInputSummary(),
                s.getOutputSummary(),
                s.getTokensIn(),
                s.getTokensOut(),
                s.getLatencyMs(),
                s.getStatus(),
                s.getErrorMessage(),
                s.getCreatedAt()
        );
    }
}