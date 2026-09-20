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
 * @param referenceAnswer 可复述的示范表达（非标准答案；没有证据的量化指标不得补写）
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
        /**
         * 参考回答：按 {@code referenceAnswerStructure} 组织、可照着复述的<b>示范表达</b>。
         *
         * <p>它不是标准答案（项目面没有唯一正确答案），只能使用候选人已回答的内容与简历事实；
         * 缺少量化结果时写成占位符，而不是编一个数字——生成内容若引入了未提供的指标会被丢弃
         * （见 {@code AnswerEvaluator}）。降级评估时为空。
         */
        String referenceAnswer,
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
