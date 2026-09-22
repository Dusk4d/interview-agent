package com.dusk4d.interview.domain;

import java.util.List;

/**
 * 基础知识库条目（八股面的事实边界）。
 *
 * @param id              条目 ID（knowledge-<topic>-<n>）
 * @param topic           主题：Java / Spring / MySQL / Redis / Network / OS / Algorithm ...
 * @param subtopic        子主题：JVM GC / 索引 / 缓存一致性 ...
 * @param difficulty      难度
 * @param title           标题
 * @param content         知识点正文
 * @param referenceAnswer 参考要点（评分时作为「应当覆盖的关键点」）
 * @param keyPoints       关键点列表，用于结构化评分对齐
 * @param commonMistakes  常见误区，用于「边界条件」维度评分
 */
public record KnowledgeItem(
        String id,
        String topic,
        String subtopic,
        Difficulty difficulty,
        String title,
        String content,
        String referenceAnswer,
        List<String> keyPoints,
        List<String> commonMistakes
) {
    public KnowledgeItem {
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        commonMistakes = commonMistakes == null ? List.of() : List.copyOf(commonMistakes);
    }
}
