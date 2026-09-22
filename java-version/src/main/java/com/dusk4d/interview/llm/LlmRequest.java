package com.dusk4d.interview.llm;

import java.util.List;

/**
 * 一次模型调用请求。
 *
 * @param systemPrompt 系统提示（角色、边界、禁止编造）
 * @param userPrompt   用户提示（含检索到的上下文与任务要求）
 * @param schemaName   结构化输出名称；为 null 表示只要纯文本
 * @param schemaFields 期望的字段清单，用于校验与一次格式修复
 * @param temperature  采样温度
 * @param maxTokens    最大输出 token
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        String schemaName,
        List<String> schemaFields,
        double temperature,
        int maxTokens
) {
    public LlmRequest {
        schemaFields = schemaFields == null ? List.of() : List.copyOf(schemaFields);
    }

    /** 纯文本请求。 */
    public LlmRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        this(systemPrompt, userPrompt, null, List.of(), temperature, maxTokens);
    }

    /** 结构化请求。 */
    public static LlmRequest structured(String systemPrompt, String userPrompt, String schemaName,
                                        List<String> schemaFields, double temperature, int maxTokens) {
        return new LlmRequest(systemPrompt, userPrompt, schemaName, schemaFields, temperature, maxTokens);
    }

    public boolean expectsJson() {
        return schemaName != null && !schemaName.isBlank();
    }
}
