package com.nightshift.payload.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The single envelope every endpoint returns. Built through {@link ResponseBuilder};
 * controllers never construct it by hand and never return a bare body.
 */
@Schema(description = "Standard response envelope")
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Pagination pagination,
        ResponseMetadata metadata,
        ErrorResponse error
) {
    public static <T> ApiResponse<T> success(String code, String message, T data,
                                             Pagination pagination, ResponseMetadata metadata) {
        return new ApiResponse<>(true, code, message, data, pagination, metadata, null);
    }

    public static <T> ApiResponse<T> failure(ErrorResponse error) {
        return new ApiResponse<>(false, null, null, null, null, ResponseMetadata.now(), error);
    }
}
