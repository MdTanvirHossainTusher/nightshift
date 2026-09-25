package com.nightshift.exception;

import org.springframework.http.HttpStatus;

/**
 * An agent step failed in a way the pipeline should record rather than retry
 * blindly: an unparseable model response, a blown token budget, a provider
 * outage. The incident is parked with this code so a nightly run finishes the
 * remaining incidents instead of dying on one.
 */
public class AgentExecutionException extends AppException {

    public AgentExecutionException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public AgentExecutionException(String code, String message, Throwable cause) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message, null, cause);
    }
}
