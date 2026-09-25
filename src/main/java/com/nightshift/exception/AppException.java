package com.nightshift.exception;

import com.nightshift.payload.common.ErrorDetail;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Base for every exception this service throws deliberately. It carries the HTTP
 * status and the {@link com.nightshift.constant.code.ErrorCodes} constant, so
 * {@link GlobalExceptionHandler} can render any subclass without a per-type branch.
 */
@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;

    public AppException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public AppException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        this(status, code, message, details, null);
    }

    public AppException(HttpStatus status, String code, String message,
                        List<ErrorDetail> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
