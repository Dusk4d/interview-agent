package com.dusk4d.interview.error;

import org.springframework.http.HttpStatus;

/** 参数/输入校验失败。 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, message);
    }
}
