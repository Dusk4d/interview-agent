package com.dusk4d.interview.error;

import org.springframework.http.HttpStatus;

/** 会话状态非法（例如终态会话继续提交回答、未出题就回答）。 */
public class InvalidSessionStateException extends ApiException {

    public InvalidSessionStateException(String message) {
        super("INVALID_SESSION_STATE", HttpStatus.CONFLICT, message);
    }
}
