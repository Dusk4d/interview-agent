package com.dusk4d.interview.parse.extract;

import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.parse.DocumentText;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本提取（TXT / MD / 无扩展名的兜底输入）。
 *
 * <p>中文简历常见 GBK/GB18030 编码，这里先严格按 UTF-8 解码，失败再回退 GB18030，
 * 避免乱码进入后续事实抽取。
 */
public class PlainTextExtractor implements DocumentTextExtractor {

    private static final Charset GB18030 = Charset.forName("GB18030");

    /**
     * 支持判定：以内容为准，扩展名只作弱提示。
     *
     * <p>原因是求职者常把 txt 改成 pdf、或导出时扩展名与内容不符；
     * 反过来，真正的二进制文件（pdf/docx）会先被更靠前的提取器按文件头命中，
     * 因此这里对「非二进制内容」一律兜底为文本。
     */
    @Override
    public boolean supports(String fileName, String contentType, byte[] head) {
        if (!isBinary(head)) {
            return true;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".text")
                || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    @Override
    public DocumentText extract(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw ResumeParseException.emptyText("文件内容为空，无法解析。请确认文件未损坏。");
        }
        List<String> warnings = new ArrayList<>();
        String text;
        try {
            text = strictDecode(content, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8Failure) {
            try {
                text = strictDecode(content, GB18030);
                warnings.add("文件不是 UTF-8 编码，已按 GB18030 解码。");
            } catch (CharacterCodingException gbkFailure) {
                text = new String(content, StandardCharsets.UTF_8);
                warnings.add("编码识别失败，已按 UTF-8 尽力解码，可能存在乱码。");
            }
        }
        int paragraphs = (int) text.lines().filter(l -> !l.isBlank()).count();
        return new DocumentText(text, "txt", paragraphs, false, false, warnings);
    }

    @Override
    public String type() {
        return "txt";
    }

    private String strictDecode(byte[] content, Charset charset) throws CharacterCodingException {
        CharBuffer decoded = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content));
        return decoded.toString();
    }

    /** 粗略判断是否为二进制（用于无扩展名的兜底判定）。 */
    static boolean isBinary(byte[] head) {
        if (head == null || head.length == 0) {
            return false;
        }
        int check = Math.min(head.length, 512);
        int control = 0;
        int considered = 0;
        for (int i = 0; i < check; i++) {
            int b = head[i] & 0xFF;
            if (b == 0) {
                return true;
            }
            // 只在 ASCII 范围内判定：UTF-8 中文的后续字节落在 0x80-0xBF，
            // 若把它们计入分母，短中文文本会被误判成二进制。
            if (b >= 0x80) {
                continue;
            }
            considered++;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                control++;
            }
        }
        return considered > 0 && control * 10 > considered;
    }
}
