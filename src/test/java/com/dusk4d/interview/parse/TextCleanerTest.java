package com.dusk4d.interview.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("文本清洗 TextCleaner")
class TextCleanerTest {

    private final TextCleaner cleaner = new TextCleaner();

    @Test
    @DisplayName("空白输入返回 EMPTY_TEXT 而不是抛异常")
    void emptyInput() {
        assertThat(cleaner.clean(null).has(CleanIssue.EMPTY_TEXT)).isTrue();
        assertThat(cleaner.clean("   \n\n  ").has(CleanIssue.EMPTY_TEXT)).isTrue();
        assertThat(cleaner.clean("  \n ").usable()).isFalse();
    }

    @Test
    @DisplayName("CRLF、制表符、零宽字符应被归一化")
    void normalizesWhitespace() {
        CleanResult result = cleaner.clean("项目经历\r\n\t技术栈：Java\u200B\r\n\r\n\r\n结果：上线");
        assertThat(result.text()).doesNotContain("\r").doesNotContain("\t").doesNotContain("\u200B");
        assertThat(result.text()).doesNotContain("\n\n\n");
        assertThat(result.text()).contains("技术栈：Java");
    }

    @Test
    @DisplayName("重复出现的页眉页脚应被移除")
    void removesRepeatedHeaders() {
        String page = "张伟的简历 | 13800138000\n正文内容一\n张伟的简历 | 13800138000\n正文内容二\n"
                + "张伟的简历 | 13800138000\n正文内容三\n";
        CleanResult result = cleaner.clean(page);
        assertThat(result.removedLines()).isEqualTo(3);
        assertThat(result.text()).doesNotContain("张伟的简历");
        assertThat(result.text()).contains("正文内容一", "正文内容二", "正文内容三");
    }

    @Test
    @DisplayName("页码与分隔线应被移除，但正常内容保留")
    void removesPageNumbersAndSeparators() {
        String text = "项目经历\n----------\n第 1 页\nFinanceAgent 智能问答系统\n1 / 3\n结果：响应 1.8 秒\n=====\n";
        CleanResult result = cleaner.clean(text);
        assertThat(result.text()).doesNotContain("----------").doesNotContain("第 1 页")
                .doesNotContain("1 / 3").doesNotContain("=====");
        assertThat(result.text()).contains("FinanceAgent 智能问答系统").contains("结果：响应 1.8 秒");
    }

    @Test
    @DisplayName("两行内容相同但只有两页时不应被误删（阈值保护）")
    void keepsContentBelowThreshold() {
        String text = "重要说明：本项目已上线\n正文一\n重要说明：本项目已上线\n正文二\n";
        CleanResult result = cleaner.clean(text);
        assertThat(result.text()).contains("重要说明：本项目已上线");
        assertThat(result.removedLines()).isZero();
    }

    @Test
    @DisplayName("乱码检测：连续替换字符应被标记")
    void detectsGarble() {
        CleanResult result = cleaner.clean("项目经历\n锟斤拷锟斤拷锟斤拷\n技术栈");
        assertThat(result.has(CleanIssue.ENCODING_GARBLED)).isTrue();
    }

    @Test
    @DisplayName("纯符号内容应被判定为不可用")
    void detectsNoise() {
        CleanResult result = cleaner.clean("★☆★☆ ※※※※ ◆◆◆◆ ○○○○ △△△△");
        assertThat(result.has(CleanIssue.MOSTLY_NOISE)).isTrue();
        assertThat(result.usable()).isFalse();
    }

    @Test
    @DisplayName("过短文本应被标记 TOO_SHORT")
    void detectsShortText() {
        CleanResult result = cleaner.clean("王强 本科");
        assertThat(result.has(CleanIssue.TOO_SHORT)).isTrue();
        assertThat(result.usable()).isTrue();
    }

    @Test
    @DisplayName("正常简历内容不应被误判")
    void keepsNormalResume() {
        String text = """
                张伟
                教育经历
                2021.09 - 2025.06  北京邮电大学  计算机科学与技术  本科
                项目经历
                2023.09 - 2024.05  FinanceAgent 智能财务问答系统
                个人职责：负责文档解析与检索链路
                """;
        CleanResult result = cleaner.clean(text);
        assertThat(result.usable()).isTrue();
        assertThat(result.text()).contains("北京邮电大学").contains("FinanceAgent");
        assertThat(result.removedLines()).isZero();
    }
}
