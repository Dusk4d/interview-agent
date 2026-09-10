package com.dusk4d.interview.service;

import com.dusk4d.interview.agent.AnswerEvaluator;
import com.dusk4d.interview.agent.InterviewStateMachine;
import com.dusk4d.interview.agent.Prompts;
import com.dusk4d.interview.agent.QuestionGenerator;
import com.dusk4d.interview.agent.ReportBuilder;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.InterviewStage;
import com.dusk4d.interview.domain.KnowledgeItem;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.domain.SessionStatus;
import com.dusk4d.interview.error.InvalidSessionStateException;
import com.dusk4d.interview.error.NotFoundException;
import com.dusk4d.interview.error.ValidationException;
import com.dusk4d.interview.rag.DocumentRetriever;
import com.dusk4d.interview.rag.KnowledgeBase;
import com.dusk4d.interview.storage.InterviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 面试编排服务（对应方案书 6.3 的会话与状态管理）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>模型只负责「生成问题」「评估回答」「写总结」，所有状态变更都由本服务执行；</li>
 *   <li>每次外部调用都有次数上限（题目上限、每题追问上限、回答长度上限）；</li>
 *   <li>模型失败时给出可操作的降级结果（模板出题 / 启发式评分），不让页面一直等待。</li>
 * </ul>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private final InterviewRepository repository;
    private final ResumeImportService resumeService;
    private final DocumentRetriever retriever;
    private final KnowledgeBase knowledgeBase;
    private final QuestionGenerator questionGenerator;
    private final AnswerEvaluator answerEvaluator;
    private final ReportBuilder reportBuilder;
    private final AppProperties properties;
    private final Clock clock;

    public InterviewService(InterviewRepository repository,
                            ResumeImportService resumeService,
                            DocumentRetriever retriever,
                            KnowledgeBase knowledgeBase,
                            QuestionGenerator questionGenerator,
                            AnswerEvaluator answerEvaluator,
                            ReportBuilder reportBuilder,
                            AppProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.resumeService = resumeService;
        this.retriever = retriever;
        this.knowledgeBase = knowledgeBase;
        this.questionGenerator = questionGenerator;
        this.answerEvaluator = answerEvaluator;
        this.reportBuilder = reportBuilder;
        this.properties = properties;
        this.clock = clock;
    }

    /** 会话视图：会话 + 当前问题 + 最近一次评估。 */
    public record SessionView(InterviewSession session,
                              InterviewQuestion currentQuestion,
                              AnswerEvaluation latestEvaluation,
                              String stateDescription,
                              List<String> warnings) {
        public SessionView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 出题结果。 */
    public record QuestionView(InterviewQuestion question,
                               boolean degraded,
                               String degradationReason,
                               List<String> citations) {
        public QuestionView {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /** 回答提交结果。 */
    public record AnswerResult(InterviewAnswer answer,
                               AnswerEvaluation evaluation,
                               InterviewSession session,
                               String nextAction,
                               String nextActionHint) {
    }

    // ---------------------------------------------------------------- 会话生命周期

    public InterviewSession createSession(String resumeId, InterviewMode mode) {
        return createSession(resumeId, mode, null);
    }

    /**
     * 创建面试会话。
     *
     * @param resumeId 简历 ID；八股面可以为 null
     * @param mode     面试模式
     * @param maxQuestions 题目上限（可为 null，取配置值）
     */
    public InterviewSession createSession(String resumeId, InterviewMode mode, Integer maxQuestions) {
        if (mode == null) {
            throw new ValidationException("面试模式不能为空，可选 PROJECT / KNOWLEDGE / FULL。");
        }
        Resume resume = null;
        if (resumeId != null && !resumeId.isBlank()) {
            resume = resumeService.require(resumeId);
            if (!resume.usable()) {
                throw new ValidationException("这份简历还没有可用的解析结果，请先修正解析内容。"
                        + (resume.errorMessage() == null ? "" : "（" + resume.errorMessage() + "）"));
            }
        }
        if (mode != InterviewMode.KNOWLEDGE && resume == null) {
            throw new ValidationException("项目面与完整模拟面试需要先导入简历，再进行练习。");
        }
        if (mode == InterviewMode.KNOWLEDGE && knowledgeBase.size() == 0) {
            throw new ValidationException("知识库为空，无法进行八股练习。请检查 knowledge-base.json 配置。");
        }

        int limit = maxQuestions == null ? properties.interview().resolvedMaxQuestions()
                : Math.max(1, Math.min(maxQuestions, 30));
        Instant now = clock.instant();
        // 没有简历（八股面）时会话直接从 INTERVIEW_STARTED 起步，避免出现无意义的 RESUME_READY 中间态
        SessionStatus initialStatus = resume == null ? SessionStatus.INTERVIEW_STARTED : SessionStatus.RESUME_READY;
        InterviewSession session = new InterviewSession(
                UUID.randomUUID().toString(),
                resume == null ? null : resume.id(),
                mode,
                mode == InterviewMode.FULL ? InterviewStage.SELF_INTRO : InterviewStage.SINGLE_QUESTION,
                initialStatus,
                null, null, null,
                0, 0, 0, 0,
                limit,
                List.of(), List.of(), List.of(),
                null,
                now, null, null, now);
        repository.saveSession(session);
        log.info("创建面试会话：id={} mode={} resume={} 题目上限={}", session.id(), mode, resumeId, limit);
        return session;
    }

    public InterviewSession requireSession(String sessionId) {
        return repository.findSession(sessionId)
                .orElseThrow(() -> new NotFoundException("面试会话", sessionId));
    }

    public SessionView view(String sessionId) {
        InterviewSession session = requireSession(sessionId);
        InterviewQuestion current = session.currentQuestionId() == null ? null
                : repository.detail(sessionId).flatMap(d -> d.question(session.currentQuestionId())).orElse(null);
        AnswerEvaluation latest = session.lastAnswerId() == null ? null
                : repository.detail(sessionId).flatMap(d -> d.evaluationOf(session.lastAnswerId())).orElse(null);
        return new SessionView(session, current, latest,
                InterviewStateMachine.describe(session.status()), List.of());
    }

    public InterviewRepository.SessionDetail detail(String sessionId) {
        requireSession(sessionId);
        return repository.detail(sessionId).orElseThrow(() -> new NotFoundException("面试会话", sessionId));
    }

    public List<InterviewSession> listSessions() {
        return repository.listSessions();
    }

    /** 取消会话。 */
    public InterviewSession cancel(String sessionId) {
        InterviewSession session = requireSession(sessionId);
        InterviewStateMachine.requireTransition(session.status(), SessionStatus.CANCELLED);
        return repository.saveSession(withStatus(session, SessionStatus.CANCELLED, "用户主动取消", clock.instant()));
    }

    // ---------------------------------------------------------------- 出题

    /** 获取下一道题。 */
    public QuestionView nextQuestion(String sessionId) {
        InterviewSession session = requireSession(sessionId);
        InterviewStateMachine.requireNextQuestion(session.status());
        if (session.questionBudgetExhausted()) {
            // 达到题目上限：自动结束，避免用户点不动
            InterviewSession finished = finish(sessionId, "达到题目数量上限");
            throw new InvalidSessionStateException(
                    "已达到本场题目上限（" + finished.questionCount() + " 题），面试已结束。请查看复盘报告或新建一场面试。");
        }

        InterviewStage stage = resolveStage(session);
        Difficulty difficulty = resolveDifficulty(session);
        Resume resume = session.resumeId() == null ? null : resumeService.require(session.resumeId());

        TopicPick topicPick = pickTopic(session, resume, stage);
        QuestionGenerator.QuestionContext context = buildContext(session, resume, topicPick);

        QuestionGenerator.GeneratedQuestion generated = questionGenerator.generate(
                session, session.mode(), stage, difficulty, context, topicPick.requestedType());

        Instant now = clock.instant();
        InterviewQuestion question = new InterviewQuestion(
                UUID.randomUUID().toString(),
                session.id(),
                session.questionCount() + 1,
                generated.plan().type(),
                generated.plan().difficulty(),
                generated.plan().content(),
                generated.plan().intent(),
                generated.plan().focus(),
                generated.plan().sourceIds(),
                context.citations(),
                generated.plan().followUpPlan(),
                null,
                false,
                stage.name(),
                now);
        repository.saveQuestion(question);

        Set<String> covered = new LinkedHashSet<>(session.coveredTopics());
        if (topicPick.topic() != null) {
            covered.add(topicPick.topic());
        }
        InterviewSession updated = new InterviewSession(
                session.id(), session.resumeId(), session.mode(),
                stage, SessionStatus.WAITING_ANSWER,
                question.id(), session.lastAnswerId(), topicPick.projectName(),
                session.questionCount() + 1, session.answerCount(), session.followUpCount(), 0,
                session.maxQuestions(), new ArrayList<>(covered), session.recentFeedback(),
                append(session.askedQuestionDigests(), digest(question.content())),
                session.endReason(), session.createdAt(),
                session.startedAt() == null ? now : session.startedAt(), session.endedAt(), now);
        repository.saveSession(updated);

        log.info("下发第 {} 题：type={} degraded={} sources={}", question.sequence(), question.type(),
                generated.degraded(), question.sourceIds().size());
        return new QuestionView(question, generated.degraded(), generated.degradationReason(), context.citations());
    }

    /** 请求一次追问（需先提交回答且评估建议追问）。 */
    public QuestionView followUp(String sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (session.status() != SessionStatus.FOLLOW_UP) {
            throw new InvalidSessionStateException(
                    session.status() == SessionStatus.WAITING_ANSWER
                            ? "请先回答问题，再请求追问。"
                            : "当前状态不支持追问：" + InterviewStateMachine.describe(session.status()));
        }
        int limit = properties.interview().resolvedFollowUpLimit();
        if (session.followUpUsedOnCurrent() >= limit) {
            throw new InvalidSessionStateException("本题已经追问过 " + limit + " 次，请进入下一题。");
        }
        InterviewQuestion parent = repository.detail(sessionId)
                .flatMap(d -> d.question(session.currentQuestionId()))
                .orElseThrow(() -> new InvalidSessionStateException("当前没有可追问的问题，请先获取题目。"));
        AnswerEvaluation evaluation = session.lastAnswerId() == null ? null
                : repository.detail(sessionId).flatMap(d -> d.evaluationOf(session.lastAnswerId())).orElse(null);
        InterviewAnswer lastAnswer = session.lastAnswerId() == null ? null
                : repository.detail(sessionId).flatMap(d -> d.answers().stream()
                        .filter(a -> a.id().equals(session.lastAnswerId())).findFirst()).orElse(null);

        Resume resume = session.resumeId() == null ? null : resumeService.require(session.resumeId());
        QuestionGenerator.QuestionContext context = buildContext(session, resume,
                new TopicPick(session.activeProject(), null, null, parent.type(), null));

        String missing = evaluation == null ? "" : String.join("；", evaluation.missingPoints());
        String focus = evaluation == null ? "" : evaluation.followUpFocus();
        QuestionGenerator.GeneratedQuestion generated = questionGenerator.generateFollowUp(
                parent, lastAnswer == null ? "" : lastAnswer.content(), missing, focus, context);

        Instant now = clock.instant();
        InterviewQuestion question = new InterviewQuestion(
                UUID.randomUUID().toString(),
                session.id(),
                session.questionCount() + 1,
                QuestionType.FOLLOW_UP,
                generated.plan().difficulty(),
                generated.plan().content(),
                generated.plan().intent(),
                generated.plan().focus(),
                generated.plan().sourceIds(),
                context.citations(),
                generated.plan().followUpPlan(),
                parent.id(),
                true,
                session.stage().name(),
                now);
        repository.saveQuestion(question);

        InterviewSession updated = new InterviewSession(
                session.id(), session.resumeId(), session.mode(), session.stage(),
                SessionStatus.WAITING_ANSWER,
                question.id(), session.lastAnswerId(), session.activeProject(),
                session.questionCount() + 1, session.answerCount(),
                session.followUpCount() + 1, session.followUpUsedOnCurrent() + 1,
                session.maxQuestions(), session.coveredTopics(), session.recentFeedback(),
                append(session.askedQuestionDigests(), digest(question.content())),
                session.endReason(), session.createdAt(), session.startedAt(), session.endedAt(), now);
        repository.saveSession(updated);

        return new QuestionView(question, generated.degraded(), generated.degradationReason(), context.citations());
    }

    // ---------------------------------------------------------------- 回答与评估

    /** 提交回答并评估。 */
    public AnswerResult submitAnswer(String sessionId, String questionId, String content) {
        InterviewSession session = requireSession(sessionId);
        InterviewStateMachine.requireAcceptAnswer(session.status());

        String trimmed = content == null ? "" : content.strip();
        int maxAnswer = properties.interview().maxAnswerChars();
        if (trimmed.length() > maxAnswer) {
            throw new ValidationException("回答过长（" + trimmed.length() + " 字），请精简到 " + maxAnswer + " 字以内。");
        }
        if (trimmed.isEmpty()) {
            throw new ValidationException("回答内容不能为空。如果暂时不会，可以写「这题我没准备过」，系统会给出思路提示。");
        }
        int minAnswer = properties.interview().minAnswerChars();
        if (trimmed.length() < minAnswer) {
            throw new ValidationException("回答过短（少于 " + minAnswer + " 字），请把思路写清楚一些再提交。");
        }

        String targetQuestionId = questionId == null || questionId.isBlank()
                ? session.currentQuestionId() : questionId;
        if (targetQuestionId == null) {
            throw new InvalidSessionStateException("当前没有待回答的问题，请先获取题目。");
        }
        InterviewRepository.SessionDetail detail = repository.detail(sessionId)
                .orElseThrow(() -> new NotFoundException("面试会话", sessionId));
        InterviewQuestion question = detail.question(targetQuestionId)
                .orElseThrow(() -> new NotFoundException("面试问题", targetQuestionId));
        if (detail.answerOf(targetQuestionId).isPresent()) {
            throw new InvalidSessionStateException("这道题已经提交过回答，请获取下一题或请求追问。");
        }

        Instant now = clock.instant();
        InterviewAnswer answer = InterviewAnswer.of(UUID.randomUUID().toString(), question, trimmed, now);
        repository.saveAnswer(answer);

        Resume resume = session.resumeId() == null ? null : resumeService.require(session.resumeId());
        QuestionGenerator.QuestionContext context = buildContext(session, resume,
                new TopicPick(session.activeProject(), null, null, question.type(), null));
        List<ResumeFact> facts = resume == null ? List.of() : resume.facts();

        AnswerEvaluation evaluation = answerEvaluator.evaluate(question, answer.trimmedContent(),
                context.context(), facts);
        // 评估器返回的 answerId 必须与已保存的回答一致，否则前端查不到反馈
        evaluation = new AnswerEvaluation(evaluation.id(), answer.id(), question.id(), session.id(),
                evaluation.totalScore(), evaluation.dimensionScores(), evaluation.strengths(),
                evaluation.missingPoints(), evaluation.corrections(), evaluation.suggestedAdditions(),
                evaluation.referenceAnswerStructure(), evaluation.evidenceWarnings(),
                evaluation.followUpRecommended(), evaluation.followUpFocus(), evaluation.summary(),
                evaluation.degraded(), evaluation.rawModelOutput(), evaluation.createdAt());
        repository.saveEvaluation(evaluation);

        int followUpLimit = properties.interview().resolvedFollowUpLimit();
        boolean canFollowUp = !question.followUp() && evaluation.followUpRecommended()
                && session.followUpUsedOnCurrent() < followUpLimit
                && !session.questionBudgetExhausted();
        SessionStatus nextStatus = canFollowUp ? SessionStatus.FOLLOW_UP : SessionStatus.NEXT_QUESTION;

        // 追加最近反馈摘要（压缩历史，避免上下文线性膨胀）
        List<String> feedback = new ArrayList<>(session.recentFeedback());
        feedback.add(String.format(Locale.ROOT, "第 %d 题 %.1f 分：%s", question.sequence(), evaluation.totalScore(),
                shorten(evaluation.summary(), 60)));
        if (feedback.size() > 8) {
            feedback = new ArrayList<>(feedback.subList(feedback.size() - 8, feedback.size()));
        }

        InterviewSession updated = new InterviewSession(
                session.id(), session.resumeId(), session.mode(), session.stage(), nextStatus,
                question.id(), answer.id(), session.activeProject(),
                session.questionCount(), session.answerCount() + 1,
                session.followUpCount(), session.followUpUsedOnCurrent(),
                session.maxQuestions(), session.coveredTopics(), feedback, session.askedQuestionDigests(),
                session.endReason(), session.createdAt(), session.startedAt(), session.endedAt(), now);
        repository.saveSession(updated);

        boolean budgetExhausted = updated.questionBudgetExhausted() && !canFollowUp;
        String nextAction;
        String hint;
        if (canFollowUp) {
            nextAction = "FOLLOW_UP";
            hint = "评估建议继续追问，可以点击「继续追问」，也可以直接进入下一题。";
        } else if (budgetExhausted) {
            nextAction = "FINISH";
            hint = "已完成本场全部题目，可以结束面试并生成复盘报告。";
        } else {
            nextAction = "NEXT_QUESTION";
            hint = "可以进入下一题，或先结束面试查看报告。";
        }
        return new AnswerResult(answer, evaluation, updated, nextAction, hint);
    }

    /** 查询最近一次评估（未提交过回答时返回空）。 */
    public Optional<AnswerEvaluation> latestEvaluation(String sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (session.lastAnswerId() == null) {
            return Optional.empty();
        }
        return repository.detail(sessionId).flatMap(d -> d.evaluationOf(session.lastAnswerId()));
    }

    public Optional<AnswerEvaluation> evaluationOfAnswer(String sessionId, String answerId) {
        requireSession(sessionId);
        return repository.detail(sessionId).flatMap(d -> d.evaluationOf(answerId));
    }

    // ---------------------------------------------------------------- 结束与报告

    /** 结束面试（可重复调用：已完成时直接返回当前会话）。 */
    public InterviewSession finish(String sessionId, String reason) {
        InterviewSession session = requireSession(sessionId);
        if (session.status() == SessionStatus.FINISHED || session.status() == SessionStatus.REPORT_READY) {
            return session;
        }
        InterviewStateMachine.requireFinish(session.status());
        InterviewStateMachine.requireTransition(session.status(), SessionStatus.FINISHED);
        Instant now = clock.instant();
        InterviewSession finished = new InterviewSession(
                session.id(), session.resumeId(), session.mode(), session.stage(), SessionStatus.FINISHED,
                session.currentQuestionId(), session.lastAnswerId(), session.activeProject(),
                session.questionCount(), session.answerCount(), session.followUpCount(),
                session.followUpUsedOnCurrent(), session.maxQuestions(), session.coveredTopics(),
                session.recentFeedback(), session.askedQuestionDigests(),
                reason == null || reason.isBlank() ? "用户主动结束" : reason,
                session.createdAt(), session.startedAt() == null ? now : session.startedAt(), now, now);
        repository.saveSession(finished);
        log.info("结束面试：id={} 原因={} 题目={} 回答={}", sessionId, finished.endReason(),
                finished.questionCount(), finished.answerCount());
        return finished;
    }

    /** 生成（或重新生成）复盘报告。 */
    public InterviewReport generateReport(String sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (session.status() != SessionStatus.FINISHED && session.status() != SessionStatus.REPORT_READY) {
            session = finish(sessionId, "生成报告前自动结束");
        }
        InterviewRepository.SessionDetail detail = repository.detail(sessionId)
                .orElseThrow(() -> new NotFoundException("面试会话", sessionId));
        InterviewReport report = reportBuilder.build(session, detail.questions(), detail.answers(),
                detail.evaluations());
        repository.saveReport(report);
        repository.saveSession(withStatus(session, SessionStatus.REPORT_READY, session.endReason(), clock.instant()));
        return report;
    }

    public InterviewReport requireReport(String sessionId) {
        requireSession(sessionId);
        return repository.findReport(sessionId)
                .orElseThrow(() -> new NotFoundException("面试报告（请先调用 finish/report 生成）", sessionId));
    }

    public Optional<InterviewReport> findReport(String sessionId) {
        return repository.findReport(sessionId);
    }

    // ---------------------------------------------------------------- 选题与上下文

    private record TopicPick(String projectName, String topic, Difficulty difficulty,
                             QuestionType requestedType, String focusHint) {
    }

    /** 依据模式、阶段与已覆盖主题挑选本次出题方向。 */
    private TopicPick pickTopic(InterviewSession session, Resume resume, InterviewStage stage) {
        Set<String> covered = new LinkedHashSet<>(session.coveredTopics());
        if (session.mode() == InterviewMode.KNOWLEDGE) {
            List<KnowledgeItem> candidates = knowledgeBase.all().stream()
                    .filter(item -> !covered.contains(topicKey(item)))
                    .toList();
            if (candidates.isEmpty()) {
                candidates = knowledgeBase.all();
            }
            KnowledgeItem picked = candidates.get(session.questionCount() % Math.max(1, candidates.size()));
            return new TopicPick(null, topicKey(picked), picked.difficulty(), null, picked.title());
        }

        List<ResumeFact> projects = resume == null ? List.of() : resume.factsOf(FactType.PROJECT);
        if (projects.isEmpty()) {
            return new TopicPick(null, null, null, null, null);
        }
        Optional<ResumeFact> uncovered = projects.stream()
                .filter(project -> !covered.contains(project.label()))
                .findFirst();
        ResumeFact picked = uncovered.orElseGet(() -> projects.get(session.questionCount() % projects.size()));
        return new TopicPick(picked.label(), picked.label(), null, null, picked.label());
    }

    private String topicKey(KnowledgeItem item) {
        return item.topic() + "/" + (item.subtopic() == null ? item.title() : item.subtopic());
    }

    /** 构造检索上下文：项目面检索个人事实，八股面检索知识库，完整面试按阶段混合检索。 */
    private QuestionGenerator.QuestionContext buildContext(InterviewSession session, Resume resume, TopicPick pick) {
        String query = buildQuery(session, pick);
        DocumentRetriever.RetrievalResult result;
        try {
            result = switch (session.mode()) {
                case PROJECT -> retriever.retrieveFacts(session.resumeId(), query);
                case KNOWLEDGE -> retriever.retrieveKnowledge(query);
                case FULL -> isFundamentalsOrIntro(session.stage())
                        ? retriever.retrieveMixed(session.resumeId(), query)
                        : retriever.retrieveFacts(session.resumeId(), query);
            };
        } catch (RuntimeException e) {
            log.warn("检索失败，降级为空上下文：{}", e.getMessage());
            result = DocumentRetriever.RetrievalResult.none();
        }
        if (result.noHits() && resume != null) {
            // 兜底：向量检索为空时，用简历事实原文构造上下文（保证「有依据」而不是编造）
            result = fallbackContextFromFacts(resume, pick);
        }
        return new QuestionGenerator.QuestionContext(result.context(), result.chunkIds(), result.citations(),
                pick.projectName());
    }

    /**
     * 检索为空时的兜底：直接用简历事实原文构造上下文。
     *
     * <p>关键点：兜底路径同样要产出 {@link com.dusk4d.interview.rag.ScoredChunk}，
     * 而不是只拼一段文本——否则 {@code sourceIds} 会是空的，问题就失去了溯源信息
     * （前端无法核对「这条依据来自哪段简历」）。这是一个曾经出现过的缺陷，已在
     * {@code acceptance7_failurePaths} 中加了回归断言。
     */
    private DocumentRetriever.RetrievalResult fallbackContextFromFacts(Resume resume, TopicPick pick) {
        List<ResumeFact> facts;
        if (pick.projectName() != null) {
            facts = resume.facts().stream()
                    .filter(f -> pick.projectName().equals(f.label()) || f.type() == FactType.PROJECT)
                    .toList();
        } else {
            facts = resume.facts().stream()
                    .filter(f -> f.type() == FactType.PROJECT || f.type() == FactType.SKILL
                            || f.type() == FactType.INTERNSHIP)
                    .toList();
        }
        if (facts.isEmpty()) {
            facts = resume.facts();
        }
        if (facts.isEmpty()) {
            return DocumentRetriever.RetrievalResult.none();
        }

        int limit = properties.retrieval().maxContextChars();
        List<com.dusk4d.interview.rag.ScoredChunk> chunks = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (ResumeFact fact : facts) {
            String block = "[" + index + "] 简历片段：" + fact.label() + "\n" + fact.content() + "\n";
            if (context.length() + block.length() > limit) {
                break;
            }
            context.append(block);
            com.dusk4d.interview.rag.TextChunk chunk = com.dusk4d.interview.rag.TextChunk.resumeFact(
                    fact.id(), resume.id(), fact.type(), fact.label(),
                    fact.type() == FactType.PROJECT ? fact.label() : null,
                    fact.content(), fact.sourceOrder(), fact.metadata());
            chunks.add(new com.dusk4d.interview.rag.ScoredChunk(chunk, 0d, index, 0, 0d));
            index++;
        }
        if (chunks.isEmpty()) {
            return DocumentRetriever.RetrievalResult.none();
        }
        List<String> citations = chunks.stream().map(com.dusk4d.interview.rag.ScoredChunk::citation).toList();
        return new DocumentRetriever.RetrievalResult(chunks, citations, context.toString().strip(), false);
    }

    private boolean isFundamentalsOrIntro(InterviewStage stage) {
        return stage == InterviewStage.FUNDAMENTALS || stage == InterviewStage.SELF_INTRO
                || stage == InterviewStage.CANDIDATE_QUESTIONS;
    }

    private String buildQuery(InterviewSession session, TopicPick pick) {
        StringBuilder sb = new StringBuilder();
        sb.append(Prompts.modeLabel(session.mode())).append(' ');
        if (pick.focusHint() != null) {
            sb.append(pick.focusHint()).append(' ');
        }
        if (pick.projectName() != null) {
            sb.append(pick.projectName()).append(' ');
        }
        if (pick.topic() != null) {
            sb.append(pick.topic()).append(' ');
        }
        if (!session.recentFeedback().isEmpty()) {
            sb.append(session.recentFeedback().get(session.recentFeedback().size() - 1));
        }
        return sb.toString().strip();
    }

    /** 完整模拟面试的阶段推进：按题目配额分配到各阶段。 */
    private InterviewStage resolveStage(InterviewSession session) {
        if (session.mode() != InterviewMode.FULL) {
            return InterviewStage.SINGLE_QUESTION;
        }
        int max = session.maxQuestions();
        int index = session.questionCount();
        int introQuota = 1;
        int projectQuota = Math.max(1, (int) Math.round(max * 0.3));
        int deepDiveQuota = Math.max(1, (int) Math.round(max * 0.25));
        int fundamentalsQuota = Math.max(1, (int) Math.round(max * 0.3));
        int total = introQuota + projectQuota + deepDiveQuota + fundamentalsQuota;
        int questionsQuota = Math.max(1, max - total);

        if (index < introQuota) {
            return InterviewStage.SELF_INTRO;
        }
        if (index < introQuota + projectQuota) {
            return InterviewStage.PROJECT;
        }
        if (index < introQuota + projectQuota + deepDiveQuota) {
            return InterviewStage.PROJECT_DEEP_DIVE;
        }
        if (index < introQuota + projectQuota + deepDiveQuota + fundamentalsQuota) {
            return InterviewStage.FUNDAMENTALS;
        }
        if (index < introQuota + projectQuota + deepDiveQuota + fundamentalsQuota + questionsQuota) {
            return InterviewStage.CANDIDATE_QUESTIONS;
        }
        return InterviewStage.WRAP_UP;
    }

    private Difficulty resolveDifficulty(InterviewSession session) {
        int index = session.questionCount();
        if (index < 2) {
            return Difficulty.EASY;
        }
        if (index < Math.max(3, session.maxQuestions() - 2)) {
            return Difficulty.MEDIUM;
        }
        return Difficulty.HARD;
    }

    private List<String> append(List<String> source, String value) {
        List<String> result = new ArrayList<>(source);
        result.add(value);
        if (result.size() > 20) {
            result = new ArrayList<>(result.subList(result.size() - 20, result.size()));
        }
        return result;
    }

    private String digest(String question) {
        return shorten(question, 60);
    }

    private InterviewSession withStatus(InterviewSession session, SessionStatus status, String reason, Instant now) {
        return new InterviewSession(session.id(), session.resumeId(), session.mode(), session.stage(), status,
                session.currentQuestionId(), session.lastAnswerId(), session.activeProject(),
                session.questionCount(), session.answerCount(), session.followUpCount(),
                session.followUpUsedOnCurrent(), session.maxQuestions(), session.coveredTopics(),
                session.recentFeedback(), session.askedQuestionDigests(),
                reason == null ? session.endReason() : reason,
                session.createdAt(), session.startedAt(), session.endedAt() == null ? now : session.endedAt(), now);
    }

    private String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }
}
