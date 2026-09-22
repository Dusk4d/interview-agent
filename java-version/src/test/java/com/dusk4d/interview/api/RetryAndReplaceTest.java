package com.dusk4d.interview.api;

import com.dusk4d.interview.agent.AnswerEvaluator;
import com.dusk4d.interview.agent.InterviewStateMachine;
import com.dusk4d.interview.agent.QuestionGenerator;
import com.dusk4d.interview.agent.ReportBuilder;
import com.dusk4d.interview.agent.StructuredOutputParser;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.SessionStatus;
import com.dusk4d.interview.error.InvalidSessionStateException;
import com.dusk4d.interview.llm.LocalHashEmbeddingClient;
import com.dusk4d.interview.llm.MockLlmClient;
import com.dusk4d.interview.parse.ResumeFactExtractor;
import com.dusk4d.interview.parse.TextCleaner;
import com.dusk4d.interview.parse.extract.DocumentExtractorRouter;
import com.dusk4d.interview.privacy.PrivacyMasker;
import com.dusk4d.interview.rag.DocumentRetriever;
import com.dusk4d.interview.rag.InMemoryVectorStore;
import com.dusk4d.interview.rag.KnowledgeBase;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.service.InterviewService;
import com.dusk4d.interview.service.ResumeImportService;
import com.dusk4d.interview.storage.InMemoryStore;
import com.dusk4d.interview.storage.InterviewRepository;
import com.dusk4d.interview.storage.MapBackedInterviewRepository;
import com.dusk4d.interview.support.Fixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「重新回答」与「换一道题」（方案书场景一）。
 *
 * <p>这两件事都会改动历史记录，所以重点不在接口能不能通，而在<b>统计口径不被污染</b>：
 * 重答后旧分数必须消失（否则平均分被同一题算两次），换题后不能白占题量配额、
 * 也不能让模型又出一道一模一样的题。
 */
@DisplayName("重答与换题")
class RetryAndReplaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant FIXED = Instant.parse("2024-06-01T10:00:00Z");

    private ResumeImportService resumeService;
    private InterviewService interviewService;
    private InterviewRepository repository;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Llm("http://127.0.0.1:1/v1", "test", "mock", "mock-embed",
                        0.3, 800, 500, 1000, 0, false, null, null, null),
                new AppProperties.Embedding("local", 128, 8),
                new AppProperties.Retrieval(4, 0.05, 4000, 3),
                // 换题上限设为 2，方便验证到达上限后的拒绝
                new AppProperties.Interview(6, 1, 8, 4000, 2),
                new AppProperties.Privacy(true, false),
                new AppProperties.Storage("memory", ""),
                new AppProperties.Parser(10 * 1024 * 1024));
        VectorStore vectorStore = new InMemoryVectorStore(new LocalHashEmbeddingClient(properties));
        repository = new MapBackedInterviewRepository(
                new InMemoryStore<>(r -> r.id()),
                new InMemoryStore<>(s -> s.id()),
                new InMemoryStore<>(q -> q.id(), Comparator.comparingInt(q -> q.sequence())),
                new InMemoryStore<>(a -> a.id(), Comparator.comparing(a -> a.createdAt())),
                new InMemoryStore<>(e -> e.id(), Comparator.comparing(e -> e.createdAt())),
                new InMemoryStore<>(r -> r.id()));
        KnowledgeBase knowledgeBase = new KnowledgeBase(MAPPER, vectorStore);
        TextCleaner cleaner = new TextCleaner();
        PrivacyMasker masker = new PrivacyMasker();
        DocumentRetriever retriever = new DocumentRetriever(vectorStore, new LocalHashEmbeddingClient(properties),
                properties);

        var llmClient = new MockLlmClient(MAPPER);
        var parser = new StructuredOutputParser(MAPPER);
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

        resumeService = new ResumeImportService(new DocumentExtractorRouter(), cleaner,
                new ResumeFactExtractor(cleaner, masker), masker, repository, vectorStore, properties, clock);
        interviewService = new InterviewService(repository, resumeService, retriever, knowledgeBase,
                new QuestionGenerator(llmClient, parser, properties),
                new AnswerEvaluator(llmClient, parser, properties, clock),
                new ReportBuilder(llmClient, parser, properties, clock),
                properties, clock);
    }

    private String newSession() {
        String resumeId = resumeService.importText(Fixtures.text("resume-standard.txt"), "张伟-简历.txt")
                .resume().id();
        return interviewService.createSession(resumeId, InterviewMode.PROJECT, 4).id();
    }

    // ---------------------------------------------------------------- 重新回答

    @Test
    @DisplayName("重答后旧答案与旧评分都被移除，状态回到等待回答")
    void retryDiscardsPreviousAnswerAndScore() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);
        var answered = interviewService.submitAnswer(sessionId, first.question().id(),
                "用 Redis 加锁保证幂等，细节记不清了。");
        assertThat(answered.session().answerCount()).isEqualTo(1);
        assertThat(answered.session().lastAnswerId()).isNotNull();

        InterviewService.RetryView retry = interviewService.retryAnswer(sessionId);

        assertThat(retry.question().id()).isEqualTo(first.question().id());
        assertThat(retry.discardedScore()).isEqualTo(answered.evaluation().totalScore());
        assertThat(retry.session().status()).isEqualTo(SessionStatus.WAITING_ANSWER);
        assertThat(retry.session().lastAnswerId()).isNull();
        assertThat(retry.session().answerCount()).isZero();
        // 旧记录必须真的从仓储里删掉，而不是只把会话指针清空
        var detail = repository.detail(sessionId).orElseThrow();
        assertThat(detail.answers()).isEmpty();
        assertThat(detail.evaluations()).isEmpty();
    }

    @Test
    @DisplayName("重答后旧反馈条目被替换，报告只统计最新一次作答")
    void retryKeepsStatisticsClean() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);
        // 第一次答得很差
        interviewService.submitAnswer(sessionId, first.question().id(), "嗯，大概是用锁。");
        interviewService.retryAnswer(sessionId);
        // 第二次答得完整
        var second = interviewService.submitAnswer(sessionId, first.question().id(),
                "背景是订单重复提交。我负责幂等设计，通过 Redis 的 SET NX 加锁，value 用请求 ID，"
                        + "解锁用 Lua 比对后再删除，续期由后台线程每 10 秒执行。结果是重复下单为 0。");

        InterviewSession session = interviewService.requireSession(sessionId);
        assertThat(session.answerCount()).isEqualTo(1);
        assertThat(session.recentFeedback()).hasSize(1);
        assertThat(session.recentFeedback().get(0)).contains("第 1 题");

        InterviewReport report = interviewService.generateReport(sessionId);
        assertThat(report.answerCount()).isEqualTo(1);
        assertThat(report.questionCount()).isEqualTo(1);
        // 只应有第二次的分数，且它必须等于重答后的分数
        assertThat(report.overallScore()).isEqualTo(second.evaluation().totalScore());
    }

    @Test
    @DisplayName("还没作答时不能重答；已进入下一题后也不能重答上一题")
    void retryRejectedWhenNotApplicable() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);

        assertThatThrownBy(() -> interviewService.retryAnswer(sessionId))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("还没有提交过回答");

        interviewService.submitAnswer(sessionId, first.question().id(), "用 Redis 加锁保证幂等，细节记不清了。");
        interviewService.nextQuestion(sessionId);

        assertThatThrownBy(() -> interviewService.retryAnswer(sessionId))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("还没有提交过回答");
    }

    @Test
    @DisplayName("结束后的会话不能重答")
    void retryRejectedAfterFinish() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);
        interviewService.submitAnswer(sessionId, first.question().id(), "用 Redis 加锁保证幂等，细节记不清了。");
        interviewService.finish(sessionId, "测试结束");

        assertThatThrownBy(() -> interviewService.retryAnswer(sessionId))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已结束");
    }

    // ---------------------------------------------------------------- 换一道题

    @Test
    @DisplayName("换题后换上新题、不占题量配额、被换的题不进仓储")
    void replaceSwapsQuestionWithoutConsumingBudget() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);
        assertThat(interviewService.requireSession(sessionId).questionCount()).isEqualTo(1);

        var replaced = interviewService.replaceQuestion(sessionId);

        assertThat(replaced.question().id()).isNotEqualTo(first.question().id());
        assertThat(replaced.question().content()).isNotBlank();
        InterviewSession session = interviewService.requireSession(sessionId);
        // 上限本来是 4：换题不消耗配额，所以仍然是「已出 1 题」
        assertThat(session.questionCount()).isEqualTo(1);
        assertThat(session.currentQuestionId()).isEqualTo(replaced.question().id());
        assertThat(repository.detail(sessionId).orElseThrow().questions())
                .extracting(q -> q.id())
                .containsExactly(replaced.question().id());
    }

    @Test
    @DisplayName("换掉的题会留在「已问过」指纹里，新题不会与它重复")
    void replacedQuestionIsNotAskedAgain() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);

        var replaced = interviewService.replaceQuestion(sessionId);

        InterviewSession session = interviewService.requireSession(sessionId);
        assertThat(session.askedQuestionDigests()).hasSize(2);
        assertThat(session.askedQuestionDigests()).doesNotHaveDuplicates();
        assertThat(replaced.question().content()).isNotEqualTo(first.question().content());
    }

    @Test
    @DisplayName("换题次数有上限，超过后明确拒绝")
    void replaceHasSkipLimit() {
        String sessionId = newSession();
        interviewService.nextQuestion(sessionId);
        interviewService.replaceQuestion(sessionId);
        interviewService.replaceQuestion(sessionId);

        assertThatThrownBy(() -> interviewService.replaceQuestion(sessionId))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("换过");
    }

    @Test
    @DisplayName("已经回答过的题不能换（应先重答或进入下一题）")
    void replaceRejectedAfterAnswer() {
        String sessionId = newSession();
        var first = interviewService.nextQuestion(sessionId);
        interviewService.submitAnswer(sessionId, first.question().id(), "用 Redis 加锁保证幂等，细节记不清了。");

        assertThatThrownBy(() -> interviewService.replaceQuestion(sessionId))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("回答过了");
    }

    @Test
    @DisplayName("不变量：已问指纹数 - 题量 = 换题次数（不新增持久化字段的前提）")
    void skipCountIsDerivableFromDigestsAndQuestionCount() {
        String sessionId = newSession();
        interviewService.nextQuestion(sessionId);
        interviewService.replaceQuestion(sessionId);
        interviewService.replaceQuestion(sessionId);

        InterviewSession session = interviewService.requireSession(sessionId);
        int derivedSkips = session.askedQuestionDigests().size() - session.questionCount();
        assertThat(derivedSkips).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 状态机

    @Test
    @DisplayName("状态机守卫：各状态下重答/换题是否允许")
    void stateMachineGuards() {
        assertThat(InterviewStateMachine.canAcceptAnswer(SessionStatus.WAITING_ANSWER)).isTrue();
        // 重答只允许在「刚答完、还没出下一题」时
        InterviewStateMachine.requireRetryAnswer(SessionStatus.NEXT_QUESTION);
        InterviewStateMachine.requireRetryAnswer(SessionStatus.FOLLOW_UP);
        assertThatThrownBy(() -> InterviewStateMachine.requireRetryAnswer(SessionStatus.WAITING_ANSWER))
                .isInstanceOf(InvalidSessionStateException.class);
        assertThatThrownBy(() -> InterviewStateMachine.requireRetryAnswer(SessionStatus.FINISHED))
                .isInstanceOf(InvalidSessionStateException.class);
        // 换题只允许在「已下发、尚未作答」时
        InterviewStateMachine.requireReplaceQuestion(SessionStatus.WAITING_ANSWER);
        assertThatThrownBy(() -> InterviewStateMachine.requireReplaceQuestion(SessionStatus.NEXT_QUESTION))
                .isInstanceOf(InvalidSessionStateException.class);
        assertThatThrownBy(() -> InterviewStateMachine.requireReplaceQuestion(SessionStatus.RESUME_READY))
                .isInstanceOf(InvalidSessionStateException.class);
    }

    @Test
    @DisplayName("完整链路：换题 → 作答 → 重答 → 下一题 → 报告，统计数据自洽")
    void endToEndWithRetryAndReplace() {
        String sessionId = newSession();
        interviewService.nextQuestion(sessionId);
        var swapped = interviewService.replaceQuestion(sessionId);

        interviewService.submitAnswer(sessionId, swapped.question().id(), "大概是用了锁和唯一索引，细节我记不清了。");
        interviewService.retryAnswer(sessionId);
        var finalAnswer = interviewService.submitAnswer(sessionId, swapped.question().id(),
                "背景是重复提交。我负责幂等，通过 Redis SET NX 加锁并用唯一索引兜底，"
                        + "解锁用 Lua 比对 value，续期每 10 秒一次。结果重复下单为 0，慢查询也消失了。");

        interviewService.nextQuestion(sessionId);
        interviewService.finish(sessionId, "测试结束");
        InterviewReport report = interviewService.generateReport(sessionId);

        assertThat(report.answerCount()).isEqualTo(1);
        assertThat(report.questionCount()).isEqualTo(2);
        assertThat(report.overallScore()).isEqualTo(finalAnswer.evaluation().totalScore());
        assertThat(report.markdown()).contains("我的回答").contains("参考结构");
        List<String> feedback = interviewService.requireSession(sessionId).recentFeedback();
        assertThat(feedback).hasSize(1);
    }
}
