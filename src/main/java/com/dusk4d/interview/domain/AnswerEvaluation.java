package com.dusk4d.interview.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 答案评估结果（对应方案书 6.5）。
 *
 * <p>不只是总分：同时返回分项分数与依据、可保留的内容、遗漏点、错误点、建议补充的信息、
 * 参考回答结构以及是否建议追问。
 *
 * @param totalScore      加权总分（0~5，保留两位小数）
 * @param degraded        是否降级（模型结构化输出失败，使用启发式评估）
 * @param rawModelOutput  降级时保留的原始模型输出片段（截断），便于排查
 * @param evidenceCheck   事实一致性校验结论（是否存在没有证据的强主张）
 */
public record AnswerEvaluation(
        String id,
        String answerId,
        String questionId,
        String sessionId,
        double totalScore,
        Map<String, DimensionScore> dimensionScores,
        List<String> strengths,
        List<String> missingPoints,
        List<String> corrections,
        List<String> suggestedAdditions,
        String referenceAnswerStructure,
        List<String> evidenceWarnings,
        boolean followUpRecommended,
        String followUpFocus,
        String summary,
        boolean degraded,
        String rawModelOutput,
        Instant createdAt
) {
    public AnswerEvaluation {
        dimensionScores = dimensionScores == null ? Map.of() : Map.copyOf(dimensionScores);
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        missingPoints = missingPoints == null ? List.of() : List.copyOf(missingPoints);
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
        suggestedAdditions = suggestedAdditions == null ? List.of() : List.copyOf(suggestedAdditions);
        evidenceWarnings = evidenceWarnings == null ? List.of() : List.copyOf(evidenceWarnings);
    }
}
