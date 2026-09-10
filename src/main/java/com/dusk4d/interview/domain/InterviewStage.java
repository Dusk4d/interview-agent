package com.dusk4d.interview.domain;

/** 面试阶段（完整模拟面试的推进单元）。 */
public enum InterviewStage {
    /** 开场自我介绍 */
    SELF_INTRO,
    /** 项目经历陈述 */
    PROJECT,
    /** 项目深挖追问 */
    PROJECT_DEEP_DIVE,
    /** 计算机基础知识 */
    FUNDAMENTALS,
    /** 反问环节准备 */
    CANDIDATE_QUESTIONS,
    /** 总结复盘 */
    WRAP_UP,
    /** 单题练习模式的默认阶段 */
    SINGLE_QUESTION
}
