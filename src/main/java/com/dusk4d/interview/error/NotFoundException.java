package com.dusk4d.interview.error;

import org.springframework.http.HttpStatus;

/** 资源不存在（简历 / 会话 / 报告 / 问题）。 */
public class NotFoundException extends ApiException {

    public NotFoundException(String resource, String id) {
        super("NOT_FOUND", HttpStatus.NOT_FOUND, resource + " 不存在：" + id);
    }
}
