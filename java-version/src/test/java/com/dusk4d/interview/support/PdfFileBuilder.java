package com.dusk4d.interview.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 PDF 生成器（测试专用）。
 *
 * <p>只使用 Helvetica/WinAnsi 基础字体，用原始对象语法拼装 PDF。
 * 这样可以精确构造三类用例：可提取文字的 PDF、图片型（无文字层）PDF、多页 PDF。
 */
public final class PdfFileBuilder {

    private PdfFileBuilder() {
    }

    private record Page(String content) {
    }

    /** 单页 PDF，多行文字。 */
    public static byte[] page(List<String> lines) {
        return build(List.of(new Page(contentOf(lines))));
    }

    public static byte[] page(String text) {
        return page(List.of(text));
    }

    /** 多页 PDF，每个元素一页（元素内可用 \n 换行）。 */
    public static byte[] pages(List<String> pageTexts) {
        List<Page> pages = new ArrayList<>();
        for (String text : pageTexts) {
            pages.add(new Page(contentOf(List.of(text.split("\n")))));
        }
        return build(pages);
    }

    private static String contentOf(List<String> lines) {
        StringBuilder content = new StringBuilder("BT /F1 12 Tf 72 720 Td 14 TL\n");
        for (String line : lines) {
            content.append('(').append(escape(line)).append(") Tj T*\n");
        }
        content.append("ET");
        return content.toString();
    }

    /** 图片型 PDF：内容流里只有绘图指令，没有文字。 */
    public static byte[] imageOnly() {
        String content = "q 100 0 0 100 72 600 cm /Im0 Do Q";
        return build(List.of(new Page(content)));
    }

    // ---------------------------------------------------------------- 内部实现

    private static byte[] build(List<Page> pages) {
        List<byte[]> objects = new ArrayList<>();
        int pageCount = pages.size();
        int fontObject = 3 + pageCount * 2;      // 目录(1) + 页树(2) + 每页 2 个对象
        int pagesObject = 2;

        // 1: Catalog
        objects.add(obj(1, "<< /Type /Catalog /Pages " + pagesObject + " 0 R >>"));
        // 2: Pages
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(3 + i * 2).append(" 0 R ");
        }
        objects.add(obj(2, "<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>"));
        // 3..: Page + Contents
        for (int i = 0; i < pageCount; i++) {
            int pageObj = 3 + i * 2;
            int contentObj = pageObj + 1;
            objects.add(obj(pageObj, "<< /Type /Page /Parent " + pagesObject + " 0 R /MediaBox [0 0 595 842] "
                    + "/Resources << /Font << /F1 " + fontObject + " 0 R >> >> /Contents " + contentObj + " 0 R >>"));
            byte[] stream = pages.get(i).content().getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(header(contentObj, "<< /Length " + stream.length + " >>"));
            // PDF 规范：stream 关键字后必须换行，endstream 前必须有 EOL，
            // 且 /Length 只计算 EOL 之间的流数据——否则解析器会把后续对象读成流内容。
            out.writeBytes("stream\n".getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(stream);
            out.writeBytes("\nendstream\n".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(out.toByteArray());
        }
        // Font
        objects.add(obj(fontObject, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));

        return assemble(objects);
    }

    private static byte[] obj(int number, String body) {
        return (number + " 0 obj\n" + body + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] header(int number, String body) {
        return (number + " 0 obj\n" + body + "\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] assemble(List<byte[]> objects) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] object : objects) {
            offsets.add(out.size());
            out.writeBytes(object);
        }
        int xrefOffset = out.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objects.size() + 1).append('\n');
        xref.append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            xref.append(String.format("%010d 00000 n \n", offset));
        }
        out.writeBytes(xref.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
