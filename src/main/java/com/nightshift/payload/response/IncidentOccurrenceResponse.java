package com.nightshift.payload.response;

import com.nightshift.model.entity.IncidentOccurrence;

import java.time.Instant;
import java.util.UUID;

public record IncidentOccurrenceResponse(
        UUID id,
        Instant occurredAt,
        String logFile,
        Integer lineNumber,
        String traceId,
        String threadName,
        String rawLine
) {
    public static IncidentOccurrenceResponse from(IncidentOccurrence o) {
        if (o == null) return null;
        return new IncidentOccurrenceResponse(
                o.getId(),
                o.getOccurredAt(),
                o.getLogFile(),
                o.getLineNumber(),
                o.getTraceId(),
                o.getThreadName(),
                o.getRawLine()
        );
    }
}