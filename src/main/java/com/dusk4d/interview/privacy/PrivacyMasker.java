package com.dusk4d.interview.privacy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历隐私处理。
 *
 * <p>方案书要求：手机号和邮箱只用于个人页面展示，不应进入问题生成上下文，也不应写入日志。
 * 这里提供统一的脱敏实现，供解析、提示词构造与日志三处复用，保证口径一致。
 */
@Component
public class PrivacyMasker {

    public static final String PHONE_PLACEHOLDER = "[手机号已脱敏]";
    public static final String EMAIL_PLACEHOLDER = "[邮箱已脱敏]";
    public static final String ID_CARD_PLACEHOLDER = "[证件号已脱敏]";
    public static final String URL_PLACEHOLDER = "[链接已脱敏]";
    public static final String ADDRESS_PLACEHOLDER = "[地址已脱敏]";
    public static final String NAME_PLACEHOLDER = "[姓名已脱敏]";

    /** 中国大陆手机号（11 位，1 开头，第二位 3-9），允许分组书写如 138-1234-5678 / 138 1234 5678。 */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[-\\s]?)?1[3-9]\\d(?:[-\\s]?\\d{4}){2}(?!\\d)");
    /** 座机：区号 + 号码。 */
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)0\\d{2,3}-\\d{7,8}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    /** 15/18 位身份证（含 X 结尾）。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])|(?<![0-9A-Za-z])\\d{15}(?![0-9A-Za-z])");
    private static final Pattern URL = Pattern.compile("https?://[\\w\\-./?%&=#:~+]+", Pattern.CASE_INSENSITIVE);
    /** 仅当同一行出现地址关键词时才脱敏，避免误伤项目描述。 */
    private static final Pattern ADDRESS = Pattern.compile(
            "(?:现居|居住地|家庭住址|通讯地址|地址)[:：\\s]*[\\u4e00-\\u9fa5A-Za-z0-9\\-号栋单元室路街道区县市省]{4,40}");

    /**
     * 通用脱敏：手机号、邮箱、身份证、链接、地址。
     *
     * @param text 原文，可为 null
     * @return 脱敏后文本；null 输入返回空串
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        result = EMAIL.matcher(result).replaceAll(Matcher.quoteReplacement(EMAIL_PLACEHOLDER));
        result = PHONE.matcher(result).replaceAll(Matcher.quoteReplacement(PHONE_PLACEHOLDER));
        result = LANDLINE.matcher(result).replaceAll(Matcher.quoteReplacement(PHONE_PLACEHOLDER));
        result = ID_CARD.matcher(result).replaceAll(Matcher.quoteReplacement(ID_CARD_PLACEHOLDER));
        result = URL.matcher(result).replaceAll(Matcher.quoteReplacement(URL_PLACEHOLDER));
        result = ADDRESS.matcher(result).replaceAll(Matcher.quoteReplacement(ADDRESS_PLACEHOLDER));
        return result;
    }

    /** 按需脱敏姓名（默认关闭，避免把「张三」这类姓名在项目描述中误伤）。 */
    public String maskName(String text, String name) {
        if (text == null || name == null || name.isBlank() || name.length() > 4) {
            return text == null ? "" : text;
        }
        return text.replace(name, NAME_PLACEHOLDER);
    }

    /** 判断文本中是否仍含敏感信息（用于测试与自检）。 */
    public boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return PHONE.matcher(text).find()
                || EMAIL.matcher(text).find()
                || ID_CARD.matcher(text).find()
                || LANDLINE.matcher(text).find();
    }

    /** 返回命中的敏感类型及数量，便于测试断言与隐私自检报告。 */
    public Map<String, Integer> scan(String text) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        countInto(hits, "phone", PHONE, text);
        countInto(hits, "landline", LANDLINE, text);
        countInto(hits, "email", EMAIL, text);
        countInto(hits, "idCard", ID_CARD, text);
        countInto(hits, "url", URL, text);
        return hits;
    }

    private void countInto(Map<String, Integer> hits, String key, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count > 0) {
            hits.put(key, count);
        }
    }

    /**
     * 日志安全的截断：只保留开头若干字符并脱敏。
     *
     * <p>先脱敏再截断，且长度上限按脱敏结果计算——否则可能把「[手机号已脱敏]」截成半截，
     * 既泄露格式又不可读。
     */
    public String forLog(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String masked = mask(text).replaceAll("\\s+", " ").trim();
        int limit = Math.max(0, maxChars);
        return masked.length() <= limit ? masked : masked.substring(0, limit) + "…";
    }

    public List<String> placeholders() {
        return List.of(PHONE_PLACEHOLDER, EMAIL_PLACEHOLDER, ID_CARD_PLACEHOLDER,
                URL_PLACEHOLDER, ADDRESS_PLACEHOLDER, NAME_PLACEHOLDER);
    }
}
