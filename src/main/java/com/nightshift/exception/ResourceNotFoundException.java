package com.nightshift.exception;

import org.springframework.http.HttpStatus;

import static com.nightshift.constant.code.ErrorCodes.NOT_FOUND;

public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, NOT_FOUND, message);
    }

    /** Reads as {@code new ResourceNotFoundException("Incident", id)}. */
    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, NOT_FOUND, resource + " " + id + " was not found");
    }
}
