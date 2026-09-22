package com.dusk4d.interview.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次面试会话。
 *
 * <p>持久化的是「必要上下文」而不是整场原文：{@code coveredTopics} 与
 * {@code recentFeedback} 用于压缩历史，避免上下文线性膨胀。
 */
public record InterviewSession(
        String id,
        String resumeId,
        InterviewMode mode,
        InterviewStage stage,
        SessionStatus status,
        String currentQuestionId,
        String lastAnswerId,
        String activeProject,
        int questionCount,
        int answerCount,
        int followUpCount,
        int followUpUsedOnCurrent,
        int maxQuestions,
        List<String> coveredTopics,
        List<String> recentFeedback,
        List<String> askedQuestionDigests,
        String endReason,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Instant updatedAt
) {
    public InterviewSession {
        coveredTopics = coveredTopics == null ? List.of() : List.copyOf(coveredTopics);
        recentFeedback = recentFeedback == null ? List.of() : List.copyOf(recentFeedback);
        askedQuestionDigests = askedQuestionDigests == null ? List.of() : List.copyOf(askedQuestionDigests);
    }

    public boolean canAcceptAnswer() {
        return status == SessionStatus.WAITING_ANSWER || status == SessionStatus.FOLLOW_UP;
    }

    public boolean questionBudgetExhausted() {
        return questionCount >= maxQuestions;
    }
}
