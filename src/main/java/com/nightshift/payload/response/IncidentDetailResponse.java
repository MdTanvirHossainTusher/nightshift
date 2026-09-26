package com.nightshift.payload.response;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.enums.Category;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentDetailResponse(
        UUID id,
        String fingerprint,
        String title,
        String serviceName,
        String loggerName,
        String logLevel,
        String exceptionType,
        IncidentStatus status,
        Severity severity,
        String severityRationale,
        Category category,
        String rootCause,
        String futureImpact,
        String recommendedAction,
        BigDecimal confidence,
        int occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean muted,
        String muteReason,
        String assigneeEmail,
        String assigneeHandle,
        String normalizedMessage,
        String sampleMessage,
        String sampleStacktrace,
        Instant triagedAt,
        String triageProvider,
        String triageModel,
        List<CodeLocationResponse> codeLocations,
        List<PatchProposalResponse> patchProposals,
        List<PullRequestResponse> pullRequests,
        List<IncidentOccurrenceResponse> recentOccurrences
) {
    public static IncidentDetailResponse of(Incident i,
                                           List<CodeLocationResponse> codeLocations,
                                           List<PatchProposalResponse> patchProposals,
                                           List<PullRequestResponse> pullRequests,
                                           List<IncidentOccurrenceResponse> occurrences) {
        if (i == null) return null;
        return new IncidentDetailResponse(
                i.getId(),
                i.getFingerprint(),
                i.getTitle(),
                i.getServiceName(),
                i.getLoggerName(),
                i.getLogLevel(),
                i.getExceptionType(),
                i.getStatus(),
                i.getSeverity(),
                i.getSeverityRationale(),
                i.getCategory(),
                i.getRootCause(),
                i.getFutureImpact(),
                i.getRecommendedAction(),
                i.getConfidence(),
                i.getOccurrenceCount(),
                i.getFirstSeenAt(),
                i.getLastSeenAt(),
                i.isMuted(),
                i.getMuteReason(),
                i.getAssigneeEmail(),
                i.getAssigneeHandle(),
                i.getNormalizedMessage(),
                i.getSampleMessage(),
                i.getSampleStacktrace(),
                i.getTriagedAt(),
                i.getTriageProvider(),
                i.getTriageModel(),
                codeLocations != null ? codeLocations : List.of(),
                patchProposals != null ? patchProposals : List.of(),
                pullRequests != null ? pullRequests : List.of(),
                occurrences != null ? occurrences : List.of()
        );
    }
}