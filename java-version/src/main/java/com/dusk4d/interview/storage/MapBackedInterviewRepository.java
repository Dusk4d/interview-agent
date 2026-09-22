package com.dusk4d.interview.storage;

import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于 {@link Store} 的仓储实现。
 *
 * <p>各实体的排序规则在构造时固定（会话按创建时间倒序、问题按序号、回答按创建时间），
 * 保证同一份数据在任何存储实现下展示顺序一致。
 */
public class MapBackedInterviewRepository implements InterviewRepository {

    private final Store<Resume> resumes;
    private final Store<InterviewSession> sessions;
    private final Store<InterviewQuestion> questions;
    private final Store<InterviewAnswer> answers;
    private final Store<AnswerEvaluation> evaluations;
    private final Store<InterviewReport> reports;

    public MapBackedInterviewRepository(Store<Resume> resumes,
                                        Store<InterviewSession> sessions,
                                        Store<InterviewQuestion> questions,
                                        Store<InterviewAnswer> answers,
                                        Store<AnswerEvaluation> evaluations,
                                        Store<InterviewReport> reports) {
        this.resumes = resumes;
        this.sessions = sessions;
        this.questions = questions;
        this.answers = answers;
        this.evaluations = evaluations;
        this.reports = reports;
    }

    @Override
    public Resume saveResume(Resume resume) {
        return resumes.save(resume);
    }

    @Override
    public Optional<Resume> findResume(String resumeId) {
        return resumes.findById(resumeId);
    }

    @Override
    public List<Resume> listResumes() {
        return resumes.findAll().stream()
                .sorted(Comparator.comparing(Resume::createdAt).reversed())
                .toList();
    }

    @Override
    public InterviewSession saveSession(InterviewSession session) {
        return sessions.save(session);
    }

    @Override
    public Optional<InterviewSession> findSession(String sessionId) {
        return sessions.findById(sessionId);
    }

    @Override
    public List<InterviewSession> listSessions() {
        return sessions.findAll().stream()
                .sorted(Comparator.comparing(InterviewSession::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean deleteSession(String sessionId) {
        if (sessionId == null || sessions.findById(sessionId).isEmpty()) {
            return false;
        }
        questionsOf(sessionId).forEach(q -> questions.deleteById(q.id()));
        answersOf(sessionId).forEach(a -> answers.deleteById(a.id()));
        evaluationsOf(sessionId).forEach(e -> evaluations.deleteById(e.id()));
        reports.findById(sessionId).ifPresent(r -> reports.deleteById(r.id()));
        return sessions.deleteById(sessionId);
    }

    @Override
    public InterviewQuestion saveQuestion(InterviewQuestion question) {
        return questions.save(question);
    }

    @Override
    public List<InterviewQuestion> questionsOf(String sessionId) {
        return questions.findAll().stream()
                .filter(q -> q.sessionId().equals(sessionId))
                .sorted(Comparator.comparingInt(InterviewQuestion::sequence))
                .toList();
    }

    @Override
    public boolean deleteQuestion(String questionId) {
        return questions.deleteById(questionId);
    }

    @Override
    public InterviewAnswer saveAnswer(InterviewAnswer answer) {
        return answers.save(answer);
    }

    @Override
    public boolean deleteAnswer(String answerId) {
        return answers.deleteById(answerId);
    }

    @Override
    public List<InterviewAnswer> answersOf(String sessionId) {
        return answers.findAll().stream()
                .filter(a -> a.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(InterviewAnswer::createdAt))
                .toList();
    }

    @Override
    public AnswerEvaluation saveEvaluation(AnswerEvaluation evaluation) {
        return evaluations.save(evaluation);
    }

    @Override
    public boolean deleteEvaluation(String evaluationId) {
        return evaluations.deleteById(evaluationId);
    }

    @Override
    public List<AnswerEvaluation> evaluationsOf(String sessionId) {
        return evaluations.findAll().stream()
                .filter(e -> e.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(AnswerEvaluation::createdAt))
                .toList();
    }

    @Override
    public InterviewReport saveReport(InterviewReport report) {
        return reports.save(report);
    }

    @Override
    public Optional<InterviewReport> findReport(String sessionId) {
        return reports.findAll().stream()
                .filter(r -> r.sessionId().equals(sessionId))
                .findFirst();
    }

    @Override
    public Optional<SessionDetail> detail(String sessionId) {
        return findSession(sessionId).map(session -> new SessionDetail(
                session,
                questionsOf(sessionId),
                answersOf(sessionId),
                evaluationsOf(sessionId)));
    }

    @Override
    public void clearAll() {
        resumes.clear();
        sessions.clear();
        questions.clear();
        answers.clear();
        evaluations.clear();
        reports.clear();
    }

    @Override
    public int sessionCount() {
        return sessions.count();
    }

    @Override
    public int resumeCount() {
        return resumes.count();
    }
}
