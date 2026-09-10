package com.dusk4d.interview.agent;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.DimensionScore;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.error.LlmException;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 答案评估器（对应方案书 6.5）。
 *
 * <p>评分口径：四个维度各 0-5 分、等权 0.25，输出分项依据、遗漏点、错误点、建议补充、
 * 参考回答结构、事实证据告警与是否建议追问——而不是只给一个总分。
 *
 * <p>失败策略：解析失败时先用解析器的修复能力；仍失败则降级为 {@link HeuristicEvaluator}
 * 并把原始输出保留在 {@code rawModelOutput} 中，绝不把缺失字段当作 0 分静默展示。
 */
@Component
public class AnswerEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluator.class);

    private static final List<String> SCHEMA_FIELDS = List.of("dimensionScores", "summary");
    private static final double DIMENSION_WEIGHT = 0.25;

    private final LlmClient llmClient;
    private final StructuredOutputParser parser;
    private final AppProperties properties;
    private final Clock clock;

    public AnswerEvaluator(LlmClient llmClient, StructuredOutputParser parser,
                           AppProperties properties, Clock clock) {
        this.llmClient = llmClient;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 评估一次回答。
     *
     * @param question  当前问题
     * @param answer    用户回答（已去空白）
     * @param context   检索到的事实片段
     * @param facts     简历事实（用于经历匹配度）
     */
    public AnswerEvaluation evaluate(InterviewQuestion question, String answer, String context,
                                     List<ResumeFact> facts) {
        Instant now = clock.instant();
        String answerId = UUID.randomUUID().toString();

        if (answer == null || answer.isBlank()) {
            return degraded(question, answerId, "", "用户没有提交任何回答内容。",
                    "（空回答不调用模型：空内容无法评估，也不应消耗额度）", now);
        }

        String userPrompt = Prompts.evaluationUser(question, answer, context, facts);
        try {
            var response = llmClient.chat(LlmRequest.structured(Prompts.EVALUATION_SYSTEM, userPrompt,
                    "evaluation", SCHEMA_FIELDS, properties.llm().temperature(), properties.llm().maxTokens()));
            Map<String, Object> parsed = parser.parseObject(response.content(), SCHEMA_FIELDS);
            return fromModel(question, answerId, parsed, response.content(), now);
        } catch (LlmException e) {
            log.warn("评分失败（{}），降级为启发式评估：{}", e.kind(), e.getMessage());
            return degraded(question, answerId, answer, e.getMessage(), null, now);
        }
    }

    // ---------------------------------------------------------------- 模型结果转换

    private AnswerEvaluation fromModel(InterviewQuestion question, String answerId, Map<String, Object> parsed,
                                       String rawOutput, Instant now) {
        Map<String, Object> rawDimensions = StructuredOutputParser.map(parsed, "dimensionScores");
        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        for (String key : Prompts.DIMENSION_KEYS) {
            Map<String, Object> entry = StructuredOutputParser.map(rawDimensions, key);
            double score = StructuredOutputParser.number(entry, "score", 0, 0, 5);
            String label = StructuredOutputParser.string(entry, "label", labelOf(key));
            String reason = StructuredOutputParser.string(entry, "reason", "模型未给出打分依据");
            dimensions.put(key, new DimensionScore(key, label, round(score), DIMENSION_WEIGHT, reason));
        }
        List<String> corrections = StructuredOutputParser.stringList(parsed, "corrections");
        List<String> evidenceWarnings = StructuredOutputParser.stringList(parsed, "evidenceWarnings");
        String structure = StructuredOutputParser.string(parsed, "referenceAnswerStructure",
                "背景 → 个人职责 → 技术机制 → 难点取舍 → 结果验证");
        return new AnswerEvaluation(
                UUID.randomUUID().toString(),
                answerId,
                question.id(),
                question.sessionId(),
                HeuristicEvaluator.totalScore(dimensions),
                dimensions,
                StructuredOutputParser.stringList(parsed, "strengths"),
                StructuredOutputParser.stringList(parsed, "missingPoints"),
                corrections,
                StructuredOutputParser.stringList(parsed, "suggestedAdditions"),
                structure,
                evidenceWarnings,
                StructuredOutputParser.bool(parsed, "followUpRecommended", false),
                StructuredOutputParser.string(parsed, "followUpFocus", ""),
                StructuredOutputParser.string(parsed, "summary",
                        StructuredOutputParser.string(parsed, "overallComment", "已完成评估。")),
                false,
                null,
                now);
    }

    // ---------------------------------------------------------------- 降级路径

    private AnswerEvaluation degraded(InterviewQuestion question, String answerId, String answer,
                                      String reason, String note, Instant now) {
        HeuristicEvaluator.HeuristicResult heuristic = HeuristicEvaluator.evaluate(question.content(), answer);
        List<String> corrections = new ArrayList<>(heuristic.corrections());
        if (note != null) {
            corrections.add(note);
        }
        return new AnswerEvaluation(
                UUID.randomUUID().toString(),
                answerId,
                question.id(),
                question.sessionId(),
                HeuristicEvaluator.totalScore(heuristic.dimensions()),
                heuristic.dimensions(),
                heuristic.strengths(),
                heuristic.missingPoints(),
                corrections,
                heuristic.suggestedAdditions(),
                "背景 → 个人职责 → 技术机制 → 难点取舍 → 结果验证",
                heuristic.evidenceWarnings(),
                heuristic.followUpRecommended(),
                heuristic.followUpFocus(),
                heuristic.summary() + "（本次为降级评估：" + reason + "）",
                true,
                truncate(reason, 300),
                now);
    }

    // ---------------------------------------------------------------- 工具

    private String labelOf(String key) {
        return switch (key) {
            case "technical_correctness" -> "技术正确性";
            case "completeness" -> "内容完整性";
            case "experience_match" -> "经历匹配度";
            case "structure" -> "表达结构";
            default -> key;
        };
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
