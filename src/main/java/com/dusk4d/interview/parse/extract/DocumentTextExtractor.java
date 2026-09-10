package com.dusk4d.interview.parse.extract;

import com.dusk4d.interview.parse.DocumentText;

/**
 * 单一格式的文本提取器。
 *
 * <p>扩展新格式（如 Markdown、HTML）只需新增实现并注册为 Bean，
 * {@code DocumentExtractorRouter} 会按 {@link #supports} 自动路由。
 */
public interface DocumentTextExtractor {

    /** 是否支持该格式；fileName 可能为 null（纯文本兜底上传）。 */
    boolean supports(String fileName, String contentType, byte[] head);

    /** 执行提取。 */
    DocumentText extract(String fileName, byte[] content);

    /** 类型标识：pdf / docx / txt。 */
    String type();
}
