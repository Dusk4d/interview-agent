package com.dusk4d.interview.parse;

import java.util.List;

/**
 * 文档文本提取结果。
 *
 * @param rawText           原始提取文本（未清洗、未脱敏）
 * @param sourceType        实际识别到的文件类型：pdf / docx / txt
 * @param pages             页数（PDF）或段落数（DOCX），未知为 0
 * @param imageOnly         疑似图片型文档（无文字层），第一版明确不支持
 * @param passwordProtected 文档被加密，需要密码
 * @param warnings          提取过程中的告警（不阻断流程）
 */
public record DocumentText(
        String rawText,
        String sourceType,
        int pages,
        boolean imageOnly,
        boolean passwordProtected,
        List<String> warnings
) {
    public DocumentText {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static DocumentText of(String rawText, String sourceType, int pages) {
        return new DocumentText(rawText, sourceType, pages, false, false, List.of());
    }

    public boolean hasText() {
        return rawText != null && !rawText.isBlank();
    }
}
