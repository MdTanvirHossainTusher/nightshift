package com.nightshift.payload.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One field-level or rule-level error")
public record ErrorDetail(
        String field,
        String type,
        String message
) {
    public static ErrorDetail of(String field, String type, String message) {
        return new ErrorDetail(field, type, message);
    }
}
