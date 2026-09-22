package com.dusk4d.interview.parse.extract;

import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.parse.DocumentText;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档提取路由：先按文件头（magic bytes）判定真实格式，再按扩展名兜底。
 *
 * <p>这样做的原因：求职者常把 docx 改成 pdf、或无扩展名上传，
 * 只看扩展名会产生「格式不支持」的误报。
 */
@Component
public class DocumentExtractorRouter {

    private final List<DocumentTextExtractor> extractors;

    public DocumentExtractorRouter() {
        // 顺序有意义：先按文件头强匹配 pdf/docx，最后才是文本兜底
        this.extractors = List.of(new PdfTextExtractor(), new DocxTextExtractor(), new PlainTextExtractor());
    }

    public DocumentText extract(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw ResumeParseException.emptyText("文件内容为空，无法解析。请确认文件未损坏。");
        }
        byte[] head = head(content);
        for (DocumentTextExtractor extractor : extractors) {
            if (extractor.supports(fileName, null, head)) {
                return extractor.extract(fileName, content);
            }
        }
        throw ResumeParseException.unsupported(
                "不支持的文件格式：" + describe(fileName) + "。当前支持 PDF、DOCX、TXT。");
    }

    /** 由文件名与内容推断类型标识，用于落库与展示。 */
    public String detectType(String fileName, byte[] content) {
        byte[] head = head(content);
        for (DocumentTextExtractor extractor : extractors) {
            if (extractor.supports(fileName, null, head)) {
                return extractor.type();
            }
        }
        return "unknown";
    }

    private String describe(String fileName) {
        return fileName == null || fileName.isBlank() ? "未知文件" : fileName;
    }

    private byte[] head(byte[] content) {
        int length = Math.min(content.length, 16);
        byte[] head = new byte[length];
        System.arraycopy(content, 0, head, 0, length);
        return head;
    }
}
