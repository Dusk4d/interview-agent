package com.dusk4d.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 应用配置聚合。所有外部依赖（模型、存储、检索、隐私）都在这里显式声明，
 * 便于在没有本地模型的环境下降级运行（见 {@code LlmConfiguration}）。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Llm llm,
        Embedding embedding,
        Retrieval retrieval,
        Interview interview,
        Privacy privacy,
        Storage storage,
        Parser parser
) {

    public AppProperties {
        llm = llm == null ? new Llm(null, null, null, null, 0.3, 1600, 3000, 45000, 0, true) : llm;
        embedding = embedding == null ? new Embedding(null, 256, 16) : embedding;
        retrieval = retrieval == null ? new Retrieval(5, 0.08, 4000, 4) : retrieval;
        interview = interview == null ? new Interview(8, 1, 8, 4000) : interview;
        privacy = privacy == null ? new Privacy(true, false) : privacy;
        storage = storage == null ? new Storage("file", "") : storage;
        parser = parser == null ? new Parser(10 * 1024 * 1024) : parser;
    }

    /** 模型服务（OpenAI 兼容协议：LM Studio / Ollama / DeepSeek / 通义）。 */
    public record Llm(
            String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            double temperature,
            int maxTokens,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxRetries,
            boolean probeOnStartup
    ) {
        public String resolvedBaseUrl() {
            return baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:1234/v1" : baseUrl.trim();
        }

        public String resolvedChatModel() {
            return chatModel == null || chatModel.isBlank() ? "local-model" : chatModel.trim();
        }

        public String resolvedEmbeddingModel() {
            return embeddingModel == null || embeddingModel.isBlank() ? "local-embedding" : embeddingModel.trim();
        }

        public String resolvedApiKey() {
            return apiKey == null || apiKey.isBlank() ? "not-needed" : apiKey.trim();
        }
    }

    /**
     * 向量化策略。
     *
     * <p>{@code local} 为进程内确定性哈希向量，离线可用且完全可复现（测试默认）；
     * {@code remote} 调用兼容 OpenAI /embeddings 协议的服务（LM Studio + nomic-embed-text）。
     */
    public record Embedding(String mode, int dimension, int batchSize) {
        public boolean remote() {
            return "remote".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }

        public int resolvedDimension() {
            return dimension <= 0 ? 256 : dimension;
        }
    }

    public record Retrieval(int topK, double minScore, int maxContextChars, int knowledgeTopK) {
        public int resolvedTopK() {
            return topK <= 0 ? 5 : topK;
        }

        public int resolvedKnowledgeTopK() {
            return knowledgeTopK <= 0 ? 4 : knowledgeTopK;
        }
    }

    public record Interview(int maxQuestions, int followUpLimitPerQuestion,
                            int minAnswerChars, int maxAnswerChars) {
        public int resolvedMaxQuestions() {
            return maxQuestions <= 0 ? 8 : Math.min(maxQuestions, 30);
        }

        public int resolvedFollowUpLimit() {
            return followUpLimitPerQuestion <= 0 ? 0 : Math.min(followUpLimitPerQuestion, 3);
        }
    }

    public record Privacy(boolean maskPii, boolean maskName) {
    }

    public record Storage(String mode, String dataDir) {
        public boolean inMemory() {
            return "memory".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }
    }

    public record Parser(long maxFileBytes) {
        public long resolvedMaxFileBytes() {
            return maxFileBytes <= 0 ? 10L * 1024 * 1024 : maxFileBytes;
        }
    }

    /** 便捷访问器，便于在服务中书写 app.llm().readTimeoutMs()。 */
    public List<String> supportedModes() {
        return List.of("PROJECT", "KNOWLEDGE", "FULL");
    }
}
