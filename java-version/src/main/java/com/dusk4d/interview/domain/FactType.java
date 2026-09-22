package com.dusk4d.interview.domain;

/**
 * 简历事实/检索片段的类型。
 *
 * <p>用于区分「个人事实库」与「基础知识库」，也用于面试模式检索过滤：
 * 项目面只检索个人事实，八股面只检索基础知识，混合面试同时检索但必须在提示中标注来源。
 */
public enum FactType {
    /** 教育经历 */
    EDUCATION,
    /** 实习/工作经历 */
    INTERNSHIP,
    /** 项目经历 */
    PROJECT,
    /** 技术栈/技能 */
    SKILL,
    /** 奖项荣誉 */
    AWARD,
    /** 自我介绍/个人信息 */
    SUMMARY,
    /** 无法归类的内容（兜底，保留原始片段） */
    OTHER,
    /** 基础知识库条目 */
    KNOWLEDGE
}
