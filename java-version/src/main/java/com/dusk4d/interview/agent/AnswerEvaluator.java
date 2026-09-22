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
    /** 评分采样次数（>1 时取中位数，用于压制小模型的打分波动）。 */
    private final int evalSamples;

    /**
     * 量化指标识别：带量词或百分号的数字。
     *
     * <p>刻意不匹配裸数字，避免「第一步」「两个模块」被当成指标而误杀参考回答。
     */
    private static final java.util.regex.Pattern METRIC_PATTERN = java.util.regex.Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:%|％|倍|万|亿|ms|毫秒|秒|分钟|小时|QPS|TPS|qps|tps|人日|天)");

    public AnswerEvaluator(LlmClient llmClient, StructuredOutputParser parser,
                           AppProperties properties, Clock clock) {
        this.llmClient = llmClient;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
        AppProperties.Llm llm = properties.llm();
        // properties 允许整块缺失（例如手工构造用于单测），这里给出安全默认值而不是抛 NPE。
        // 评分默认使用 0.0 温度（贪心解码）以获得可复现的分数，出题仍用常规温度。
        this.defaultTemperature = llm == null ? 0.0 : llm.resolvedEvalTemperature();
        this.defaultMaxTokens = llm == null ? 1600 : llm.maxTokens();
        this.evalSamples = llm == null ? 1 : llm.resolvedEvalSamples();
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
        int samples = evalSamples;
        List<Map<String, Object>> successfulRuns = new ArrayList<>();
        List<String> rawOutputs = new ArrayList<>();
        LlmException lastFailure = null;
        for (int attempt = 0; attempt < samples; attempt++) {
            try {
                var response = llmClient.chat(LlmRequest.structured(Prompts.EVALUATION_SYSTEM, userPrompt,
                        "evaluation", SCHEMA_FIELDS, defaultTemperature, defaultMaxTokens));
                successfulRuns.add(parser.parseObject(response.content(), REQUIRED_FIELDS));
                rawOutputs.add(response.content());
            } catch (LlmException e) {
                lastFailure = e;
                log.warn("评分第 {}/{} 次采样失败（{}）：{}", attempt + 1, samples, e.kind(), e.getMessage());
            }
        }
        if (successfulRuns.isEmpty()) {
            log.warn("评分失败（{}），降级为启发式评估：{}", lastFailure == null ? "UNKNOWN" : lastFailure.kind(),
                    lastFailure == null ? "未知原因" : lastFailure.getMessage());
            return degraded(question, answerId, answer,
                    lastFailure == null ? "模型调用失败" : lastFailure.getMessage(), null, now);
        }
        Map<String, Object> merged = successfulRuns.size() == 1
                ? successfulRuns.get(0)
                : medianOf(successfulRuns);
        try {
            AnswerEvaluation evaluation = fromModel(question, answerId, merged,
                    rawOutputs.isEmpty() ? null : rawOutputs.get(0), answer, context, facts, now);
            if (successfulRuns.size() > 1) {
                evaluation = withMultiSampleNote(evaluation, successfulRuns.size());
            }
            return evaluation;
        } catch (LlmException e) {
            // 采样调用成功、但结果里没有任何可解析的维度（例如字段名完全不符）。
            // 这里必须走降级而不是把异常抛给调用方——否则一次模型跑偏会让整个提交回答接口失败。
            log.warn("评分结果无可解析维度（{}），降级为启发式评估：{}", e.kind(), e.getMessage());
            return degraded(question, answerId, answer, e.getMessage(), null, now);
        }
    }

    /**
     * 多次采样的中位数合并。
     *
     * <p>对离散打分（0-5 整数为主）取中位数能有效压掉离群值：实测小模型对「答非所问」的回答
     * 会偶尔给出 0.0 与 1.25 两种结果，单次评分不可复现。
     * 文本类字段（依据、遗漏点等）取中位数那次采样的结果，保证分数与解释来自同一次判断。
     */
    private Map<String, Object> medianOf(List<Map<String, Object>> runs) {
        List<Map<String, Object>> withDimensions = runs.stream()
                .filter(run -> !parseDimensions(run).isEmpty())
                .toList();
        if (withDimensions.isEmpty()) {
            return runs.get(0);
        }
        Map<String, Double> medians = new LinkedHashMap<>();
        for (String key : Prompts.DIMENSION_KEYS) {
            List<Double> values = new ArrayList<>();
            for (Map<String, Object> run : withDimensions) {
                Map<String, DimensionScore> dimensions = parseDimensions(run);
                if (dimensions.containsKey(key)) {
                    values.add(dimensions.get(key).score());
                }
            }
            if (!values.isEmpty()) {
                java.util.Collections.sort(values);
                medians.put(key, values.get(values.size() / 2));
            }
        }
        // 选择「与各维度中位数距离最小」的那次采样作为文本来源，保证分数与解释一致
        Map<String, Object> best = withDimensions.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (Map<String, Object> run : withDimensions) {
            Map<String, DimensionScore> dimensions = parseDimensions(run);
            double distance = 0;
            for (Map.Entry<String, Double> entry : medians.entrySet()) {
                DimensionScore dimension = dimensions.get(entry.getKey());
                if (dimension != null) {
                    distance += Math.abs(dimension.score() - entry.getValue());
                }
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = run;
            }
        }
        // 用中位数覆盖该次采样的分数（文本解释沿用 best）
        Map<String, Object> result = new LinkedHashMap<>(best);
        Map<String, Object> rawDimensions = new LinkedHashMap<>();
        Map<String, DimensionScore> bestDimensions = parseDimensions(best);
        for (String key : Prompts.DIMENSION_KEYS) {
            DimensionScore dimension = bestDimensions.get(key);
            double score = medians.getOrDefault(key, dimension == null ? 0.0 : dimension.score());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("score", score);
            entry.put("reason", dimension == null ? "模型未给出打分依据" : dimension.reason());
            rawDimensions.put(key, entry);
        }
        result.put("dimensionScores", rawDimensions);
        return result;
    }

    /** 在多采样结果上标注采样次数，便于用户与排查者知道这不是单次判定。 */
    private AnswerEvaluation withMultiSampleNote(AnswerEvaluation evaluation, int samples) {
        String note = "（本分数为 " + samples + " 次评分的中位数，用于降低模型打分波动）";
        return new AnswerEvaluation(evaluation.id(), evaluation.answerId(), evaluation.questionId(),
                evaluation.sessionId(), evaluation.totalScore(), evaluation.dimensionScores(),
                evaluation.strengths(), evaluation.missingPoints(), evaluation.corrections(),
                evaluation.suggestedAdditions(), evaluation.referenceAnswerStructure(),
                evaluation.referenceAnswer(),
                evaluation.evidenceWarnings(), evaluation.followUpRecommended(), evaluation.followUpFocus(),
                evaluation.summary() + note, evaluation.degraded(), evaluation.rawModelOutput(),
                evaluation.createdAt());
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
                                       String rawOutput, String answer, String context, List<ResumeFact> facts,
                                       Instant now) {
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
        ReferenceAnswer reference = referenceAnswer(
                StructuredOutputParser.string(parsed, "referenceAnswer", ""), answer, context, facts);
        corrections = new ArrayList<>(corrections);
        if (reference.droppedNote() != null) {
            corrections.add(reference.droppedNote());
        }
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
                reference.text(),
                evidenceWarnings,
                StructuredOutputParser.bool(parsed, "followUpRecommended", false),
                StructuredOutputParser.string(parsed, "followUpFocus", ""),
                StructuredOutputParser.string(parsed, "summary",
                        StructuredOutputParser.string(parsed, "overallComment", "已完成评估。")),
                false,
                null,
                now);
    }

    /** 参考回答及其被丢弃的原因。 */
    private record ReferenceAnswer(String text, String droppedNote) {
    }

    /**
     * 校验模型生成的参考回答：出现「用户没说过的量化指标」就整段丢弃。
     *
     * <p>为什么要在代码里再挡一道：提示词已经写明「不得补写没有证据的指标」，但小模型实测中
     * 仍会顺手编出「QPS 提升 3 倍」这类数字。参考回答是给用户照着复述的，一旦混入编造的指标，
     * 用户很可能直接背下来去面试——这是本项目最不能接受的失败方式，所以宁可不给参考回答。
     *
     * <p>判定方式取「保守但确定」的策略：参考回答里出现的数字（含 %、ms、QPS、倍、万 等量词）
     * 必须在用户回答或给定事实片段里原样出现过，否则丢弃。
     */
    private ReferenceAnswer referenceAnswer(String text, String answer, String context, List<ResumeFact> facts) {
        if (text == null || text.isBlank()) {
            return new ReferenceAnswer("", null);
        }
        String trimmed = text.strip();
        String allowed = (answer == null ? "" : answer) + "\n" + (context == null ? "" : context) + "\n"
                + (facts == null ? "" : facts.stream()
                        .map(f -> f.label() + " " + f.content()).collect(java.util.stream.Collectors.joining("\n")));
        if (!hasUnsupportedMetric(trimmed, allowed)) {
            return new ReferenceAnswer(trimmed, null);
        }
        return new ReferenceAnswer("",
                "模型生成的参考回答引入了原回答与简历中都没有的量化指标，为避免编造已丢弃该段参考回答，"
                        + "请按 referenceAnswerStructure 自行组织，只使用你真实做过的数据。");
    }

    /**
     * 参考回答里是否出现了「来源里没有的量化指标」。
     *
     * <p>只检查带量词或百分号的数字（3 倍、60%、200ms、1 万），不检查纯数字——
     * 否则「第一步」「两个模块」这类表述会造成大量误杀。
     */
    private boolean hasUnsupportedMetric(String referenceAnswer, String allowedSource) {
        java.util.regex.Matcher matcher = METRIC_PATTERN.matcher(referenceAnswer);
        while (matcher.find()) {
            String metric = matcher.group();
            String digits = metric.replaceAll("[^0-9.]", "");
            if (digits.isEmpty()) {
                continue;
            }
            // 数字本身在来源里出现过即可（容忍写法差异：60% 与 60 视为同一数据）
            if (!allowedSource.contains(digits)) {
                return true;
            }
        }
        return false;
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
                // 降级路径没有语义理解能力，硬拼一段「示范表达」只会看起来像真的——留空更诚实。
                "",
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
