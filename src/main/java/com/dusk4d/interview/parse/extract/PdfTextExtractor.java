package com.dusk4d.interview.parse.extract;

import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.parse.DocumentText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文本提取（PDFBox 3）。
 *
 * <p>关键处理：
 * <ul>
 *   <li>加密 PDF（无密码）→ 明确提示需要密码，而不是抛出 500；</li>
 *   <li>图片型 PDF（扫描件，无文字层）→ 标记 {@code imageOnly}，
 *       由上层返回「暂不支持图片简历」，绝不生成看起来合理但没有依据的问题；</li>
 *   <li>按页提取并保留换页标记，让页眉页脚在清洗阶段可被重复行检测识别。</li>
 * </ul>
 */
public class PdfTextExtractor implements DocumentTextExtractor {

    /** 平均每页可提取字符数低于该值时判定为图片型 PDF。 */
    private static final int MIN_CHARS_PER_PAGE = 12;
    private static final int MAX_PAGES = 60;

    /**
     * 支持判定：必须命中 {@code %PDF} 文件头。
     *
     * <p>只按扩展名判定会误伤「内容其实是文本、只是被改名成 .pdf」的常见情况
     * （导出工具或用户改名导致），此时应该回落到文本解析，而不是报「PDF 解析失败」。
     * 反过来也不能仅凭扩展名就声称支持——判断必须与 {@code DocumentExtractorRouter.detectType()}
     * 保持一致，否则会出现「识别为 pdf 却解析不了」的错配。
     */
    @Override
    public boolean supports(String fileName, String contentType, byte[] head) {
        return head != null && head.length >= 5
                && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
    }

    @Override
    public DocumentText extract(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw ResumeParseException.emptyText("文件内容为空，无法解析。请确认文件未损坏。");
        }
        List<String> warnings = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                warnings.add("PDF 已加密，提取结果可能不完整。");
            }
            int pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setParagraphEnd("\n");
            stripper.setPageEnd("\n");

            StringBuilder sb = new StringBuilder();
            int limit = Math.min(pages, MAX_PAGES);
            for (int page = 1; page <= limit; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isBlank()) {
                    sb.append(pageText.strip()).append('\n');
                }
            }
            if (pages > MAX_PAGES) {
                warnings.add("PDF 共 " + pages + " 页，仅解析前 " + MAX_PAGES + " 页。");
            }

            String text = sb.toString();
            int effectivePages = Math.max(1, pages);
            boolean imageOnly = text.replaceAll("\\s", "").length() < MIN_CHARS_PER_PAGE * effectivePages;
            if (imageOnly) {
                warnings.add("未提取到文字层，扫描件/图片型 PDF 暂不支持（第一版不做 OCR）。");
            }
            return new DocumentText(text, "pdf", pages, imageOnly, false, warnings);
        } catch (InvalidPasswordException e) {
            return new DocumentText("", "pdf", 0, false, true,
                    List.of("PDF 需要打开密码，无法解析。"));
        } catch (IOException e) {
            throw ResumeParseException.unsupported("PDF 解析失败，文件可能已损坏或被截断：" + e.getMessage());
        } catch (RuntimeException e) {
            throw ResumeParseException.unsupported("PDF 解析异常：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public String type() {
        return "pdf";
    }
}
