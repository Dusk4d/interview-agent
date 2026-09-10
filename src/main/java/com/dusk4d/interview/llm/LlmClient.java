package com.dusk4d.interview.llm;

/**
 * 模型适配层入口。
 *
 * <p>实现必须是可替换的：本地 LM Studio / Ollama（OpenAI 兼容）、远端服务、
 * 以及测试用的确定性 Mock。上层只依赖本接口，不感知具体协议。
 */
public interface LlmClient {

    /** 执行一次对话补全；失败时抛出 {@link com.dusk4d.interview.error.LlmException}。 */
    LlmResponse chat(LlmRequest request);

    /** 当前生效的模型名（用于日志与前端展示）。 */
    String modelName();

    /** 提供方标识：lmstudio / ollama / openai-compatible / mock。 */
    String provider();

    /**
     * 轻量可用性探测，不抛异常。
     *
     * @return true 表示可以正常调用
     */
    boolean available();
}
