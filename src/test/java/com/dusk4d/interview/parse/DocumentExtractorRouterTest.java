package com.dusk4d.interview.parse;

import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.parse.extract.DocumentExtractorRouter;
import com.dusk4d.interview.support.DocxFileBuilder;
import com.dusk4d.interview.support.Fixtures;
import com.dusk4d.interview.support.PdfFileBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("文档提取（TXT/DOCX/PDF）")
class DocumentExtractorRouterTest {

    private final DocumentExtractorRouter router = new DocumentExtractorRouter();

    @Test
    @DisplayName("TXT：UTF-8 正常解析")
    void parsesUtf8Txt() {
        byte[] content = Fixtures.bytes("resume-standard.txt");
        DocumentText result = router.extract("resume.txt", content);
        assertThat(result.sourceType()).isEqualTo("txt");
        assertThat(result.hasText()).isTrue();
        assertThat(result.rawText()).contains("FinanceAgent").contains("北京邮电大学");
        assertThat(result.imageOnly()).isFalse();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("TXT：GBK 编码应自动回退解码并给出提示")
    void parsesGbkTxt() {
        String source = Fixtures.text("resume-tight-headers.txt");
        byte[] gbk = source.getBytes(Charset.forName("GB18030"));
        DocumentText result = router.extract("resume.txt", gbk);
        assertThat(result.rawText()).contains("智学助手在线教育平台");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("GB18030"));
    }

    @Test
    @DisplayName("TXT：扩展名伪装成 pdf 时按文件头判定为文本")
    void detectsTypeByMagicBytesNotExtension() {
        byte[] content = Fixtures.bytes("resume-standard.txt");
        assertThat(router.detectType("resume.pdf", content)).isEqualTo("txt");
    }

    @Test
    @DisplayName("DOCX：段落与标题正确提取")
    void parsesDocx() {
        List<String> paragraphs = List.of(
                "张伟",
                "教育经历",
                "2021.09 - 2025.06  北京邮电大学  计算机科学与技术  本科",
                "项目经历",
                "2023.09 - 2024.05  FinanceAgent 智能财务问答系统",
                "技术栈：Java 21、Spring Boot 3、PostgreSQL、pgvector");
        byte[] docx = DocxFileBuilder.build(paragraphs, List.of("张伟的简历"));
        DocumentText result = router.extract("resume.docx", docx);

        assertThat(result.sourceType()).isEqualTo("docx");
        assertThat(result.rawText()).contains("北京邮电大学").contains("FinanceAgent").contains("pgvector");
        assertThat(result.rawText()).contains("张伟的简历");
        assertThat(result.imageOnly()).isFalse();
    }

    @Test
    @DisplayName("DOCX：表格内容按行保留")
    void parsesDocxTable() {
        byte[] docx = DocxFileBuilder.buildWithTable(List.of(
                new String[]{"项目", "时间", "职责"},
                new String[]{"FinanceAgent", "2023.09-2024.05", "负责文档解析与检索链路"}));
        DocumentText result = router.extract("resume.docx", docx);
        assertThat(result.rawText()).contains("FinanceAgent").contains("负责文档解析与检索链路");
    }

    @Test
    @DisplayName("DOCX：正文为空时标记 imageOnly 并给出告警")
    void detectsEmptyDocxBody() {
        DocumentText result = router.extract("resume.docx", DocxFileBuilder.buildEmptyBody());
        assertThat(result.imageOnly()).isTrue();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("旧版 .doc 明确提示不支持，而不是抛 500")
    void rejectsLegacyDoc() {
        byte[] fake = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0};
        assertThatThrownBy(() -> router.extract("resume.doc", fake))
                .isInstanceOf(ResumeParseException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    @DisplayName("PDF：可提取文字的 PDF 正常解析")
    void parsesTextPdf() {
        byte[] pdf = PdfFileBuilder.page(List.of(
                "Zhang Wei - Java Backend Developer",
                "Project: FinanceAgent RAG platform",
                "Tech: Java 21, Spring Boot, PostgreSQL, pgvector"));
        DocumentText result = router.extract("resume.pdf", pdf);
        assertThat(result.sourceType()).isEqualTo("pdf");
        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.rawText()).contains("FinanceAgent").contains("pgvector");
        assertThat(result.imageOnly()).isFalse();
    }

    @Test
    @DisplayName("PDF：多页文档页数正确，且页眉被清洗阶段识别")
    void parsesMultiPagePdf() {
        byte[] pdf = PdfFileBuilder.pages(List.of(
                "Zhang Wei Resume\nJava backend services",
                "Zhang Wei Resume\nRedis cache design",
                "Zhang Wei Resume\nMySQL indexing"));
        DocumentText result = router.extract("resume.pdf", pdf);
        assertThat(result.pages()).isEqualTo(3);
        assertThat(result.rawText()).contains("Redis").contains("MySQL");
    }

    @Test
    @DisplayName("PDF：图片型（无文字层）PDF 必须明确不支持，不得产出虚假文本")
    void detectsImageOnlyPdf() {
        DocumentText result = router.extract("scan.pdf", PdfFileBuilder.imageOnly());
        assertThat(result.imageOnly()).isTrue();
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("暂不支持"));
    }

    @Test
    @DisplayName("未知二进制格式明确报错")
    void rejectsUnknownFormat() {
        byte[] content = new byte[]{0x00, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> router.extract("resume.bin", content))
                .isInstanceOf(ResumeParseException.class);
    }

    @Test
    @DisplayName("空文件在入口处报错")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> router.extract("resume.txt", new byte[0]))
                .isInstanceOf(ResumeParseException.class)
                .hasMessageContaining("为空");
    }

    @Test
    @DisplayName("无扩展名但内容是文本时按文本兜底")
    void fallsBackToPlainText() {
        byte[] content = "张伟 简历 项目经历 FinanceAgent".getBytes(StandardCharsets.UTF_8);
        DocumentText result = router.extract(null, content);
        assertThat(result.sourceType()).isEqualTo("txt");
        assertThat(result.rawText()).contains("FinanceAgent");
    }
}
