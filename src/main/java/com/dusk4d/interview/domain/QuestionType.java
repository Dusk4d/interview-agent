package com.dusk4d.interview.domain;

/** 问题类型。 */
public enum QuestionType {
    /** 项目事实题（做了什么/背景） */
    PROJECT,
    /** 项目技术机制题（怎么实现） */
    PROJECT_TECH,
    /** 项目设计取舍题（为什么这样选） */
    PROJECT_TRADEOFF,
    /** 项目异常边界题 */
    PROJECT_BOUNDARY,
    /** 项目结果验证题 */
    PROJECT_RESULT,
    /** 八股概念题 */
    CONCEPT,
    /** 八股原理题 */
    PRINCIPLE,
    /** 八股应用题 */
    APPLICATION,
    /** 八股边界/误区题 */
    BOUNDARY,
    /** 八股对比题 */
    COMPARISON,
    /** 行为/软技能题（自我介绍等） */
    BEHAVIOR,
    /** 追问 */
    FOLLOW_UP
}
