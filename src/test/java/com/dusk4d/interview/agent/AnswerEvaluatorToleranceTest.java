package com.dusk4d.interview.agent;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.error.LlmException;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评分结果容错测试。
 *
 * <p>全部来自真实模型（qwen3:1.7b）实测暴露的问题，而不是想象出来的边界：
 * <ol>
 *   <li>模型把 {@code dimensionScores} 返回成<b>数组</b>而不是对象；</li>
 *   <li>数组元素的维度名放在 {@code dimension} 字段；</li>
 *   <li>分数按 <b>10 分制</b>给（score=8）；</li>
 *   <li>分数嵌在 {@code scores.value} 里；</li>
 *   <li>打分依据字段名五花八门（reason / comment / explanation / scores.reason）。</li>
 * </ol>
 *
 * <p>最严重的一种失败是「静默记 0 分」：模型输出无法解析时若直接给四个 0 分，
 * 用户会以为是自己答得极差。因此这里也锁住「解析不出任何维度必须降级并标明」的行为。
 */
@DisplayName("评分容错 AnswerEvaluator")
class AnswerEvaluatorToleranceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppProperties PROPERTIES = new AppProperties(
            new AppProperties.Llm("http://127.0.0.1:1/v1", "k", "mock", "e", 0.2, 800, 500, 1000, 0, false, null),
            null, null, null, null, null, null);

    private static InterviewQuestion question() {
        return new InterviewQuestion("q-1", "s-1", 1, QuestionType.PROJECT_TECH, Difficulty.MEDIUM,
                "你在订单系统中如何保证幂等？", "考察幂等实现", List.of("幂等"),
                List.of("resume-project-001"), List.of("简历片段：订单中心"), "追问机制", null, false,
                "SINGLE_QUESTION", Instant.now());
    }

    /** 用固定 JSON 回应替换模型，便于精确验证解析逻辑。 */
    private static AnswerEvaluator evaluatorReturning(Function<LlmRequest, String> responder) {
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return new LlmResponse(responder.apply(request), "stub", 1L);
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
        };
        return new AnswerEvaluator(client, new StructuredOutputParser(MAPPER), PROPERTIES,
                Clock.fixed(Instant.parse("2024-06-01T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("数组形式的 dimensionScores（dimension 字段 + 10 分制）应被正确解析并归一化")
    void parsesArrayWithDimensionFieldAndTenPointScale() {
        String json = """
                {
                  "dimensionScores": [
                    { "dimension": "technical_correctness", "score": 8, "reason": "机制描述正确" },
                    { "dimension": "completeness", "score": 7, "comment": "缺少量化结果" },
                    { "dimension": "experience_match", "score": 10, "explanation": "与简历职责一致" },
                    { "dimension": "structure", "score": 9, "rationale": "结构清晰" }
                  ],
                  "summary": "整体不错"
                }
                """;
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "用 Redis 加锁保证幂等。", "", List.of());

        assertThat(evaluation.degraded()).isFalse();
        assertThat(evaluation.dimensionScores()).hasSize(4);
        assertThat(evaluation.dimensionScores().get("technical_correctness").score()).isEqualTo(4.0);
        assertThat(evaluation.dimensionScores().get("completeness").score()).isEqualTo(3.5);
        assertThat(evaluation.dimensionScores().get("experience_match").score()).isEqualTo(5.0);
        assertThat(evaluation.dimensionScores().get("structure").score()).isEqualTo(4.5);
        assertThat(evaluation.totalScore()).isEqualTo(4.25);
        // 依据字段名各异，都必须被取到
        assertThat(evaluation.dimensionScores().values())
                .allSatisfy(dimension -> assertThat(dimension.reason())
                        .isNotBlank().isNotEqualTo("模型未给出打分依据"));
    }

    @Test
    @DisplayName("分数嵌在 scores.value 里也能解析")
    void parsesNestedScoreObject() {
        String json = """
                {
                  "dimensionScores": [
                    { "dimension": "technical_correctness", "scores": { "value": 4, "reason": "推理正确" } },
                    { "dimension": "completeness", "scores": { "value": 3, "reason": "要点不全" } },
                    { "dimension": "experience_match", "scores": { "value": 5, "reason": "有事实依据" } },
                    { "dimension": "structure", "scores": { "value": 4, "reason": "顺序合理" } }
                  ]
                }
                """;
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "回答内容足够长以通过校验。", "", List.of());

        assertThat(evaluation.degraded()).isFalse();
        assertThat(evaluation.totalScore()).isEqualTo(4.0);
        assertThat(evaluation.dimensionScores().get("technical_correctness").reason()).isEqualTo("推理正确");
    }

    @Test
    @DisplayName("中文维度名与简写也能映射到标准键")
    void mapsChineseDimensionNames() {
        String json = """
                {
                  "dimensionScores": {
                    "技术正确性": { "score": 4, "reason": "概念正确" },
                    "completeness": 3,
                    "experience_match": { "score": 5, "reason": "一致" },
                    "表达结构": { "score": 4, "reason": "清晰" }
                  }
                }
                """;
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "回答内容足够长以通过校验。", "", List.of());

        assertThat(evaluation.degraded()).isFalse();
        assertThat(evaluation.dimensionScores()).hasSize(4);
        assertThat(evaluation.dimensionScores().get("completeness").score()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("百分制分数会被折算到 0-5")
    void normalisesPercentScale() {
        String json = """
                {
                  "dimensionScores": {
                    "technical_correctness": { "score": 80, "reason": "r" },
                    "completeness": { "score": 60, "reason": "r" },
                    "experience_match": { "score": 100, "reason": "r" },
                    "structure": { "score": 20, "reason": "r" }
                  }
                }
                """;
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "回答内容足够长以通过校验。", "", List.of());
        assertThat(evaluation.totalScore()).isEqualTo(3.25);
    }

    @Test
    @DisplayName("一个维度都解析不出时必须降级，绝不能静默记 0 分")
    void missingAllDimensionsFallsBackInsteadOfZero() {
        // 模型返回了合法 JSON 但维度名完全不符
        String json = "{ \"dimensionScores\": { \"foo\": { \"score\": 4 } }, \"summary\": \"ok\" }";
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "这是一段正常长度的回答内容。", "", List.of());

        assertThat(evaluation.degraded())
                .as("解析不出维度时必须走降级，而不是给出四个 0 分")
                .isTrue();
        // 降级结果仍然要有完整四维与说明
        assertThat(evaluation.dimensionScores()).hasSize(4);
        assertThat(evaluation.summary()).contains("降级评估");
    }

    @Test
    @DisplayName("部分维度缺失时保留可用维度，并在纠错中说明缺了哪些")
    void partialDimensionsAreReported() {
        String json = """
                {
                  "dimensionScores": {
                    "technical_correctness": { "score": 4, "reason": "机制正确" },
                    "completeness": { "score": 3, "reason": "要点不全" }
                  },
                  "summary": "总体可用"
                }
                """;
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "回答内容足够长以通过校验。", "", List.of());

        assertThat(evaluation.degraded()).isFalse();
        assertThat(evaluation.dimensionScores()).hasSize(2);
        assertThat(evaluation.totalScore()).isEqualTo(3.5);
        assertThat(evaluation.corrections())
                .anySatisfy(correction -> assertThat(correction)
                        .contains("未返回以下维度的评分")
                        .contains("经历匹配度")
                        .contains("表达结构"));
    }

    @Test
    @DisplayName("非法的结构化输出触发降级而不是抛出到调用方")
    void malformedOutputDegradesGracefully() {
        AnswerEvaluation evaluation = evaluatorReturning(request -> "这不是 JSON")
                .evaluate(question(), "回答内容足够长以通过校验。", "", List.of());

        assertThat(evaluation.degraded()).isTrue();
        assertThat(evaluation.rawModelOutput()).isNotNull();
        assertThat(evaluation.dimensionScores()).hasSize(4);
    }

    @Test
    @DisplayName("打分依据的前导冒号被清理掉")
    void cleansReasonPrefix() {
        String json = """
                {
                  "dimensionScores": {
                    "technical_correctness": { "score": 4, "reason": ": 机制描述正确" },
                    "completeness": { "score": 3, "reason": "—— 缺少结果" },
                    "experience_match": { "score": 5, "reason": "" },
                    "structure": { "score": 4, "reason": "顺序合理" }
                  }
                }
                """;
        AnswerEvaluation evaluation = evaluatorReturning(request -> json)
                .evaluate(question(), "回答内容足够长以通过校验。", "", List.of());

        assertThat(evaluation.dimensionScores().get("technical_correctness").reason())
                .isEqualTo("机制描述正确");
        assertThat(evaluation.dimensionScores().get("completeness").reason()).isEqualTo("缺少结果");
        assertThat(evaluation.dimensionScores().get("experience_match").reason())
                .isEqualTo("模型未给出打分依据");
    }

    @Test
    @DisplayName("空回答不调用模型，直接给出降级结果与引导")
    void emptyAnswerShortCircuits() {
        LlmClient neverCalled = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                throw new AssertionError("空回答不应调用模型");
            }

            @Override
            public String modelName() {
                return "unused";
            }

            @Override
            public String provider() {
                return "unused";
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        AnswerEvaluation evaluation = new AnswerEvaluator(neverCalled, new StructuredOutputParser(MAPPER),
                PROPERTIES, Clock.systemUTC()).evaluate(question(), "   ", "", List.of());

        assertThat(evaluation.degraded()).isTrue();
        assertThat(evaluation.totalScore()).isZero();
        assertThat(evaluation.summary()).contains("没有收到有效回答");
    }

    @Test
    @DisplayName("模型超时同样降级并保留原因")
    void timeoutDegrades() {
        LlmClient timeoutClient = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                throw LlmException.timeout("读取超时", null);
            }

            @Override
            public String modelName() {
                return "timeout";
            }

            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        AnswerEvaluation evaluation = new AnswerEvaluator(timeoutClient, new StructuredOutputParser(MAPPER),
                PROPERTIES, Clock.systemUTC()).evaluate(question(), "回答内容足够长以通过校验。", "", List.of());

        assertThat(evaluation.degraded()).isTrue();
        assertThat(evaluation.rawModelOutput()).contains("超时");
    }
}
