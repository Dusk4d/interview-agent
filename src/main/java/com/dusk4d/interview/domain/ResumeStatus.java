package com.dusk4d.interview.domain;

/** 简历解析状态。 */
public enum ResumeStatus {
    /** 已接收并解析成功 */
    PARSED,
    /** 解析失败，原因见 errorMessage */
    FAILED,
    /** 需要用户确认或修正（例如结构抽取置信度低） */
    NEEDS_REVIEW
}
