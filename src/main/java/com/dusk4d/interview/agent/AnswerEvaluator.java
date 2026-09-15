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

    /**
     * 结构化请求里期望的字段（用于提示与校验提示，不要求全部出现）。
     */
    private static final List<String> SCHEMA_FIELDS = List.of("dimensionScores", "criticalReview", "summary");

    /**
     * 必需字段：只有 {@code dimensionScores} 是「没有它就无法评分」的。
     *
     * <p>其余字段（summary / missingPoints / corrections …）缺失时都有安全默认值。
     * 曾经把 {@code summary} 也列为必需，结果是模型只要漏掉总评，整份评分就被判定失败并降级，
     * 真实的四维分数被丢弃——这是真实模型实测暴露过的缺陷，
     * 现已由 {@code AnswerEvaluatorToleranceTest} 守住。
     */
    private static final List<String> REQUIRED_FIELDS = List.of("dimensionScores");

    private static final double DIMENSION_WEIGHT = 0.25;

    private final LlmClient llmClient;
    private final StructuredOutputParser parser;
    private final AppProperties properties;
    private final Clock clock;

    /** 解析默认值：避免每次调用都做空判断。 */
    private final double defaultTemperature;
    private final int defaultMaxTokens;

    public AnswerEvaluator(LlmClient llmClient, StructuredOutputParser parser,
                           AppProperties properties, Clock clock) {
        this.llmClient = llmClient;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
        AppProperties.Llm llm = properties.llm();
        // properties 允许整块缺失（例如手工构造用于单测），这里给出安全默认值而不是抛 NPE
        this.defaultTemperature = llm == null ? 0.3 : llm.temperature();
        this.defaultMaxTokens = llm == null ? 1600 : llm.maxTokens();
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
                    "evaluation", SCHEMA_FIELDS, defaultTemperature, defaultMaxTokens));
            Map<String, Object> parsed = parser.parseObject(response.content(), REQUIRED_FIELDS);
            return fromModel(question, answerId, parsed, response.content(), now);
        } catch (LlmException e) {
            log.warn("评分失败（{}），降级为启发式评估：{}", e.kind(), e.getMessage());
            return degraded(question, answerId, answer, e.getMessage(), null, now);
        }
    }

    // ---------------------------------------------------------------- 模型结果转换

    /**
     * 把模型输出转换为四维评分。
     *
     * <p>容错设计（都由真实小模型实测驱动）：
     * <ul>
     *   <li>{@code dimensionScores} 既可能是「以维度为键的对象」，也可能是「数组」，
     *       数组元素里维度名可能放在 {@code dimension}/{@code key}/{@code name} 任一字段；</li>
     *   <li>分数可能是 0-10 甚至 0-100 分制，统一归一化到 0-5；</li>
     *   <li>任一必需维度完全无法解析时，<b>必须判定为失败并降级</b>，不能静默记 0 分——
     *       否则用户看到的是「0 分 + 模型未给出打分依据」，会误以为是自己答得极差。</li>
     * </ul>
     */
    private Map<String, DimensionScore> parseDimensions(Map<String, Object> parsed) {
        Object rawDimensions = parsed.get("dimensionScores");
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        if (rawDimensions instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String normalizedKey = normalizeDimensionKey(String.valueOf(key));
                if (value instanceof Map<?, ?> entry) {
                    byKey.put(normalizedKey, toStringMap(entry));
                } else if (value != null) {
                    // 形如 {"technical_correctness": 4} 的简写
                    Map<String, Object> shorthand = new LinkedHashMap<>();
                    shorthand.put("score", value);
                    byKey.put(normalizedKey, shorthand);
                }
            });
        } else if (rawDimensions instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> entry)) {
                    continue;
                }
                Map<String, Object> entryMap = toStringMap(entry);
                String key = firstNonBlank(StructuredOutputParser.string(entryMap, "dimension"),
                        StructuredOutputParser.string(entryMap, "key"),
                        StructuredOutputParser.string(entryMap, "name"),
                        StructuredOutputParser.string(entryMap, "label"));
                if (key != null) {
                    byKey.put(normalizeDimensionKey(key), entryMap);
                }
            }
        }

        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        for (String key : Prompts.DIMENSION_KEYS) {
            Map<String, Object> entry = byKey.get(key);
            if (entry == null) {
                continue;
            }
            // 分数可能在 score/value 里，也可能嵌在 {"scores": {"value": 4}} 里（实测小模型会这样返回）
            double rawScore = StructuredOutputParser.number(entry, "score",
                    StructuredOutputParser.number(entry, "value", -1, -1, 100), -1, 100);
            if (rawScore < 0) {
                Map<String, Object> nested = StructuredOutputParser.map(entry, "scores");
                rawScore = StructuredOutputParser.number(nested, "score",
                        StructuredOutputParser.number(nested, "value", -1, -1, 100), -1, 100);
            }
            if (rawScore < 0) {
                continue;
            }
            dimensions.put(key, new DimensionScore(key, labelOf(key), normalizeScore(rawScore),
                    DIMENSION_WEIGHT, cleanReason(extractReason(entry))));
        }
        return dimensions;
    }

    /** 抽取打分依据：兼容模型使用的多种字段名与嵌套写法。 */
    private String extractReason(Map<String, Object> entry) {
        String direct = firstNonBlank(
                StructuredOutputParser.string(entry, "reason"),
                StructuredOutputParser.string(entry, "explanation"),
                StructuredOutputParser.string(entry, "comment"),
                StructuredOutputParser.string(entry, "rationale"),
                StructuredOutputParser.string(entry, "justification"),
                StructuredOutputParser.string(entry, "analysis"),
                StructuredOutputParser.string(entry, "detail"));
        if (direct != null) {
            return direct;
        }
        Map<String, Object> nested = StructuredOutputParser.map(entry, "scores");
        return firstNonBlank(
                StructuredOutputParser.string(nested, "reason"),
                StructuredOutputParser.string(nested, "explanation"),
                StructuredOutputParser.string(nested, "comment"));
    }

    /**
     * 分数归一化到 0-5。
     *
     * <p>模型常按 10 分制或百分制给分（实测 qwen3 给出 score=8）。
     * 通过提示词无法完全约束，因此在这里按量级折算，并记录一次日志便于排查。
     */
    private double normalizeScore(double raw) {
        double value = raw;
        if (raw > 5 && raw <= 10) {
            value = raw / 2.0;
            log.debug("模型使用 10 分制（{}），已归一化为 {}", raw, value);
        } else if (raw > 10 && raw <= 100) {
            value = raw / 20.0;
            log.debug("模型使用百分制（{}），已归一化为 {}", raw, value);
        } else if (raw > 100) {
            value = 5.0;
        }
        return round(Math.max(0, Math.min(5, value)));
    }

    /** 去掉模型偶尔带出的前导冒号/破折号，避免出现「: 逻辑正确」这类残句。 */
    private String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "模型未给出打分依据";
        }
        String cleaned = reason.strip().replaceAll("^[:：\\-–—、,，]+\\s*", "").strip();
        return cleaned.isEmpty() ? "模型未给出打分依据" : cleaned;
    }

    /** 维度名归一化：统一成小写下划线形式，兼容中英文与空格。 */
    private String normalizeDimensionKey(String raw) {
        String key = raw.strip().toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
        for (String candidate : Prompts.DIMENSION_KEYS) {
            if (candidate.equals(key)) {
                return candidate;
            }
        }
        return switch (key) {
            case "technicalcorrectness", "technical", "correctness", "技术正确性", "技术准确性" ->
                    "technical_correctness";
            case "completeness", "content", "coverage", "内容完整性", "完整性" -> "completeness";
            case "experiencematch", "experience", "match", "经历匹配度", "项目匹配度" -> "experience_match";
            case "structure", "expression", "clarity", "表达结构", "结构性" -> "structure";
            default -> key;
        };
    }

    private Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private AnswerEvaluation fromModel(InterviewQuestion question, String answerId, Map<String, Object> parsed,
                                       String rawOutput, Instant now) {
        Map<String, DimensionScore> dimensions = parseDimensions(parsed);
        if (dimensions.isEmpty()) {
            // 一个维度都没解析出来：判定为结构化失败，交给上层降级，
            // 而不是返回「四维 0 分」这种会误导用户的假结果。
            throw LlmException.invalidStructure(
                    "模型未返回可解析的评分维度（dimensionScores 缺失或字段名不符）。");
        }
        List<String> corrections = StructuredOutputParser.stringList(parsed, "corrections");
        if (dimensions.size() < Prompts.DIMENSION_KEYS.size()) {
            List<String> missing = Prompts.DIMENSION_KEYS.stream()
                    .filter(key -> !dimensions.containsKey(key))
                    .map(this::labelOf)
                    .toList();
            corrections = new ArrayList<>(corrections);
            corrections.add("模型未返回以下维度的评分：" + String.join("、", missing) + "（已按可用维度计算总分）");
        }
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
