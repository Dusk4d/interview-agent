package com.dusk4d.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

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
        // 注意：这里只把缺失的整块配置替换为默认块，不做「部分字段兜底」。
        // 每个子 record 同样只保留规范构造器——Spring 的绑定器一旦发现多个构造器
        // 就会静默放弃绑定（见 Llm 的说明）。
        llm = llm == null ? Llm.defaults() : llm;
        embedding = embedding == null ? new Embedding(null, 256, 16) : embedding;
        retrieval = retrieval == null ? new Retrieval(5, 0.08, 4000, 4) : retrieval;
        interview = interview == null ? new Interview(8, 1, 8, 4000, 3) : interview;
        privacy = privacy == null ? new Privacy(true, false) : privacy;
        storage = storage == null ? new Storage("file", "") : storage;
        parser = parser == null ? new Parser(10 * 1024 * 1024) : parser;
    }

    /**
     * 模型服务（OpenAI 兼容协议：LM Studio / Ollama / DeepSeek / 通义）。
     *
     * <p><b>重要约束：这个 record 只能有规范构造器（canonical constructor）。</b>
     * 曾经为了构造方便加过一个 10 参数的便捷构造器，结果是 Spring 的绑定器无法确定
     * 该用哪个构造器，于是**静默放弃绑定、保留字段默认值**——表现为
     * {@code Environment} 能取到正确配置，但注入到服务里的 {@code AppProperties} 全是 null。
     * 这个缺陷在纯单元测试里看不出来（手动 Binder 用的是规范构造器），
     * 只有启动真实上下文才会暴露，因此 {@code VectorIndexInitializerTest.BindingTest} 专门守住它。
     * 需要默认值时请用 {@link #defaults()} 工厂方法，不要新增构造器。
     */
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
            boolean probeOnStartup,
            Boolean disableThinking,
            Double evalTemperature,
            Integer evalSamples
    ) {
        /** 默认值（不配置任何 app.llm.* 时使用）；唯一构造入口，避免出现第二个构造器。 */
        public static Llm defaults() {
            return new Llm(null, null, null, null, 0.3, 1600, 3000, 45000, 0, true, null, null, null);
        }

        /**
         * 评分采样次数（取中位数以压制方差）。
         *
         * <p>为什么需要：即使把温度降到 0.0 并固定分档标准，小模型对「答非所问」这类边界
         * 回答仍会偶尔给出明显偏离的分数（实测同一回答出现 0.0 与 1.25）。
         * 对这种离散打分，多次采样取中位数能把离群值压掉，代价是评分耗时与调用量成倍增加。
         * 默认 1（单次评分，最省）；对评分稳定性要求高时可设为 3，或设为奇数以获得真正的中位数。
         * 取值范围 1~5；偶数会被向上取整为奇数。
         */
        public int resolvedEvalSamples() {
            int samples = evalSamples == null ? 1 : evalSamples;
            samples = Math.max(1, Math.min(5, samples));
            return samples % 2 == 0 ? samples + 1 : samples;
        }

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

        /**
         * 评分时的采样温度。
         *
         * <p>为什么评分要单独用一个温度：出题需要一点多样性（避免每次都是同一个问法），
         * 但评分需要的是<b>可复现</b>——同一个回答重复评分应当得到接近的分数。
         * 实测默认 0.3 温度下，同一份回答重复评分的总分极差可达 1.3 分，
         * 这对「评分可解释、可复现」的核心主张是硬伤。
         * 因此评分默认使用 0.0（贪心解码），出题仍用 {@code temperature}。
         * 可用 {@code app.llm.eval-temperature} 覆盖。
         */
        public double resolvedEvalTemperature() {
            return evalTemperature == null ? 0.0 : Math.max(0.0, Math.min(1.0, evalTemperature));
        }

        /**
         * 是否需要抑制「思考链」输出。
         *
         * <p>背景：Qwen3 / R1 这类混合推理模型默认先输出一大段思考内容。在只有一两千 token
         * 预算的结构化任务里，思考会吃掉全部额度并导致 {@code content} 为空
         * （本机实测 qwen3:1.7b 一个短问题要 6~10 秒且正文可能为空）。
         * 我们只需要结构化结果，因此对已知推理模型默认追加 {@code /no_think}。
         * 显式配置 {@code app.llm.disable-thinking} 时以配置为准。
         */
        public boolean shouldDisableThinking() {
            if (disableThinking != null) {
                return disableThinking;
            }
            String model = resolvedChatModel().toLowerCase(Locale.ROOT);
            return model.startsWith("qwen3") || model.contains("qwq")
                    || model.startsWith("deepseek-r1") || model.contains("reasoning")
                    || model.startsWith("magistral") || model.contains("thinking");
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
                            int minAnswerChars, int maxAnswerChars, int maxQuestionSkips) {
        public int resolvedMaxQuestions() {
            return maxQuestions <= 0 ? 8 : Math.min(maxQuestions, 30);
        }

        public int resolvedFollowUpLimit() {
            return followUpLimitPerQuestion <= 0 ? 0 : Math.min(followUpLimitPerQuestion, 3);
        }

        /**
         * 一场面试最多能「换一道题」几次。
         *
         * <p>换题不消耗题量配额（被换掉的题不计入报告），所以要有个上限，
         * 否则可以一直换到抽到一道好答的题为止。
         */
        public int resolvedMaxQuestionSkips() {
            return maxQuestionSkips < 0 ? 0 : Math.min(maxQuestionSkips, 20);
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
