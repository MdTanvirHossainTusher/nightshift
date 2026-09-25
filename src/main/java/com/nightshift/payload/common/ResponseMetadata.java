package com.nightshift.payload.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Per-response diagnostics")
public record ResponseMetadata(
        Instant timestamp,

        @JsonProperty("request_id")
        String requestId
) {
    /**
     * Uses the MDC request id when {@code RequestIdFilter} has set one, so an API
     * error and its log line can be correlated; falls back to a fresh id.
     */
    public static ResponseMetadata now() {
        String fromMdc = org.slf4j.MDC.get("requestId");
        return new ResponseMetadata(Instant.now(),
                fromMdc != null ? fromMdc : UUID.randomUUID().toString());
    }
}
