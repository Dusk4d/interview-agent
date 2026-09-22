package com.dusk4d.interview.agent;

import com.dusk4d.interview.domain.SessionStatus;
import com.dusk4d.interview.error.InvalidSessionStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("会话状态机 InterviewStateMachine")
class InterviewStateMachineTest {

    @Test
    @DisplayName("正常流程的每一步转移都合法")
    void happyPath() {
        assertThat(InterviewStateMachine.canTransition(SessionStatus.CREATED, SessionStatus.RESUME_READY)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.RESUME_READY, SessionStatus.INTERVIEW_STARTED)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.INTERVIEW_STARTED, SessionStatus.WAITING_ANSWER)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.WAITING_ANSWER, SessionStatus.EVALUATING)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.EVALUATING, SessionStatus.FOLLOW_UP)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.EVALUATING, SessionStatus.NEXT_QUESTION)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.NEXT_QUESTION, SessionStatus.WAITING_ANSWER)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.EVALUATING, SessionStatus.FINISHED)).isTrue();
        assertThat(InterviewStateMachine.canTransition(SessionStatus.FINISHED, SessionStatus.REPORT_READY)).isTrue();
    }

    @Test
    @DisplayName("终态不可再转移到任何状态")
    void terminalStatesAreFinal() {
        for (SessionStatus terminal : new SessionStatus[]{SessionStatus.REPORT_READY, SessionStatus.FAILED,
                SessionStatus.CANCELLED}) {
            for (SessionStatus target : SessionStatus.values()) {
                assertThat(InterviewStateMachine.canTransition(terminal, target))
                        .as("%s -> %s 应被拒绝", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("跳过流程的非法转移被拒绝并给出原因")
    void rejectsIllegalTransitions() {
        assertThatThrownBy(() -> InterviewStateMachine.requireTransition(SessionStatus.CREATED, SessionStatus.FINISHED))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("非法状态转移");
        assertThatThrownBy(() -> InterviewStateMachine.requireTransition(SessionStatus.REPORT_READY, SessionStatus.WAITING_ANSWER))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("只有等待回答与追问状态可以提交回答")
    void answerOnlyInWaitingStates() {
        assertThat(InterviewStateMachine.canAcceptAnswer(SessionStatus.WAITING_ANSWER)).isTrue();
        assertThat(InterviewStateMachine.canAcceptAnswer(SessionStatus.FOLLOW_UP)).isTrue();
        for (SessionStatus other : new SessionStatus[]{SessionStatus.CREATED, SessionStatus.RESUME_READY,
                SessionStatus.INTERVIEW_STARTED, SessionStatus.EVALUATING, SessionStatus.NEXT_QUESTION,
                SessionStatus.FINISHED, SessionStatus.REPORT_READY, SessionStatus.CANCELLED, SessionStatus.FAILED}) {
            assertThat(InterviewStateMachine.canAcceptAnswer(other)).isFalse();
        }
    }

    @Test
    @DisplayName("未开始面试就提交回答给出可操作提示")
    void answerBeforeStartGivesHint() {
        assertThatThrownBy(() -> InterviewStateMachine.requireAcceptAnswer(SessionStatus.CREATED))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("请先获取第一道题");
        assertThatThrownBy(() -> InterviewStateMachine.requireAcceptAnswer(SessionStatus.REPORT_READY))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("结束后不允许继续出题")
    void nextQuestionRejectedWhenFinished() {
        assertThatThrownBy(() -> InterviewStateMachine.requireNextQuestion(SessionStatus.REPORT_READY))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已结束");
        assertThatThrownBy(() -> InterviewStateMachine.requireNextQuestion(SessionStatus.CANCELLED))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("每个状态都有中文说明")
    void describesEveryStatus() {
        for (SessionStatus status : SessionStatus.values()) {
            assertThat(InterviewStateMachine.describe(status)).isNotBlank();
        }
    }
}
