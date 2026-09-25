package com.nightshift.payload.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static com.nightshift.constant.code.ErrorCodes.BAD_REQUEST;
import static com.nightshift.constant.code.ErrorCodes.CONFLICT;
import static com.nightshift.constant.code.ErrorCodes.INTERNAL_ERROR;
import static com.nightshift.constant.code.ErrorCodes.NOT_FOUND;
import static com.nightshift.constant.code.ErrorCodes.RATE_LIMITED;
import static com.nightshift.constant.code.ErrorCodes.VALIDATION_ERROR;

/**
 * The only place an {@link ApiResponse} is constructed. Every controller returns
 * {@code ResponseEntity<ApiResponse<T>>} through one of these factories, which is
 * what keeps the wire format identical across the API — including on errors,
 * which {@link com.nightshift.exception.GlobalExceptionHandler} routes back here.
 *
 * <h3>Success</h3>
 * <pre>
 * { "success": true,
 *   "code": "SCAN_ACCEPTED",
 *   "data": { ... },
 *   "pagination": { "total_count": 12, "page": 0, "size": 25, ... },
 *   "metadata": { "timestamp": "2026-09-25T02:00:00Z", "request_id": "..." } }
 * </pre>
 *
 * <h3>Error</h3>
 * <pre>
 * { "success": false,
 *   "error": { "code": "PATCH_DOES_NOT_APPLY", "message": "...", "details": [...] },
 *   "metadata": { ... } }
 * </pre>
 */
public final class ResponseBuilder {

    private ResponseBuilder() {
    }

    // ── Success ───────────────────────────────────────────────────────────────

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return build(HttpStatus.OK, data, null, null, null);
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data, String code, String message) {
        return build(HttpStatus.OK, data, null, code, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data, Pagination pagination) {
        return build(HttpStatus.OK, data, pagination, null, null);
    }

    /** Convenience for the paged list endpoints, which all return a {@link PageResult}. */
    public static <T> ResponseEntity<ApiResponse<List<T>>> ok(PageResult<T> page) {
        return build(HttpStatus.OK, page.items(), page.pagination(), null, null);
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data, String code, String message) {
        return build(HttpStatus.CREATED, data, null, code, message);
    }

    /** For work handed to the pipeline: the caller polls the returned run/incident. */
    public static <T> ResponseEntity<ApiResponse<T>> accepted(T data, String code, String message) {
        return build(HttpStatus.ACCEPTED, data, null, code, message);
    }

    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    private static <T> ResponseEntity<ApiResponse<T>> build(HttpStatus status, T data,
                                                            Pagination pagination,
                                                            String code, String message) {
        return ResponseEntity.status(status)
                .body(ApiResponse.success(code, message, data, pagination, ResponseMetadata.now()));
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    public static <T> ResponseEntity<ApiResponse<T>> error(HttpStatus status, String code,
                                                           String message) {
        return error(status, ErrorResponse.of(code, message));
    }

    public static <T> ResponseEntity<ApiResponse<T>> error(HttpStatus status, String code,
                                                           String message,
                                                           List<ErrorDetail> details) {
        return error(status, ErrorResponse.of(code, message, details));
    }

    public static <T> ResponseEntity<ApiResponse<T>> error(HttpStatus status, ErrorResponse error) {
        return ResponseEntity.status(status).body(ApiResponse.failure(error));
    }

    // ── Shorthands ────────────────────────────────────────────────────────────

    public static <T> ResponseEntity<ApiResponse<T>> validationError(List<ErrorDetail> details) {
        String message = details.size() == 1
                ? details.get(0).message()
                : details.size() + " validation errors occurred";
        return error(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION_ERROR, message, details);
    }

    public static <T> ResponseEntity<ApiResponse<T>> validationError(String field, String message) {
        return validationError(List.of(ErrorDetail.of(field, VALIDATION_ERROR, message)));
    }

    public static <T> ResponseEntity<ApiResponse<T>> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, NOT_FOUND, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> conflict(String message) {
        return error(HttpStatus.CONFLICT, CONFLICT, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> rateLimited(String message) {
        return error(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> internalError(String message) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, message);
    }
}
