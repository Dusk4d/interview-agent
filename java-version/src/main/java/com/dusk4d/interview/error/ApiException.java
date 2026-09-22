package com.dusk4d.interview.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带稳定的错误码与 HTTP 状态，避免把模型超时、非法 JSON、
 * 检索为空这类可预期失败暴露成 500 堆栈。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ApiException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ApiException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
