package com.dusk4d.interview.storage;

import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;

import java.util.List;
import java.util.Optional;

/**
 * 面试数据访问门面。
 *
 * <p>把「一次面试的全部记录」组合成聚合视图，避免上层服务到处拼装多次查询；
 * 同时把存储实现（内存 / JSON 文件 / 未来的 PostgreSQL）隔离在配置层。
 */
public interface InterviewRepository {

    /** 一次面试的完整记录。 */
    record SessionDetail(
            InterviewSession session,
            List<InterviewQuestion> questions,
            List<InterviewAnswer> answers,
            List<AnswerEvaluation> evaluations
    ) {
        public SessionDetail {
            questions = questions == null ? List.of() : List.copyOf(questions);
            answers = answers == null ? List.of() : List.copyOf(answers);
            evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
        }

        public Optional<InterviewQuestion> question(String questionId) {
            return questions.stream().filter(q -> q.id().equals(questionId)).findFirst();
        }

        public Optional<InterviewAnswer> answerOf(String questionId) {
            return answers.stream().filter(a -> a.questionId().equals(questionId)).findFirst();
        }

        public Optional<AnswerEvaluation> evaluationOf(String answerId) {
            return evaluations.stream().filter(e -> e.answerId().equals(answerId)).findFirst();
        }

        public Optional<AnswerEvaluation> evaluationByQuestion(String questionId) {
            return evaluations.stream().filter(e -> e.questionId().equals(questionId)).findFirst();
        }

        /** 某条回答的全部评分（正常只有一条；重答/多次采样调试时可能不止一条）。 */
        public List<AnswerEvaluation> evaluationsOfAnswer(String answerId) {
            return evaluations.stream().filter(e -> e.answerId().equals(answerId)).toList();
        }
    }

    // ---------------------------------------------------------------- 简历

    Resume saveResume(Resume resume);

    Optional<Resume> findResume(String resumeId);

    List<Resume> listResumes();

    // ---------------------------------------------------------------- 会话

    InterviewSession saveSession(InterviewSession session);

    Optional<InterviewSession> findSession(String sessionId);

    List<InterviewSession> listSessions();

    boolean deleteSession(String sessionId);

    // ---------------------------------------------------------------- 问题/回答/评分

    InterviewQuestion saveQuestion(InterviewQuestion question);

    List<InterviewQuestion> questionsOf(String sessionId);

    /**
     * 删除一道题（「换一道题」用：被换掉的题不再计入报告与题量统计）。
     *
     * @return 是否确实删除了记录
     */
    boolean deleteQuestion(String questionId);

    InterviewAnswer saveAnswer(InterviewAnswer answer);

    List<InterviewAnswer> answersOf(String sessionId);

    /** 删除一条回答（「重新回答」用：旧答案必须移除，否则报告会重复统计同一题）。 */
    boolean deleteAnswer(String answerId);

    AnswerEvaluation saveEvaluation(AnswerEvaluation evaluation);

    List<AnswerEvaluation> evaluationsOf(String sessionId);

    /** 删除一条评分（与 {@link #deleteAnswer} 成对使用，避免旧分数留在报告里）。 */
    boolean deleteEvaluation(String evaluationId);

    // ---------------------------------------------------------------- 报告

    InterviewReport saveReport(InterviewReport report);

    Optional<InterviewReport> findReport(String sessionId);

    // ---------------------------------------------------------------- 聚合

    /** 一次面试的完整记录；会话不存在时返回空。 */
    Optional<SessionDetail> detail(String sessionId);

    void clearAll();

    int sessionCount();

    int resumeCount();
}
