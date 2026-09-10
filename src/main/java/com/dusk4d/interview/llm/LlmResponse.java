package com.dusk4d.interview.llm;

/**
 * 模型返回内容。
 *
 * @param content     正文（结构化请求时应为 JSON 字符串）
 * @param model       实际使用的模型名
 * @param elapsedMs   调用耗时
 * @param promptTokens 提示 token 数（服务端返回时才有）
 * @param outputTokens 输出 token 数（服务端返回时才有）
 */
public record LlmResponse(
        String content,
        String model,
        long elapsedMs,
        int promptTokens,
        int outputTokens
) {
    public LlmResponse(String content, String model, long elapsedMs) {
        this(content, model, elapsedMs, 0, 0);
    }

    public boolean blank() {
        return content == null || content.isBlank();
    }
}
