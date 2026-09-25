package com.nightshift.exception;

import org.springframework.http.HttpStatus;

/**
 * The guard layer refused a proposed patch: it did not apply, it exceeded the
 * size budget, or it touched a denied path. Never retried automatically — a
 * rejected patch is a finding about the agent, and it is surfaced on the
 * incident for a human to read.
 */
public class PatchRejectedException extends AppException {

    public PatchRejectedException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
