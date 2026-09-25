package com.nightshift.exception;

import com.nightshift.payload.common.ErrorDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

import static com.nightshift.constant.code.ErrorCodes.BAD_REQUEST;

public class BadResourceRequestException extends AppException {

    public BadResourceRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, BAD_REQUEST, message);
    }

    public BadResourceRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }

    public BadResourceRequestException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.BAD_REQUEST, code, message, details);
    }
}
