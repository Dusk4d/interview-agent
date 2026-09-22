package com.dusk4d.interview.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次简历导入的结果。
 *
 * <p>{@code rawText} 为清洗后的原文（联系方式已脱敏），{@code maskedText} 记录脱敏后的文本，
 * 供问题生成上下文使用；原始文件本身不落库、不进日志。
 */
public record Resume(
        String id,
        String fileName,
        String fileType,
        long fileSize,
        String rawText,
        String maskedText,
        ResumeStatus status,
        String errorMessage,
        List<String> warnings,
        List<ResumeFact> facts,
        Instant createdAt,
        Instant updatedAt
) {
    public Resume {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    /** 按类型筛选事实。 */
    public List<ResumeFact> factsOf(FactType type) {
        return facts.stream().filter(f -> f.type() == type).toList();
    }

    /** 是否可用于出题：解析成功且存在可检索事实。 */
    public boolean usable() {
        return status == ResumeStatus.PARSED && !facts.isEmpty();
    }

    public Resume withFacts(List<ResumeFact> newFacts) {
        return new Resume(id, fileName, fileType, fileSize, rawText, maskedText, status,
                errorMessage, warnings, newFacts, createdAt, Instant.now());
    }

    public Resume withStatus(ResumeStatus newStatus, String message) {
        return new Resume(id, fileName, fileType, fileSize, rawText, maskedText, newStatus,
                message, warnings, facts, createdAt, Instant.now());
    }
}
