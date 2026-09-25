package com.nightshift.exception;

import org.springframework.http.HttpStatus;

import static com.nightshift.constant.code.ErrorCodes.UPSTREAM_SERVICE_UNAVAILABLE;

/** GitHub, SMTP, or a model provider was unreachable or answered non-2xx. */
public class ExternalServiceException extends AppException {

    public ExternalServiceException(String service, String message) {
        super(HttpStatus.BAD_GATEWAY, UPSTREAM_SERVICE_UNAVAILABLE, service + ": " + message);
    }

    public ExternalServiceException(String service, String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, UPSTREAM_SERVICE_UNAVAILABLE, service + ": " + message,
                null, cause);
    }
}
