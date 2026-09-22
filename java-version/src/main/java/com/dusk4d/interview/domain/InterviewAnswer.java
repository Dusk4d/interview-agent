package com.dusk4d.interview.domain;

import java.time.Instant;

/**
 * 用户的一次文字回答。
 *
 * @param rawLength      原始字符数，用于「空回答/极短回答」判定与统计
 * @param trimmedContent 去除首尾空白后的内容（真正进入评估的文本）
 */
public record InterviewAnswer(
        String id,
        String questionId,
        String sessionId,
        String content,
        int rawLength,
        boolean empty,
        Instant createdAt
) {
    public static InterviewAnswer of(String id, InterviewQuestion question, String content, Instant now) {
        String normalized = content == null ? "" : content.trim();
        return new InterviewAnswer(id, question.id(), question.sessionId(), normalized,
                normalized.length(), normalized.isEmpty(), now);
    }

    public String trimmedContent() {
        return content == null ? "" : content.trim();
    }
}
