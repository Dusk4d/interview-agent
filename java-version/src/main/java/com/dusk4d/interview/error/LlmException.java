package com.dusk4d.interview.error;

import org.springframework.http.HttpStatus;

/**
 * 模型服务不可用或返回不可用内容。
 *
 * <p>区分 {@link Kind}：连接失败（未启动本地模型）、超时、非法响应体、结构化输出失败。
 * 只有 {@code INVALID_STRUCTURE} 会触发一次「格式修复重试」，其余不重试。
 */
public class LlmException extends ApiException {

    public enum Kind {
        /** 无法建立连接：本地模型服务未启动 */
        CONNECTION,
        /** 读取超时 */
        TIMEOUT,
        /** HTTP 非 2xx 或响应体非法 JSON */
        BAD_RESPONSE,
        /** 结构化输出不满足 Schema */
        INVALID_STRUCTURE
    }

    private final Kind kind;

    public LlmException(Kind kind, String code, HttpStatus status, String message, Throwable cause) {
        super(code, status, message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 映射到 llm 包的中立失败类型（供上层降级与指标统计使用）。 */
    public com.dusk4d.interview.llm.LlmFailureKind toFailureKind() {
        return switch (kind) {
            case CONNECTION -> com.dusk4d.interview.llm.LlmFailureKind.CONNECTION;
            case TIMEOUT -> com.dusk4d.interview.llm.LlmFailureKind.TIMEOUT;
            case BAD_RESPONSE -> com.dusk4d.interview.llm.LlmFailureKind.BAD_RESPONSE;
            case INVALID_STRUCTURE -> com.dusk4d.interview.llm.LlmFailureKind.INVALID_STRUCTURE;
        };
    }

    public static LlmException connection(String message, Throwable cause) {
        return new LlmException(Kind.CONNECTION, "LLM_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    public static LlmException timeout(String message, Throwable cause) {
        return new LlmException(Kind.TIMEOUT, "LLM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, message, cause);
    }

    public static LlmException badResponse(String message, Throwable cause) {
        return new LlmException(Kind.BAD_RESPONSE, "LLM_BAD_RESPONSE", HttpStatus.BAD_GATEWAY, message, cause);
    }

    public static LlmException invalidStructure(String message) {
        return new LlmException(Kind.INVALID_STRUCTURE, "LLM_INVALID_STRUCTURE",
                HttpStatus.BAD_GATEWAY, message, null);
    }
}
