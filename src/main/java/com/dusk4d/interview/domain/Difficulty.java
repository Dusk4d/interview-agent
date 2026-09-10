package com.dusk4d.interview.domain;

/** 问题难度。 */
public enum Difficulty {
    EASY,
    MEDIUM,
    HARD;

    /** 宽容解析：无法识别时回退到 MEDIUM，避免非法枚举导致整条链路失败。 */
    public static Difficulty parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEDIUM;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (Difficulty d : values()) {
            if (d.name().equals(normalized)) {
                return d;
            }
        }
        return switch (normalized) {
            case "简单", "容易", "低", "EASY", "LOW", "1" -> EASY;
            case "困难", "难", "高", "HARD", "HIGH", "3" -> HARD;
            default -> MEDIUM;
        };
    }
}
