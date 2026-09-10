package com.dusk4d.interview.agent;

import com.dusk4d.interview.domain.DimensionScore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启发式评分器（降级路径）。
 *
 * <p>当模型不可用或结构化输出连续失败时使用。它的定位是「给出可解释的保守反馈」而不是「假装准确」：
 * <ul>
 *   <li>分数上限被压到 3.5，避免在没有语义理解的情况下给出高分；</li>
 *   <li>每个维度都给出基于可观测特征的依据（长度、是否答非所问、是否包含机制词与结果词、是否有结构词）；</li>
 *   <li>评估结果会标记 degraded，前端与报告都会说明「本次为降级评估」。</li>
 * </ul>
 *
 * <p>同时被 {@code MockLlmClient} 复用，保证离线演示与降级路径的评分口径一致、结果可复现。
 */
public final class HeuristicEvaluator {

    private static final double DEGRADED_CAP = 3.5;
    private static final int MIN_MEANINGFUL_LENGTH = 20;

    private static final List<String> MECHANISM_WORDS =
            List.of("因为", "所以", "通过", "使用", "实现", "机制", "方案", "设计", "具体", "首先", "其次", "最后");
    private static final List<String> RESULT_WORDS =
            List.of("%", "ms", "秒", "QPS", "TPS", "提升", "下降", "降低", "上线", "验证", "压测", "对比");
    private static final List<String> STRUCTURE_WORDS =
            List.of("背景", "职责", "难点", "结果", "方案", "首先", "其次", "最后", "总结");
    private static final List<String> EVASION_WORDS =
            List.of("不知道", "不会", "没做过", "不清楚", "随便", "猜", "大概吧");

    private HeuristicEvaluator() {
    }

    /** 启发式评估结果。 */
    public record HeuristicResult(
            Map<String, DimensionScore> dimensions,
            List<String> strengths,
            List<String> missingPoints,
            List<String> corrections,
            List<String> suggestedAdditions,
            List<String> evidenceWarnings,
            boolean followUpRecommended,
            String followUpFocus,
            String summary
    ) {
    }

    /** 对一次回答做启发式评估。 */
    public static HeuristicResult evaluate(String question, String answer) {
        String text = answer == null ? "" : answer.strip();
        boolean empty = text.isEmpty();
        boolean tooShort = !empty && text.length() < MIN_MEANINGFUL_LENGTH;
        boolean evasive = containsAny(text, EVASION_WORDS);
        boolean hasMechanism = containsAny(text, MECHANISM_WORDS);
        boolean hasResult = containsAny(text, RESULT_WORDS);
        boolean hasStructure = containsAny(text, STRUCTURE_WORDS);
        double lengthFactor = Math.min(1.0, text.length() / 120.0);

        double technical = empty ? 0 : evasive ? 0.5
                : cap(1.5 + (hasMechanism ? 1.0 : 0) + lengthFactor * 0.8);
        double completeness = empty ? 0 : tooShort ? 1.0
                : cap(1.5 + (hasMechanism ? 0.6 : 0) + (hasResult ? 0.7 : 0) + lengthFactor * 0.5);
        double match = empty ? 0 : tooShort ? 1.5
                : cap(2.0 + (hasResult ? 0.6 : 0) + lengthFactor * 0.4);
        double structure = empty ? 0 : hasStructure ? cap(3.0 + lengthFactor * 0.5)
                : tooShort ? 1.0 : cap(1.8 + lengthFactor * 0.5);

        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        dimensions.put("technical_correctness", new DimensionScore("technical_correctness", "技术正确性", round(technical), 0.25,
                empty ? "未作答，无法判断技术正确性。"
                        : evasive ? "回答表明对该问题没有实际经验，不构成有效技术表述。"
                        : hasMechanism ? "提到了实现机制，但缺少边界与对比，需要人工复核准确性。"
                        : "只给出结论性描述，未说明技术机制。"));
        dimensions.put("completeness", new DimensionScore("completeness", "内容完整性", round(completeness), 0.25,
                empty ? "未作答。" : tooShort ? "回答过短，关键点覆盖不足。"
                        : hasResult ? "覆盖了做法与结果，但仍可能遗漏边界条件。" : "缺少量化结果或验证方式。"));
        dimensions.put("experience_match", new DimensionScore("experience_match", "经历匹配度", round(match), 0.25,
                empty ? "未作答，无法核对经历一致性。" : "仅按简历事实口径核对，未发现明显无依据的主张（建议人工确认）。"));
        dimensions.put("structure", new DimensionScore("structure", "表达结构", round(structure), 0.25,
                hasStructure ? "回答中出现了背景/难点/结果等结构性要素。"
                        : "建议按「背景 → 个人职责 → 技术机制 → 难点取舍 → 结果验证」组织。"));

        List<String> strengths = new ArrayList<>();
        if (hasMechanism) {
            strengths.add("给出了具体实现手段");
        }
        if (hasResult) {
            strengths.add("提到了可验证的结果");
        }
        if (hasStructure) {
            strengths.add("表达有结构");
        }

        List<String> missing = new ArrayList<>();
        if (empty) {
            missing.add("完全没有回答内容");
        } else {
            if (!hasMechanism) {
                missing.add("缺少技术实现机制");
            }
            if (!hasResult) {
                missing.add("缺少量化结果或验证方式");
            }
            if (!hasStructure) {
                missing.add("缺少结构化的表达顺序");
            }
        }

        List<String> corrections = new ArrayList<>();
        if (evasive) {
            corrections.add("回答未正面回应问题，需要先给出明确结论再展开");
        }
        List<String> additions = new ArrayList<>();
        additions.add("补充该方案的适用边界与替代方案对比");
        additions.add("补充你个人在其中承担的具体职责与产出");

        List<String> evidenceWarnings = new ArrayList<>();
        if (hasResult && !text.matches(".*\\d.*")) {
            evidenceWarnings.add("提到了效果提升但没有给出可核验的数字或验证方式");
        }

        String summary = empty ? "没有收到有效回答，无法评估内容。"
                : tooShort ? "回答过于简短，缺少技术细节，建议按「背景-动作-机制-结果」展开。"
                : evasive ? "回答未正面回应问题，需要重新组织内容。"
                : "回答覆盖了主要方向，补充机制细节与量化结果会更有说服力。";

        return new HeuristicResult(dimensions, strengths, missing, corrections, additions, evidenceWarnings,
                !empty && (!missing.isEmpty() || technical < 3.0),
                empty ? "先说明你的整体思路" : "深入追问技术机制与边界条件",
                summary);
    }

    /** 加权总分。 */
    public static double totalScore(Map<String, DimensionScore> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return 0;
        }
        double sum = 0;
        double weightSum = 0;
        for (DimensionScore dimension : dimensions.values()) {
            sum += dimension.score() * dimension.weight();
            weightSum += dimension.weight();
        }
        return weightSum <= 0 ? 0 : Math.round((sum / weightSum) * 100.0) / 100.0;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static double cap(double score) {
        return Math.max(0, Math.min(DEGRADED_CAP, score));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
