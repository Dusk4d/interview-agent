package com.dusk4d.interview.config;

import com.dusk4d.interview.llm.EmbeddingClient;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LocalHashEmbeddingClient;
import com.dusk4d.interview.llm.MockLlmClient;
import com.dusk4d.interview.llm.OpenAiCompatibleClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型层装配。
 *
 * <p>模型客户端按配置选择：
 * <ul>
 *   <li>{@code app.llm.provider=local}（默认）：OpenAI 兼容协议，指向 LM Studio / Ollama；</li>
 *   <li>{@code app.llm.provider=mock}：确定性 Mock，用于离线演示与自动化测试。</li>
 * </ul>
 *
 * <p>向量化同理：{@code app.embedding.mode=local} 使用进程内哈希向量（离线可用、可复现），
 * {@code remote} 使用本地模型的 embeddings 接口。两种模式都保证检索链路可运行。
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    @Bean
    public LlmClient llmClient(AppProperties properties, ObjectMapper objectMapper) {
        String provider = System.getProperty("app.llm.provider",
                System.getenv().getOrDefault("INTERVIEW_LLM_PROVIDER", "local"));
        if ("mock".equalsIgnoreCase(provider)) {
            log.warn("模型提供方为 mock：仅用于离线演示与自动化测试，不会调用真实模型。");
            return new MockLlmClient(objectMapper);
        }
        AppProperties.Llm llm = properties.llm();
        LlmClient client = new OpenAiCompatibleClient(
                llm.resolvedBaseUrl(),
                llm.resolvedApiKey(),
                llm.resolvedChatModel(),
                llm.maxTokens(),
                llm.connectTimeoutMs(),
                llm.readTimeoutMs(),
                objectMapper);
        if (llm.probeOnStartup()) {
            boolean ok = client.available();
            if (ok) {
                log.info("模型服务可用：{}（model={}）", llm.resolvedBaseUrl(), llm.resolvedChatModel());
            } else {
                log.warn("模型服务当前不可用：{}。系统仍可启动，出题/评分会返回明确的失败提示（不会抛 500）。"
                                + "可启动 LM Studio 或 Ollama，或设置 -Dapp.llm.provider=mock 使用离线 Mock 模式。",
                        llm.resolvedBaseUrl());
            }
        }
        return client;
    }

    /**
     * 向量化客户端。
     *
     * <p>{@code remote} 模式下远端不可用会自动降级到本地哈希向量：检索质量下降但系统仍可用，
     * 这与「模型失败不能让页面一直等」的验收要求一致。
     */
    @Bean
    public EmbeddingClient embeddingClient(AppProperties properties, ObjectMapper objectMapper) {
        LocalHashEmbeddingClient local = new LocalHashEmbeddingClient(properties);
        if (!properties.embedding().remote()) {
            log.info("向量化模式：local-hash（维度 {}）", local.dimension());
            return local;
        }
        AppProperties.Llm llm = properties.llm();
        AppProperties.Embedding embedding = properties.embedding();
        com.dusk4d.interview.llm.EmbeddingClient remote = new com.dusk4d.interview.llm.OpenAiCompatibleEmbeddingClient(
                llm.resolvedBaseUrl(),
                llm.resolvedApiKey(),
                llm.resolvedEmbeddingModel(),
                embedding.resolvedDimension(),
                embedding.batchSize(),
                llm.connectTimeoutMs(),
                llm.readTimeoutMs(),
                objectMapper);
        log.info("向量化模式：remote（model={}，失败自动降级 local-hash）", llm.resolvedEmbeddingModel());
        return new com.dusk4d.interview.llm.FallbackEmbeddingClient(remote, local);
    }
}
