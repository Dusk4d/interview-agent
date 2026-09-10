package com.dusk4d.interview.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 最小 DOCX 生成器（测试专用）。
 *
 * <p>不引入 Apache POI：测试只需要一个能被 {@code DocxTextExtractor} 解析的 OOXML 包，
 * 手写 zip 更轻，也避免了测试依赖与生产提取器实现「同源」，能真正验证解析逻辑。
 */
public final class DocxFileBuilder {

    private DocxFileBuilder() {
    }

    /** 用若干段落构造 docx。 */
    public static byte[] build(List<String> paragraphs) {
        return build(paragraphs, List.of());
    }

    /** 用段落 + 页眉构造 docx（页眉用于验证页眉页脚剔除逻辑）。 */
    public static byte[] build(List<String> paragraphs, List<String> headers) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escape(paragraph))
                    .append("</w:t></w:r></w:p>");
        }
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s</w:body>
                </w:document>
                """.formatted(body);

        List<String[]> entries = new ArrayList<>();
        entries.add(new String[]{"[Content_Types].xml", contentTypes(headers.isEmpty())});
        entries.add(new String[]{"word/document.xml", documentXml});
        if (!headers.isEmpty()) {
            StringBuilder headerXml = new StringBuilder();
            for (String header : headers) {
                headerXml.append("<w:p><w:r><w:t>").append(escape(header)).append("</w:t></w:r></w:p>");
            }
            entries.add(new String[]{"word/header1.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    %s
                    </w:hdr>
                    """.formatted(headerXml)});
        }
        return zip(entries);
    }

    /** 构造一个表格形式的 docx（验证表格行按 | 拼接）。 */
    public static byte[] buildWithTable(List<String[]> rows) {
        StringBuilder table = new StringBuilder("<w:tbl>");
        for (String[] row : rows) {
            table.append("<w:tr>");
            for (String cell : row) {
                table.append("<w:tc><w:p><w:r><w:t>").append(escape(cell)).append("</w:t></w:r></w:p></w:tc>");
            }
            table.append("</w:tr>");
        }
        table.append("</w:tbl>");
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s</w:body>
                </w:document>
                """.formatted(table);
        return zip(List.of(
                new String[]{"[Content_Types].xml", contentTypes(false)},
                new String[]{"word/document.xml", documentXml}));
    }

    /** 空正文（模拟纯图片简历）。 */
    public static byte[] buildEmptyBody() {
        return build(List.of(""));
    }

    private static String contentTypes(boolean withHeader) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                """);
        if (withHeader) {
            xml.append("<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>");
        }
        xml.append("</Types>");
        return xml.toString();
    }

    private static byte[] zip(List<String[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] entry : entries) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
