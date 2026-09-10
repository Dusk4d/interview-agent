package com.dusk4d.interview.domain;

import java.util.List;

/**
 * 结构化事实（简历片段）。
 *
 * @param id         事实 ID，同时作为向量库中的 chunk id（如 resume-project-001）
 * @param resumeId   所属简历
 * @param type       事实类型
 * @param label      展示标签：项目名 / 公司名 / 学校名 等
 * @param content    片段正文（清洗后，联系方式已脱敏）
 * @param sourceOrder 在原简历中的顺序，用于溯源与稳定排序
 * @param confidence 抽取置信度 0~1，低置信度会提示用户人工修正
 * @param metadata   附加元数据（时间、技术栈等）
 */
public record ResumeFact(
        String id,
        String resumeId,
        FactType type,
        String label,
        String content,
        int sourceOrder,
        double confidence,
        List<String> metadata
) {
    public ResumeFact {
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
    }

    public static ResumeFact of(String id, String resumeId, FactType type, String label,
                                String content, int sourceOrder) {
        return new ResumeFact(id, resumeId, type, label, content, sourceOrder, 1.0, List.of());
    }
}
