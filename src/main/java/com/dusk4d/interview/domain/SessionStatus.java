package com.dusk4d.interview.domain;

/**
 * 会话状态机。
 *
 * <pre>
 * CREATED -> RESUME_READY -> INTERVIEW_STARTED -> WAITING_ANSWER
 * WAITING_ANSWER -> EVALUATING -> FOLLOW_UP | NEXT_QUESTION
 * NEXT_QUESTION -> WAITING_ANSWER
 * EVALUATING -> FINISHED -> REPORT_READY
 * 任意状态 -> FAILED | CANCELLED
 * </pre>
 *
 * <p>状态只能由后端服务层根据结构化结果推进，模型无权直接修改。
 */
public enum SessionStatus {
    /** 会话已创建，尚未绑定简历 */
    CREATED,
    /** 简历就绪，可以开始面试 */
    RESUME_READY,
    /** 面试已开始，等待出题 */
    INTERVIEW_STARTED,
    /** 已下发问题，等待用户回答 */
    WAITING_ANSWER,
    /** 正在评估回答 */
    EVALUATING,
    /** 评估完成，进入追问分支 */
    FOLLOW_UP,
    /** 评估完成，准备下一题 */
    NEXT_QUESTION,
    /** 面试已结束 */
    FINISHED,
    /** 报告已生成 */
    REPORT_READY,
    /** 会话失败（不可恢复） */
    FAILED,
    /** 用户主动取消 */
    CANCELLED;

    /** 是否终态：终态不允许再次提交回答。 */
    public boolean isTerminal() {
        return this == FINISHED || this == REPORT_READY || this == FAILED || this == CANCELLED;
    }
}
