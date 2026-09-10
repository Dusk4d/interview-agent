package com.dusk4d.interview.agent;

import com.dusk4d.interview.error.LlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("结构化输出解析 StructuredOutputParser")
class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());

    @Test
    @DisplayName("标准 JSON 直接解析")
    void parsesPlainJson() {
        Map<String, Object> parsed = parser.parseObject("{\"summary\":\"不错\",\"score\":4}", List.of("summary"));
        assertThat(parsed).containsEntry("summary", "不错");
    }

    @Test
    @DisplayName("markdown 代码块包裹的 JSON 应被剥离")
    void stripsCodeFence() {
        String raw = "```json\n{\"summary\": \"结构清晰\"}\n```";
        Map<String, Object> parsed = parser.parseObject(raw, List.of("summary"));
        assertThat(parsed).containsEntry("summary", "结构清晰");
    }

    @Test
    @DisplayName("带前后解释文字的 JSON 应被截取")
    void carvesJsonFromProse() {
        String raw = "好的，这是我的评估：\n{\"summary\":\"回答完整\",\"missingPoints\":[\"边界条件\"]}\n希望有帮助！";
        Map<String, Object> parsed = parser.parseObject(raw, List.of("summary", "missingPoints"));
        assertThat(parsed).containsEntry("summary", "回答完整");
        assertThat(StructuredOutputParser.stringList(parsed, "missingPoints")).containsExactly("边界条件");
    }

    @Test
    @DisplayName("中文引号、单引号、尾随逗号等常见瑕疵可修复")
    void repairsCommonDefects() {
        String raw = "{'summary': '回答不错', 'score': 4, }";
        Map<String, Object> parsed = parser.parseObject(raw, List.of("summary"));
        assertThat(parsed).containsEntry("summary", "回答不错");
        assertThat(StructuredOutputParser.number(parsed, "score", 0, 0, 5)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("未加引号的键名可修复")
    void repairsUnquotedKeys() {
        Map<String, Object> parsed = parser.parseObject("{summary: \"ok\", score: 3}", List.of("summary"));
        assertThat(parsed).containsEntry("summary", "ok");
    }

    @Test
    @DisplayName("被截断的 JSON 可补全")
    void repairsTruncatedJson() {
        String raw = "{\"summary\":\"回答覆盖了机制，但缺少边界\",\"missingPoints\":[\"并发边界\",\"验证方式\"";
        Map<String, Object> parsed = parser.parseObject(raw, List.of("summary", "missingPoints"));
        assertThat(parsed).containsKey("summary");
        assertThat(StructuredOutputParser.stringList(parsed, "missingPoints")).contains("并发边界");
    }

    @Test
    @DisplayName("缺少必需字段必须报错，不能静默当 0 分")
    void rejectsMissingRequiredField() {
        assertThatThrownBy(() -> parser.parseObject("{\"foo\":1}", List.of("summary")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("缺少必需字段");
    }

    @Test
    @DisplayName("完全非 JSON 的输出报错而不是返回空结果")
    void rejectsNonJson() {
        assertThatThrownBy(() -> parser.parseObject("我觉得回答得挺好的。", List.of("summary")))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> parser.parseObject("", List.of("summary")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("空内容");
    }

    @Test
    @DisplayName("数值解析兼容中文描述与分数写法，并裁剪到区间")
    void numberParsing() {
        Map<String, Object> map = Map.of("a", "4 分", "b", "3.5/5", "c", 9, "d", "无");
        assertThat(StructuredOutputParser.number(map, "a", 0, 0, 5)).isEqualTo(4.0);
        assertThat(StructuredOutputParser.number(map, "b", 0, 0, 5)).isEqualTo(3.5);
        assertThat(StructuredOutputParser.number(map, "c", 0, 0, 5)).isEqualTo(5.0);
        assertThat(StructuredOutputParser.number(map, "d", 1, 0, 5)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("布尔解析兼容中英文写法")
    void boolParsing() {
        Map<String, Object> map = Map.of("a", "是", "b", "no", "c", true);
        assertThat(StructuredOutputParser.bool(map, "a", false)).isTrue();
        assertThat(StructuredOutputParser.bool(map, "b", true)).isFalse();
        assertThat(StructuredOutputParser.bool(map, "c", false)).isTrue();
        assertThat(StructuredOutputParser.bool(map, "missing", true)).isTrue();
    }

    @Test
    @DisplayName("列表字段兼容「字符串数组」与「分号分隔文本」两种返回")
    void listParsing() {
        Map<String, Object> map = Map.of("a", List.of("x", "y"), "b", "x；y；z", "c", List.of());
        assertThat(StructuredOutputParser.stringList(map, "a")).containsExactly("x", "y");
        assertThat(StructuredOutputParser.stringList(map, "b")).containsExactly("x", "y", "z");
        assertThat(StructuredOutputParser.stringList(map, "c")).isEmpty();
    }

    @Test
    @DisplayName("嵌套对象可读取为 Map")
    void nestedMap() {
        Map<String, Object> map = Map.of("dims", Map.of("k", Map.of("score", 3)));
        Map<String, Object> nested = StructuredOutputParser.map(map, "dims");
        assertThat(nested).containsKey("k");
        Map<String, Object> inner = StructuredOutputParser.map(nested, "k");
        assertThat(StructuredOutputParser.number(inner, "score", 0, 0, 5)).isEqualTo(3.0);
    }
}
