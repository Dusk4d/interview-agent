package com.dusk4d.interview.parse.extract;

import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.parse.DocumentText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DOCX 文本提取（OOXML）。
 *
 * <p>实现方式：把 docx 当作 zip，读取 {@code word/document.xml} 与页眉页脚部件，
 * 单次扫描提取 {@code <w:t>} 文本，并按元素语义插入换行/分隔符：
 * <ul>
 *   <li>{@code <w:p>} 结束 → 换行（段落）</li>
 *   <li>{@code <w:tr>} 内的单元格边界 {@code </w:tc>} → " | "（表格保持「时间-公司-职责」同行语义）</li>
 *   <li>{@code </w:tr>} → 换行</li>
 *   <li>{@code <w:br/> <w:cr/> <w:tab/>} → 换行或空格</li>
 * </ul>
 *
 * <p>为什么不用 Apache POI：离线环境依赖更少、启动更快，并且这里只需要文字内容。
 * 页眉页脚单独成行注入，交给 {@code TextCleaner} 的重复行检测统一剔除，
 * 比在解析层硬编码删除更稳。
 *
 * <p>注意：本类只提取文字层；文本框/艺术字/图片中的文字不处理。
 * 若正文提取为空会明确报错提示重新导出，而不是产出虚假结构。
 */
public class DocxTextExtractor implements DocumentTextExtractor {

    private static final String DOCUMENT_PART = "word/document.xml";

    /** 单次扫描：段落、换行、制表、表格行/单元格边界。 */
    private static final Pattern MARKUP = Pattern.compile(
            "<w:t(?:\\s[^>]*)?>(.*?)</w:t>"
                    + "|<w:br\\s*/?>|<w:cr\\s*/?>|<w:tab\\s*/?>"
                    + "|</w:p>|</w:tc>|</w:tr>"
                    + "|</w:tbl>",
            Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    /** zip bomb 防护：解压后单个部件最大 32MB。 */
    private static final int MAX_PART_BYTES = 32 * 1024 * 1024;

    @Override
    public boolean supports(String fileName, String contentType, byte[] head) {
        if (head != null && head.length >= 4 && head[0] == 'P' && head[1] == 'K') {
            return true;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".docx");
    }

    @Override
    public DocumentText extract(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw ResumeParseException.emptyText("文件内容为空，无法解析。请确认文件未损坏。");
        }
        List<String> warnings = new ArrayList<>();
        Map<String, String> parts = readParts(content);

        String documentXml = parts.get(DOCUMENT_PART);
        if (documentXml == null) {
            String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".doc")) {
                throw ResumeParseException.unsupported(
                        "检测到旧版 .doc 二进制格式（Word 97-2003），当前版本仅支持 .docx。"
                                + "请用 Word/WPS 另存为 .docx 后重新上传。");
            }
            throw ResumeParseException.unsupported(
                    "该文件不是有效的 .docx 文档（缺少 word/document.xml）。请确认文件未损坏或格式正确。");
        }

        // 页眉页脚放在前面，便于清洗阶段按「重复行」识别并整段剔除
        StringBuilder text = new StringBuilder();
        int paragraphs = 0;
        for (Map.Entry<String, String> entry : parts.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("word/header") || name.startsWith("word/footer")) {
                String headerText = toPlainText(entry.getValue());
                if (!headerText.isBlank()) {
                    text.append(headerText).append('\n');
                }
            }
        }
        String bodyText = toPlainText(documentXml);
        paragraphs = countParagraphs(bodyText);
        if (!bodyText.isBlank()) {
            text.append(bodyText).append('\n');
        }

        String result = text.toString();
        boolean imageOnly = result.replaceAll("\\s", "").length() < 20;
        if (imageOnly) {
            warnings.add("文档正文中未提取到文字，可能内容全部位于图片或文本框中。");
        }
        return new DocumentText(result, "docx", paragraphs, imageOnly, false, warnings);
    }

    @Override
    public String type() {
        return "docx";
    }

    /**
     * 单次扫描 XML，按元素语义拼接纯文本。
     *
     * <p>这里不使用 {@code Matcher.appendReplacement}：表格与段落的处理需要在同一遍扫描中
     * 完成，两遍替换容易出现「表格内容被替换掉又被后续提取忽略」的问题。
     */
    private String toPlainText(String xml) {
        String clean = COMMENT.matcher(xml).replaceAll("");
        Matcher matcher = MARKUP.matcher(clean);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String textNode = matcher.group(1);
            if (textNode != null) {
                sb.append(unescape(textNode));
                continue;
            }
            switch (matcher.group()) {
                case "<w:br/>", "<w:br />", "<w:cr/>", "<w:cr />", "</w:p>", "</w:tr>", "</w:tbl>" ->
                        appendNewline(sb);
                case "<w:tab/>", "<w:tab />" -> sb.append(' ');
                case "</w:tc>" -> sb.append(" | ");
                default -> {
                    // 其它标记忽略
                }
            }
        }
        return normalizeLines(sb.toString());
    }

    private void appendNewline(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    /** 收尾清理：去掉行尾分隔符、压缩空行与多余空格。 */
    private String normalizeLines(String raw) {
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String cleaned = line.replaceAll("[ \\t]{2,}", " ").strip();
            cleaned = cleaned.replaceAll("(\\|\\s*)+$", "").strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            sb.append(cleaned).append('\n');
        }
        return sb.toString().strip();
    }

    private int countParagraphs(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) text.lines().filter(line -> !line.isBlank()).count();
    }

    /** 只读取需要的 XML 部件（跳过 media 等大二进制部件）。 */
    private Map<String, String> readParts(byte[] content) {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean wanted = DOCUMENT_PART.equals(name)
                        || name.startsWith("word/header")
                        || name.startsWith("word/footer");
                if (!wanted || entry.isDirectory()) {
                    continue;
                }
                parts.put(name, new String(readLimited(zip), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw ResumeParseException.unsupported("docx 文件解压失败，可能已损坏：" + e.getMessage());
        }
        return parts;
    }

    private byte[] readLimited(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > MAX_PART_BYTES) {
                throw new IOException("XML 部件过大，已中止解压");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String unescape(String xml) {
        if (xml.indexOf('&') < 0) {
            return xml;
        }
        return xml.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
