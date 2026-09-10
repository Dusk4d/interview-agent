package com.dusk4d.interview.domain;

/**
 * 单个评分维度的结果。
 *
 * @param key    维度标识（technical_correctness / completeness / experience_match / structure）
 * @param label  维度中文名
 * @param score  0~5 分
 * @param weight 权重（用于加权总分，四维默认等权 0.25）
 * @param reason 打分依据（必须说明为什么给这个分数）
 */
public record DimensionScore(
        String key,
        String label,
        double score,
        double weight,
        String reason
) {
}
