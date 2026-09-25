package com.nightshift.payload.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Error body; present only when success = false")
public record ErrorResponse(
        String code,
        String message,
        List<ErrorDetail> details
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    public static ErrorResponse of(String code, String message, List<ErrorDetail> details) {
        return new ErrorResponse(code, message, details);
    }
}
