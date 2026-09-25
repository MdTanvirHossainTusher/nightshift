package com.nightshift.exception;

import com.nightshift.payload.common.ApiResponse;
import com.nightshift.payload.common.ErrorDetail;
import com.nightshift.payload.common.ResponseBuilder;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static com.nightshift.constant.code.ErrorCodes.BAD_REQUEST;
import static com.nightshift.constant.code.ErrorCodes.DATABASE_TEMPORARILY_UNAVAILABLE;
import static com.nightshift.constant.code.ErrorCodes.DATA_INTEGRITY_VIOLATION;
import static com.nightshift.constant.code.ErrorCodes.DUPLICATE_ENTRY;
import static com.nightshift.constant.code.ErrorCodes.METHOD_NOT_ALLOWED;
import static com.nightshift.constant.code.ErrorCodes.VALIDATION_ERROR;

/**
 * Turns every exception that escapes a controller into the same
 * {@link ApiResponse} envelope. Two rules:
 *
 * <ol>
 *   <li>A 4xx is logged at WARN with no stack trace — it is the caller's mistake,
 *       and a stack trace per bad request is noise that buries real failures.
 *       A 5xx is logged at ERROR with the stack trace.</li>
 *   <li>The client never sees an exception message we did not write. Anything
 *       unmapped becomes a generic 500, because a Hibernate or JDBC message can
 *       leak schema and connection detail.</li>
 * </ol>
 *
 * <p>Nightshift reads its own logs during a self-scan demo, so this class is also
 * what makes those logs parseable: one line per failure, code first.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Deliberate application exceptions ─────────────────────────────────────

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Object>> handleAppException(AppException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("code={} status={} message={}", ex.getCode(), ex.getStatus().value(),
                    ex.getMessage(), ex);
        } else {
            log.warn("code={} status={} message={}", ex.getCode(), ex.getStatus().value(),
                    ex.getMessage());
        }
        return ResponseBuilder.error(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    // ── Bean validation ───────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {

        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> ErrorDetail.of(err.getField(), VALIDATION_ERROR,
                        err.getDefaultMessage()))
                .toList();

        log.warn("Validation failed on {} field(s): {}", details.size(),
                ex.getBindingResult().getFieldErrors().stream()
                        .map(FieldError::getField).distinct().toList());

        return ResponseBuilder.validationError(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex) {

        List<ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(v -> ErrorDetail.of(v.getPropertyPath().toString(), VALIDATION_ERROR,
                        v.getMessage()))
                .toList();

        log.warn("Constraint violation on {} path(s)", details.size());
        return ResponseBuilder.validationError(details);
    }

    // ── Malformed requests ────────────────────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadableBody(
            HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseBuilder.badRequest("Request body is missing or not valid JSON");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return ResponseBuilder.validationError(ex.getParameterName(),
                "Required parameter is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return ResponseBuilder.validationError(ex.getName(),
                "Value is not a valid " + (ex.getRequiredType() == null
                        ? "value" : ex.getRequiredType().getSimpleName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseBuilder.error(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED,
                ex.getMethod() + " is not supported on this endpoint");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException ex) {
        return ResponseBuilder.notFound("No endpoint " + ex.getResourcePath());
    }

    // ── Database ──────────────────────────────────────────────────────────────

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataIntegrity(
            DataIntegrityViolationException ex) {

        String cause = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase();
        log.warn("Data integrity violation: {}", cause);

        // Constraint names are the only reliable signal here; the driver message is
        // not portable. Keep this translation table small and intentional.
        if (cause.contains("unique") || cause.contains("duplicate key")) {
            return ResponseBuilder.error(HttpStatus.CONFLICT, DUPLICATE_ENTRY,
                    "A record with these values already exists");
        }
        return ResponseBuilder.error(HttpStatus.CONFLICT, DATA_INTEGRITY_VIOLATION,
                "The request conflicts with an existing record");
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<ApiResponse<Object>> handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable", ex);
        return ResponseBuilder.error(HttpStatus.SERVICE_UNAVAILABLE,
                DATABASE_TEMPORARILY_UNAVAILABLE, "The database is temporarily unavailable");
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseBuilder.error(HttpStatus.BAD_REQUEST, BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseBuilder.internalError("An unexpected error occurred");
    }
}
