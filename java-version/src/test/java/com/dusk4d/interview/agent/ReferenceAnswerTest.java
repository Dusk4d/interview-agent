package com.dusk4d.interview.agent;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LlmRequest;
import com.dusk4d.interview.llm.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参考回答（referenceAnswer）的解析与<b>防编造</b>校验。
 *
 * <p>参考回答是给用户照着复述的，一旦混入他没做过的指标，用户很可能直接背下来去面试——
 * 这是本项目最不能接受的失败方式。所以提示词之外，代码里必须再挡一道。
 */
@DisplayName("参考回答与防编造校验")
class ReferenceAnswerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED = Instant.parse("2024-06-01T10:00:00Z");
    private static final String ANSWER_WITH_METRIC =
            "我用 Redis 做幂等，QPS 从 800 提升到 3200，重复下单率降到 60%。";

    private InterviewQuestion question() {
        return new InterviewQuestion("q-1", "s-1", 1, QuestionType.PROJECT_TECH, Difficulty.HARD,
                "你负责了什么？", "考察职责", List.of("个人职责"), List.of("resume-project-001"),
                List.of("简历片段：订单中台"), "追问机制", null, false, "SINGLE_QUESTION", FIXED);
    }

    private AnswerEvaluation evaluate(String modelReferenceAnswer, String answer) {
        LlmClient stub = new StubLlmClient(modelReferenceAnswer);
        AppProperties properties = new AppProperties(
                new AppProperties.Llm("http://127.0.0.1:1/v1", "test", "mock", "mock-embed",
                        0.0, 800, 500, 1000, 0, false, null, null, null),
                new AppProperties.Embedding("local", 128, 8),
                new AppProperties.Retrieval(4, 0.05, 4000, 3),
                new AppProperties.Interview(6, 1, 8, 4000, 3),
                new AppProperties.Privacy(true, false),
                new AppProperties.Storage("memory", ""),
                new AppProperties.Parser(10 * 1024 * 1024));
        AnswerEvaluator evaluator = new AnswerEvaluator(stub, new StructuredOutputParser(MAPPER), properties,
                Clock.fixed(FIXED, ZoneOffset.UTC));
        List<ResumeFact> facts = List.of(new ResumeFact("resume-project-001", "resume-1", FactType.PROJECT,
                "订单中台", "我负责召回与排序链路，QPS 从 800 提升到 3200。", 1, 0.9, List.of()));
        return evaluator.evaluate(question(), answer, "[1] 简历片段：订单中台\nQPS 从 800 提升到 3200。", facts);
    }

    @Test
    @DisplayName("模型给出参考回答且指标有出处 → 原样保留")
    void keepsGroundedReferenceAnswer() {
        String reference = "背景是订单中台重复下单问题。我负责幂等设计，用 Redis 做去重，"
                + "QPS 从 800 提升到 3200，重复下单率降到 60%。";

        AnswerEvaluation evaluation = evaluate(reference, ANSWER_WITH_METRIC);

        assertThat(evaluation.referenceAnswer()).isEqualTo(reference);
        assertThat(evaluation.corrections()).noneMatch(c -> c.contains("已丢弃"));
    }

    @Test
    @DisplayName("参考回答编造了原回答里没有的指标 → 整段丢弃并说明原因")
    void dropsFabricatedMetric() {
        // 用户没说过 5 倍、没说过 200ms，模型却替他把数字补上了
        String reference = "我负责幂等设计，用 Redis 去重，性能提升了 5 倍，平均耗时降到 200ms。";

        AnswerEvaluation evaluation = evaluate(reference, ANSWER_WITH_METRIC);

        assertThat(evaluation.referenceAnswer()).isEmpty();
        assertThat(evaluation.corrections())
                .anyMatch(c -> c.contains("已丢弃") && c.contains("编造"));
    }

    @Test
    @DisplayName("参考回答里的数字若在简历事实里出现过 → 不算编造")
    void allowsMetricPresentInResumeFacts() {
        // 用户回答里没写 3200，但简历事实里有；这类复用是允许的（不是编造）
        String reference = "我负责召回链路，QPS 从 800 提升到 3200。";

        AnswerEvaluation evaluation = evaluate(reference, "我负责召回链路的优化工作。");

        assertThat(evaluation.referenceAnswer()).isEqualTo(reference);
    }

    @Test
    @DisplayName("「第一步」「两个模块」这类序数词不算指标，不能误杀")
    void ordinalWordsAreNotMetrics() {
        String reference = "第一步梳理链路，第二步把两个模块合并，最后用 Redis 做去重。";

        AnswerEvaluation evaluation = evaluate(reference, "我梳理了链路并合并了模块。");

        assertThat(evaluation.referenceAnswer()).isEqualTo(reference);
    }

    @Test
    @DisplayName("模型不给参考回答 → 字段为空，不影响评分与其余反馈")
    void emptyReferenceAnswerIsFine() {
        AnswerEvaluation evaluation = evaluate("", ANSWER_WITH_METRIC);

        assertThat(evaluation.referenceAnswer()).isEmpty();
        assertThat(evaluation.totalScore()).isBetween(0.0, 5.0);
        assertThat(evaluation.dimensionScores()).hasSize(4);
        assertThat(evaluation.corrections()).noneMatch(c -> c.contains("已丢弃"));
    }

    @Test
    @DisplayName("降级评估不给参考回答（没有语义理解能力时不能硬拼一段像真的）")
    void degradedEvaluationHasNoReferenceAnswer() {
        LlmClient failing = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                throw com.dusk4d.interview.error.LlmException.connection("测试：模型不可达", null);
            }

            @Override
            public String modelName() {
                return "stub";
            }

            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public boolean available() {
                return false;
            }
        };
        AppProperties properties = new AppProperties(
                new AppProperties.Llm("http://127.0.0.1:1/v1", "test", "mock", "mock-embed",
                        0.0, 800, 500, 1000, 0, false, null, null, null),
                new AppProperties.Embedding("local", 128, 8),
                new AppProperties.Retrieval(4, 0.05, 4000, 3),
                new AppProperties.Interview(6, 1, 8, 4000, 3),
                new AppProperties.Privacy(true, false),
                new AppProperties.Storage("memory", ""),
                new AppProperties.Parser(10 * 1024 * 1024));
        AnswerEvaluator evaluator = new AnswerEvaluator(failing, new StructuredOutputParser(MAPPER), properties,
                Clock.fixed(FIXED, ZoneOffset.UTC));

        AnswerEvaluation evaluation = evaluator.evaluate(question(), ANSWER_WITH_METRIC, "", List.of());

        assertThat(evaluation.degraded()).isTrue();
        assertThat(evaluation.referenceAnswer()).isEmpty();
    }

    /** 用固定 JSON 冒充模型输出，避免依赖真实模型。 */
    private record StubLlmClient(String referenceAnswer) implements LlmClient {
        @Override
        public LlmResponse chat(LlmRequest request) {
            String json = """
                    {
                      "dimensionScores": {
                        "technical_correctness": { "score": 4, "reason": "机制正确" },
                        "completeness": { "score": 3, "reason": "缺少结果验证" },
                        "experience_match": { "score": 4, "reason": "与简历一致" },
                        "structure": { "score": 3, "reason": "结构一般" }
                      },
                      "strengths": ["提到了机制"],
                      "missingPoints": ["缺少量化结果"],
                      "corrections": [],
                      "suggestedAdditions": ["补充结果"],
                      "referenceAnswerStructure": "背景 → 个人职责 → 技术机制 → 难点取舍 → 结果验证",
                      "referenceAnswer": "%s",
                      "evidenceWarnings": [],
                      "followUpRecommended": false,
                      "followUpFocus": "",
                      "summary": "总体可用。"
                    }
                    """.formatted(referenceAnswer.replace("\"", "\\\""));
            return new LlmResponse(json, "stub", 1L);
        }

        @Override
        public String modelName() {
            return "stub";
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
