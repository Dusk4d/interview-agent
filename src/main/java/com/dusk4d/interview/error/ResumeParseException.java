package com.dusk4d.interview.error;

import org.springframework.http.HttpStatus;

/** 简历解析失败：文件不可读、格式不支持、文本为空、疑似图片型 PDF 等。 */
public class ResumeParseException extends ApiException {

    public ResumeParseException(String code, String message) {
        super(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 格式不支持（含图片型 PDF）。 */
    public static ResumeParseException unsupported(String message) {
        return new ResumeParseException("UNSUPPORTED_FILE", message);
    }

    /** 文本为空或不可读。 */
    public static ResumeParseException emptyText(String message) {
        return new ResumeParseException("EMPTY_RESUME_TEXT", message);
    }
}
