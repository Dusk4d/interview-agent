package com.dusk4d.interview.domain;

import java.time.Instant;
import java.util.List;

/**
 * 面试复盘报告（对应方案书 6.6）。
 *
 * @param abilityRadar  能力雷达（多维度）
 * @param questionCount 题目数量
 * @param answerCount   回答数量
 * @param markdown      可直接下载的 Markdown 版本
 */
public record InterviewReport(
        String id,
        String sessionId,
        InterviewMode mode,
        Instant startedAt,
        Instant endedAt,
        long durationSeconds,
        int questionCount,
        int answerCount,
        double overallScore,
        List<AbilityDimension> abilityRadar,
        List<String> weakestQuestions,
        List<WeakTopic> knowledgeGaps,
        List<String> projectRisks,
        List<String> actionItems,
        String summary,
        String markdown,
        Instant createdAt
) {
    public InterviewReport {
        abilityRadar = abilityRadar == null ? List.of() : List.copyOf(abilityRadar);
        weakestQuestions = weakestQuestions == null ? List.of() : List.copyOf(weakestQuestions);
        knowledgeGaps = knowledgeGaps == null ? List.of() : List.copyOf(knowledgeGaps);
        projectRisks = projectRisks == null ? List.of() : List.copyOf(projectRisks);
        actionItems = actionItems == null ? List.of() : List.copyOf(actionItems);
    }

    /** 生成 Markdown 版本（报告先在内存中成形，再渲染为可下载文本）。 */
    public InterviewReport withMarkdown(String renderedMarkdown) {
        return new InterviewReport(id, sessionId, mode, startedAt, endedAt, durationSeconds, questionCount,
                answerCount, overallScore, abilityRadar, weakestQuestions, knowledgeGaps, projectRisks,
                actionItems, summary, renderedMarkdown, createdAt);
    }
}
