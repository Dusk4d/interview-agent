package com.dusk4d.interview.parse;

/** 简历文本清洗阶段发现的问题，用于给用户明确提示而不是静默继续。 */
public enum CleanIssue {
    /** 清洗后没有可用文本 */
    EMPTY_TEXT,
    /** 文本几乎全是符号/乱码 */
    MOSTLY_NOISE,
    /** 疑似图片型 PDF（无可提取文字） */
    IMAGE_ONLY_PDF,
    /** 检测到乱码（编码问题） */
    ENCODING_GARBLED,
    /** 文本过短，可能解析不完整 */
    TOO_SHORT
}
