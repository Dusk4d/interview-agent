package com.dusk4d.interview.parse;

import java.util.List;

/**
 * 文本清洗结果。
 *
 * @param text         清洗后的正文
 * @param issues       发现的问题
 * @param removedLines 被判定为页眉页脚/水印而移除的行数
 */
public record CleanResult(String text, List<CleanIssue> issues, int removedLines) {

    public CleanResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean has(CleanIssue issue) {
        return issues.contains(issue);
    }

    public boolean usable() {
        return !has(CleanIssue.EMPTY_TEXT) && !has(CleanIssue.MOSTLY_NOISE) && !has(CleanIssue.IMAGE_ONLY_PDF);
    }
}
