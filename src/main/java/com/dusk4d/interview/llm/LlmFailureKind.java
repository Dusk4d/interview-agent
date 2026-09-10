package com.dusk4d.interview.llm;

/** 模型调用失败类型。 */
public enum LlmFailureKind {
    /** 无法建立连接：本地模型服务未启动 */
    CONNECTION,
    /** 读取超时 */
    TIMEOUT,
    /** HTTP 非 2xx 或响应体不是合法 JSON */
    BAD_RESPONSE,
    /** 结构化输出不满足 Schema */
    INVALID_STRUCTURE
}
