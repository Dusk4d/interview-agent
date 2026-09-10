package com.dusk4d.interview.domain;

import java.time.Instant;
import java.util.List;

/**
 * 面试问题。
 *
 * @param sourceIds    问题的事实来源（简历片段 / 知识点 ID），用于「这条建议来自哪段简历」
 * @param sourceSnippets 来源片段摘要，直接给前端展示溯源
 * @param followUpPlan 追问预案（模型给出的下一步方向）
 */
public record InterviewQuestion(
        String id,
        String sessionId,
        int sequence,
        QuestionType type,
        Difficulty difficulty,
        String content,
        String intent,
        List<String> focus,
        List<String> sourceIds,
        List<String> sourceSnippets,
        String followUpPlan,
        String parentQuestionId,
        boolean followUp,
        String stage,
        Instant createdAt
) {
    public InterviewQuestion {
        focus = focus == null ? List.of() : List.copyOf(focus);
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        sourceSnippets = sourceSnippets == null ? List.of() : List.copyOf(sourceSnippets);
    }
}
