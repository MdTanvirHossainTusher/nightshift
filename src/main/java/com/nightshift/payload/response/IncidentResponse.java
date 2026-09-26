package com.nightshift.payload.response;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.enums.Category;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
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
        UUID lastScanRunId
) {
    public static IncidentResponse from(Incident i) {
        return from(i, null);
    }

    public static IncidentResponse from(Incident i, UUID lastScanRunId) {
        if (i == null) return null;
        return new IncidentResponse(
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
                lastScanRunId
        );
    }
}