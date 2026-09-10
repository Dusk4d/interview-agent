package com.dusk4d.interview.agent;

import com.dusk4d.interview.error.LlmException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化输出解析与修复。
 *
 * <p>大模型输出 JSON 失败是常态，方案书要求「不能把非法 JSON 当作业务结果」。
 * 这里的处理策略由宽到严分四步，每步都可解释：
 * <ol>
 *   <li>去除 markdown 代码围栏与前后解释文本；</li>
 *   <li>直接解析；</li>
 *   <li>修复常见瑕疵（中文引号、单引号、尾随逗号、未加引号的键、注释）；</li>
 *   <li>截断修复（补全未闭合的字符串/对象/数组）后再解析。</li>
 * </ol>
 * 全部失败时抛 {@link LlmException}（错误码 LLM_INVALID_STRUCTURE），
 * 由上层决定「再让模型修一次」还是「降级为启发式结果」，绝不会把缺失字段当成 0 分展示。
 */
@Component
public class StructuredOutputParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputParser.class);

    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json|JSON)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern FIRST_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)^\\s*//.*$");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern UNQUOTED_KEY = Pattern.compile("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*:)");

    private final ObjectMapper objectMapper;

    public StructuredOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析为映射，并校验必需字段。
     *
     * @param raw           模型原始输出
     * @param requiredFields 期望存在的字段（缺失即视为失败）
     */
    public Map<String, Object> parseObject(String raw, List<String> requiredFields) {
        if (raw == null || raw.isBlank()) {
            throw LlmException.invalidStructure("模型返回空内容，无法解析结构化结果。");
        }
        List<String> attempts = new ArrayList<>();
        String stripped = stripFences(raw).strip();
        attempts.add(stripped);
        String carved = carveObject(stripped);
        if (carved != null && !carved.equals(stripped)) {
            attempts.add(carved);
        }
        String repaired = repair(carved != null ? carved : stripped);
        attempts.add(repaired);
        attempts.add(repairTruncated(repaired));

        LlmException lastFailure = null;
        for (String candidate : attempts) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> parsed = objectMapper.readValue(candidate, new TypeReference<>() {
                });
                List<String> missing = missingFields(parsed, requiredFields);
                if (missing.isEmpty()) {
                    return parsed;
                }
                lastFailure = LlmException.invalidStructure("结构化输出缺少必需字段：" + missing);
            } catch (Exception e) {
                lastFailure = LlmException.invalidStructure("结构化输出解析失败：" + firstLine(e.getMessage()));
            }
        }
        log.debug("结构化解析失败，原始输出前 300 字符：{}", abbreviate(raw, 300));
        throw lastFailure == null
                ? LlmException.invalidStructure("结构化输出无法解析。")
                : lastFailure;
    }

    /** 解析为 Jackson 树，便于按路径取值。 */
    public JsonNode parseTree(String raw) {
        try {
            return objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw LlmException.invalidStructure("结构化输出解析失败：" + firstLine(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 内部步骤

    /** 去掉 ```json 围栏，保留围栏内内容。 */
    String stripFences(String raw) {
        Matcher matcher = CODE_FENCE.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return raw;
    }

    /** 从解释性文本中截取最外层 JSON 对象。 */
    String carveObject(String text) {
        Matcher matcher = FIRST_OBJECT.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group();
        // 只保留第一个平衡的 {...}
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return candidate.substring(0, i + 1);
                }
            }
        }
        return candidate;
    }

    /** 修复常见 JSON 瑕疵。 */
    String repair(String text) {
        String result = text;
        result = LINE_COMMENT.matcher(result).replaceAll("");
        result = BLOCK_COMMENT.matcher(result).replaceAll("");
        result = result.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'');
        result = result.replace('：', ':');
        result = UNQUOTED_KEY.matcher(result).replaceAll("$1\"$2\"$3");
        result = TRAILING_COMMA.matcher(result).replaceAll("$1");
        // 单引号包裹的键/值（仅当没有双引号包裹时替换，避免破坏合法内容）
        if (!result.contains("\"")) {
            result = result.replace('\'', '"');
        }
        return result.strip();
    }

    /** 截断修复：补全未闭合的引号、对象与数组。 */
    String repairTruncated(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        boolean inString = false;
        boolean escaped = false;
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                default -> {
                    // 其它字符无需处理
                }
            }
        }
        // 去掉可能被截断的半个键值对
        if (inString) {
            sb.append('"');
        }
        String trimmed = sb.toString().stripTrailing();
        if (trimmed.endsWith(",")) {
            sb.setLength(sb.length() - 1);
            trimmed = sb.toString().stripTrailing();
        }
        if (trimmed.endsWith(":")) {
            sb.append(" null");
        }
        while (brackets-- > 0) {
            sb.append(']');
        }
        while (braces-- > 0) {
            sb.append('}');
        }
        return sb.toString();
    }

    private List<String> missingFields(Map<String, Object> parsed, List<String> requiredFields) {
        if (requiredFields == null || requiredFields.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String field : requiredFields) {
            if (!parsed.containsKey(field) || parsed.get(field) == null) {
                missing.add(field);
            }
        }
        return missing;
    }

    // ---------------------------------------------------------------- 取值助手

    /** 读取字符串；缺失返回默认值。 */
    public static String string(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? defaultValue : text;
    }

    public static String string(Map<String, Object> map, String key) {
        return string(map, key, null);
    }

    /** 读取字符串列表；模型可能返回字符串而非数组，这里统一处理。 */
    @SuppressWarnings("unchecked")
    public static List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element == null) {
                    continue;
                }
                if (element instanceof Map<?, ?> nested) {
                    // 例如 followUpRisks: [{topic, reason}]，取其中的值文本
                    for (Object nestedValue : nested.values()) {
                        if (nestedValue != null && !String.valueOf(nestedValue).isBlank()) {
                            result.add(String.valueOf(nestedValue).strip());
                        }
                    }
                } else {
                    String text = String.valueOf(element).strip();
                    if (!text.isEmpty()) {
                        result.add(text);
                    }
                }
            }
            return result;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return List.of();
        }
        // 兼容「用分号/换行分隔的一整段文本」
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[;；\\n]")) {
            String cleaned = part.strip();
            if (!cleaned.isEmpty()) {
                parts.add(cleaned);
            }
        }
        return parts;
    }

    /** 读取数值，支持 "3"、"3.5 分"、"3/5" 等写法，最终裁剪到 [min,max]。 */
    public static double number(Map<String, Object> map, String key, double defaultValue, double min, double max) {
        Object value = map.get(key);
        double parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else if (value != null) {
            Matcher matcher = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(String.valueOf(value));
            if (matcher.find()) {
                try {
                    parsed = Double.parseDouble(matcher.group());
                } catch (NumberFormatException ignored) {
                    parsed = defaultValue;
                }
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    /** 读取布尔值，兼容 "true"/"是"/"yes"/1。 */
    public static boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return defaultValue;
        }
        return switch (text) {
            case "true", "yes", "y", "1", "是", "需要", "建议" -> true;
            case "false", "no", "n", "0", "否", "不需要", "不建议" -> false;
            default -> defaultValue;
        };
    }

    /** 读取嵌套对象。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> result = new LinkedHashMap<>();
            nested.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private String firstLine(String message) {
        if (message == null) {
            return "未知原因";
        }
        int index = message.indexOf('\n');
        return index < 0 ? message : message.substring(0, index);
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
