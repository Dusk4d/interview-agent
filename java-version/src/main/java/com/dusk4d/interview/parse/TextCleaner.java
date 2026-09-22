package com.dusk4d.interview.parse;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 简历文本清洗。
 *
 * <p>职责边界（方案书 6.1）：
 * <ol>
 *   <li>统一换行与空白，去掉零宽字符与控制字符；</li>
 *   <li>识别并移除页眉页脚/水印（靠「同一内容在多页重复出现」判定，不做硬编码关键词匹配）；</li>
 *   <li>去掉纯分隔符行与重复空行；</li>
 *   <li>给出明确问题标记（空文本、乱码、图片型 PDF），不静默产出虚假结构。</li>
 * </ol>
 *
 * <p>注意：这里不做脱敏，脱敏由 {@code PrivacyMasker} 统一负责，避免两处口径不一致。
 */
@Component
public class TextCleaner {

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\uFEFF\\u2060]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    /** 仅由分隔符/空白组成的行，如 "-----"、"=====" 、"···"。 */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[\\s\\-—_=*·.。~#+*/\\\\|<>·•]{3,}$");
    /** 纯页码行：如 "1 / 3"、"第 2 页"、"- 3 -"。 */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^[\\s\\-—_]*(?:第\\s*\\d+\\s*页(?:\\s*/\\s*共\\s*\\d+\\s*页)?|\\d+\\s*/\\s*\\d+|page\\s*\\d+(\\s*of\\s*\\d+)?|\\d{1,3})[\\s\\-—_]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+(?=\\n)");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final Pattern GARBLE = Pattern.compile("(?:[ÃÂåæçèéêëÐÑ][\\x80-\\xBF])|(?:\\uFFFD){2,}|(?:[锟斤拷]{2,})");

    private static final int SHORT_TEXT_THRESHOLD = 40;
    /** 页眉页脚判定：归一化后重复出现的最小次数。 */
    private static final int REPEAT_THRESHOLD = 3;
    private static final int MAX_REMOVED_LINES = 40;

    private final int repeatThreshold;

    public TextCleaner() {
        this(REPEAT_THRESHOLD);
    }

    /** 允许调整重复阈值，便于测试与不同文档风格。 */
    public TextCleaner(int repeatThreshold) {
        this.repeatThreshold = Math.max(2, repeatThreshold);
    }

    /**
     * 清洗文本。
     *
     * @param raw 原始提取文本（可为 null）
     * @return 清洗结果（含问题标记）
     */
    public CleanResult clean(String raw) {
        List<CleanIssue> issues = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            issues.add(CleanIssue.EMPTY_TEXT);
            return new CleanResult("", issues, 0);
        }

        String normalized = normalize(raw);
        if (GARBLE.matcher(normalized).find()) {
            issues.add(CleanIssue.ENCODING_GARBLED);
        }

        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        Map<String, Integer> frequency = new HashMap<>();
        for (String line : lines) {
            String key = headerFooterKey(line);
            if (key != null) {
                frequency.merge(key, 1, Integer::sum);
            }
        }

        List<String> kept = new ArrayList<>(lines.size());
        int removed = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                kept.add("");
                continue;
            }
            String key = headerFooterKey(line);
            boolean repeated = key != null && frequency.getOrDefault(key, 0) >= repeatThreshold;
            boolean noise = SEPARATOR_LINE.matcher(trimmed).matches()
                    || PAGE_NUMBER.matcher(trimmed).matches();
            if ((repeated || noise) && removed < MAX_REMOVED_LINES) {
                removed++;
                continue;
            }
            kept.add(line);
        }

        String text = String.join("\n", kept);
        text = MULTI_BLANK.matcher(text).replaceAll("\n\n");
        text = TRAILING_SPACE.matcher(text).replaceAll("");
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        text = text.strip();

        if (text.isEmpty()) {
            issues.add(CleanIssue.EMPTY_TEXT);
        } else {
            if (noiseRatio(text) > 0.55) {
                issues.add(CleanIssue.MOSTLY_NOISE);
            }
            if (text.length() < SHORT_TEXT_THRESHOLD) {
                issues.add(CleanIssue.TOO_SHORT);
            }
        }
        return new CleanResult(text, issues, removed);
    }

    /** 归一化：换行、制表符、零宽字符、控制字符。 */
    public String normalize(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("");
        text = text.replace('\t', ' ').replace('\u00A0', ' ');
        return text;
    }

    /**
     * 页眉页脚归一化关键字：去掉数字与空白后仍足够长的行才参与重复统计，
     * 避免「2023.09-2024.06」这类只差数字的行被误判为页眉。
     */
    private String headerFooterKey(String line) {
        String trimmed = line.strip();
        if (trimmed.length() < 4) {
            return null;
        }
        String key = trimmed.replaceAll("\\d", "#").replaceAll("\\s+", "");
        if (key.length() < 3) {
            return null;
        }
        // 全是占位符（例如纯日期行）不参与
        if (key.chars().allMatch(c -> c == '#')) {
            return null;
        }
        return key;
    }

    private double noiseRatio(String text) {
        int total = 0;
        int meaningful = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (Character.isLetterOrDigit(c) || isCjk(c)) {
                meaningful++;
            }
        }
        return total == 0 ? 1.0 : 1.0 - ((double) meaningful / total);
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
