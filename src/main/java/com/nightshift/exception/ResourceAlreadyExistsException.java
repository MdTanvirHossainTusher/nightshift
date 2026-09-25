package com.nightshift.exception;

import org.springframework.http.HttpStatus;

import static com.nightshift.constant.code.ErrorCodes.CONFLICT;

public class ResourceAlreadyExistsException extends AppException {

    public ResourceAlreadyExistsException(String message) {
        super(HttpStatus.CONFLICT, CONFLICT, message);
    }

    public ResourceAlreadyExistsException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
