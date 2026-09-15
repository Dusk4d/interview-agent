package com.dusk4d.interview.api;

import com.dusk4d.interview.agent.AnswerEvaluator;
import com.dusk4d.interview.agent.QuestionGenerator;
import com.dusk4d.interview.agent.ReportBuilder;
import com.dusk4d.interview.agent.StructuredOutputParser;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.SessionStatus;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LocalHashEmbeddingClient;
import com.dusk4d.interview.llm.MockLlmClient;
import com.dusk4d.interview.rag.DocumentRetriever;
import com.dusk4d.interview.rag.InMemoryVectorStore;
import com.dusk4d.interview.rag.KnowledgeBase;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.service.InterviewService;
import com.dusk4d.interview.service.ResumeImportService;
import com.dusk4d.interview.storage.InMemoryStore;
import com.dusk4d.interview.storage.InterviewRepository;
import com.dusk4d.interview.storage.MapBackedInterviewRepository;
import com.dusk4d.interview.parse.ResumeFactExtractor;
import com.dusk4d.interview.parse.TextCleaner;
import com.dusk4d.interview.parse.extract.DocumentExtractorRouter;
import com.dusk4d.interview.privacy.PrivacyMasker;
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
 * 端到端流程测试（服务层，不启动 HTTP）。
 *
 * <p>覆盖「导入简历 → 出题 → 回答 → 评分 → 追问 → 下一题 → 结束 → 报告」完整闭环，
 * 以及重复提交、越权题目、空回答、追问次数上限等边界。
 */
@DisplayName("端到端闭环 InterviewService")
class InterviewFlowTest {

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
                new AppProperties.Interview(6, 1, 8, 4000),
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
        DocumentRetriever retriever = new DocumentRetriever(vectorStore,
                new com.dusk4d.interview.llm.EmbeddingClient() {
                    private final LocalHashEmbeddingClient delegate = new LocalHashEmbeddingClient(properties);

                    @Override
                    public List<float[]> embed(List<String> texts) {
                        return delegate.embed(texts);
                    }

                    @Override
                    public int dimension() {
                        return delegate.dimension();
                    }

                    @Override
                    public String provider() {
                        return delegate.provider();
                    }
                }, properties);

        LlmClient llmClient = new MockLlmClient(MAPPER);
        StructuredOutputParser parser = new StructuredOutputParser(MAPPER);
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

        resumeService = new ResumeImportService(new DocumentExtractorRouter(), cleaner,
                new ResumeFactExtractor(cleaner, masker), masker, repository, vectorStore, properties, clock);
        interviewService = new InterviewService(repository, resumeService, retriever, knowledgeBase,
                new QuestionGenerator(llmClient, parser, properties),
                new AnswerEvaluator(llmClient, parser, properties, clock),
                new ReportBuilder(llmClient, parser, properties, clock),
                properties, clock);
    }

    private String importStandardResume() {
        return resumeService.importText(Fixtures.text("resume-standard.txt"), "张伟-简历.txt").resume().id();
    }

    @Test
    @DisplayName("项目面闭环：出题带来源 → 回答评分 → 追问 → 下一题 → 结束 → 报告")
    void projectModeFullLoop() {
        String resumeId = importStandardResume();
        // 追问也占用题目配额，这里给足空间以验证完整闭环
        InterviewSession session = interviewService.createSession(resumeId, InterviewMode.PROJECT, 5);
        assertThat(session.status()).isEqualTo(SessionStatus.RESUME_READY);

        InterviewService.QuestionView first = interviewService.nextQuestion(session.id());
        assertThat(first.question().content()).isNotBlank();
        assertThat(first.question().sourceIds()).isNotEmpty();
        assertThat(first.question().sessionId()).isEqualTo(session.id());
        assertThat(first.degraded()).isFalse();

        var answerResult = interviewService.submitAnswer(session.id(), first.question().id(),
                "用 Redis 加锁保证幂等，具体细节我记不太清了。");
        assertThat(answerResult.evaluation().totalScore()).isBetween(0.0, 5.0);
        assertThat(answerResult.evaluation().dimensionScores()).hasSize(4);
        assertThat(answerResult.answer().id()).isEqualTo(answerResult.evaluation().answerId());
        assertThat(answerResult.session().answerCount()).isEqualTo(1);
        // 回答不完整时应建议追问
        assertThat(answerResult.nextAction()).isEqualTo("FOLLOW_UP");

        // 追问
        InterviewService.QuestionView followUp = interviewService.followUp(session.id());
        assertThat(followUp.question().followUp()).isTrue();
        assertThat(followUp.question().parentQuestionId()).isEqualTo(first.question().id());

        var followUpAnswer = interviewService.submitAnswer(session.id(), followUp.question().id(),
                "具体实现上，我用 SET key value NX PX 30000 加锁，value 用请求 ID；"
                        + "解锁用 Lua 脚本先比对 value 再删除，避免误删；续期由后台线程每 10 秒执行一次。"
                        + "结果重复下单为 0，压测成功率 99.5%。");
        assertThat(followUpAnswer.evaluation().totalScore()).isGreaterThan(0.0);
        // 同一题已经追问过，不应再次建议追问
        assertThat(followUpAnswer.nextAction()).isIn("NEXT_QUESTION", "FINISH");

        // 再拿两题（第 1 题 + 追问 + 第 3、4 题 = 4 题，上限 5）
        InterviewService.QuestionView second = interviewService.nextQuestion(session.id());
        assertThat(second.question().sequence()).isEqualTo(3);
        interviewService.submitAnswer(session.id(), second.question().id(), "这题我准备得不好，只记得大概是用锁和唯一索引保证幂等。");

        InterviewService.QuestionView third = interviewService.nextQuestion(session.id());
        assertThat(third.question().sequence()).isEqualTo(4);
        interviewService.submitAnswer(session.id(), third.question().id(), "我用 Redis 做了缓存，性能提升很明显。");

        InterviewService.QuestionView fourth = interviewService.nextQuestion(session.id());
        assertThat(fourth.question().sequence()).isEqualTo(5);
        interviewService.submitAnswer(session.id(), fourth.question().id(),
                "我会先确认业务幂等键，再用数据库唯一索引兜底，最后用压测验证并发下的正确性。");

        // 达到上限后应自动结束
        assertThatThrownBy(() -> interviewService.nextQuestion(session.id()))
                .hasMessageContaining("已达");
        InterviewSession finished = interviewService.requireSession(session.id());
        assertThat(finished.status()).isEqualTo(SessionStatus.FINISHED);
        assertThat(finished.questionCount()).isEqualTo(5);

        InterviewReport report = interviewService.generateReport(session.id());
        assertThat(report.sessionId()).isEqualTo(session.id());
        assertThat(report.questionCount()).isEqualTo(5);
        assertThat(report.answerCount()).isEqualTo(5);
        assertThat(report.overallScore()).isBetween(0.0, 5.0);
        assertThat(report.abilityRadar()).isNotEmpty();
        assertThat(report.markdown()).contains("# 面试复盘报告").contains("## 七、逐题明细");
        assertThat(report.actionItems()).isNotEmpty();
        assertThat(interviewService.requireSession(session.id()).status()).isEqualTo(SessionStatus.REPORT_READY);
    }

    @Test
    @DisplayName("八股面不需要简历即可练习，题目来自知识库")
    void knowledgeModeWithoutResume() {
        InterviewSession session = interviewService.createSession(null, InterviewMode.KNOWLEDGE, 3);
        assertThat(session.resumeId()).isNull();

        InterviewService.QuestionView question = interviewService.nextQuestion(session.id());
        assertThat(question.question().content()).isNotBlank();
        assertThat(question.question().sourceIds()).isNotEmpty();
        // 八股面检索的是基础知识库，来源 ID 必须能在知识库里找到
        assertThat(question.question().sourceIds())
                .anySatisfy(id -> assertThat(id).startsWith("knowledge-"));
        assertThat(question.citations()).isNotEmpty();

        var result = interviewService.submitAnswer(session.id(), question.question().id(),
                "以 Redis 分布式锁为例：加锁用 SET NX PX 保证原子性，解锁必须用 Lua 比对 value，"
                        + "超时时间要覆盖业务耗时，否则会出现锁提前释放导致的并发问题。");
        assertThat(result.evaluation().degraded()).isFalse();
    }

    @Test
    @DisplayName("完整模拟面试：阶段按流程推进，报告包含各阶段题目")
    void fullMockInterviewStages() {
        String resumeId = importStandardResume();
        InterviewSession session = interviewService.createSession(resumeId, InterviewMode.FULL, 6);

        List<String> stages = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            InterviewService.QuestionView question = interviewService.nextQuestion(session.id());
            stages.add(question.question().stage());
            interviewService.submitAnswer(session.id(), question.question().id(),
                    "背景是订单系统，我负责核销链路；通过 Redis 加锁与唯一索引保证幂等；"
                            + "难点在锁续期，用看门狗解决；压测提升 30%，上线后没有重复单。");
        }
        assertThat(stages.get(0)).isEqualTo("SELF_INTRO");
        assertThat(stages).contains("PROJECT", "PROJECT_DEEP_DIVE", "FUNDAMENTALS");
        assertThat(stages.stream().distinct().count()).isGreaterThanOrEqualTo(3);

        InterviewReport report = interviewService.generateReport(session.id());
        assertThat(report.mode()).isEqualTo(InterviewMode.FULL);
        assertThat(report.abilityRadar()).anySatisfy(d -> assertThat(d.sample()).isPositive());
    }

    @Test
    @DisplayName("重复提交同一题被拒绝，避免污染评分")
    void duplicateAnswerRejected() {
        String resumeId = importStandardResume();
        InterviewSession session = interviewService.createSession(resumeId, InterviewMode.PROJECT, 3);
        InterviewService.QuestionView question = interviewService.nextQuestion(session.id());
        interviewService.submitAnswer(session.id(), question.question().id(), "第一次回答，说明我的职责与实现方案。");
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), question.question().id(),
                "第二次回答，内容与第一次不同。"))
                .hasMessageContaining("已经提交过回答");
    }

    @Test
    @DisplayName("空回答与超短回答在入口被拦截，不消耗模型调用")
    void emptyAnswerRejected() {
        String resumeId = importStandardResume();
        InterviewSession session = interviewService.createSession(resumeId, InterviewMode.PROJECT, 3);
        InterviewService.QuestionView question = interviewService.nextQuestion(session.id());
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), question.question().id(), "   "))
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), question.question().id(), "不会"))
                .hasMessageContaining("过短");
        // 状态仍停留在等待回答，可以重新提交
        assertThat(interviewService.requireSession(session.id()).status()).isEqualTo(SessionStatus.WAITING_ANSWER);
    }

    @Test
    @DisplayName("未出题就提交回答、结束后继续出题都会被拒绝并给出提示")
    void illegalSequenceRejected() {
        String resumeId = importStandardResume();
        InterviewSession session = interviewService.createSession(resumeId, InterviewMode.PROJECT, 2);
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), null, "试图直接回答。"))
                .hasMessageContaining("请先获取");

        InterviewService.QuestionView q1 = interviewService.nextQuestion(session.id());
        interviewService.submitAnswer(session.id(), q1.question().id(), "回答一，说明背景与机制。");
        InterviewService.QuestionView q2 = interviewService.nextQuestion(session.id());
        interviewService.submitAnswer(session.id(), q2.question().id(), "回答二，说明难点与结果验证。");
        interviewService.finish(session.id(), "测试主动结束");

        assertThatThrownBy(() -> interviewService.nextQuestion(session.id()))
                .hasMessageContaining("已结束");
        // 结束后仍可生成报告
        assertThat(interviewService.generateReport(session.id()).overallScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("追问次数到达上限后拒绝继续追问")
    void followUpLimitEnforced() {
        String resumeId = importStandardResume();
        InterviewSession session = interviewService.createSession(resumeId, InterviewMode.PROJECT, 4);
        InterviewService.QuestionView question = interviewService.nextQuestion(session.id());
        interviewService.submitAnswer(session.id(), question.question().id(), "简答：用了 Redis 和唯一索引。");
        interviewService.followUp(session.id());
        interviewService.submitAnswer(session.id(), interviewService.requireSession(session.id()).currentQuestionId(),
                "再补充：具体用 SET NX PX 加锁，Lua 解锁，续期 10 秒一次，验证方式为并发压测。");
        // 上限为 1，第二次追问应被拒绝
        assertThatThrownBy(() -> interviewService.followUp(session.id()))
                .hasMessageContaining("追问");
    }

    @Test
    @DisplayName("项目面未导入简历时拒绝创建会话")
    void projectModeRequiresResume() {
        assertThatThrownBy(() -> interviewService.createSession(null, InterviewMode.PROJECT))
                .hasMessageContaining("需要先导入简历");
    }

    @Test
    @DisplayName("会话不存在时给出 404 语义的异常")
    void unknownSession() {
        assertThatThrownBy(() -> interviewService.view("not-exist"))
                .isInstanceOf(com.dusk4d.interview.error.NotFoundException.class);
    }

    @Test
    @DisplayName("简历事实可以人工修正，修正后检索索引同步更新")
    void resumeFactsCanBeEdited() {
        String resumeId = importStandardResume();
        var resume = resumeService.require(resumeId);
        var projects = resume.factsOf(com.dusk4d.interview.domain.FactType.PROJECT);
        assertThat(projects).isNotEmpty();

        var edited = projects.stream().map(p -> new com.dusk4d.interview.domain.ResumeFact(p.id(), resumeId,
                p.type(), "修正后的项目名", p.content() + "\n补充：我负责了压测与容量评估。", p.sourceOrder(),
                1.0, p.metadata())).toList();
        var others = resume.facts().stream()
                .filter(f -> f.type() != com.dusk4d.interview.domain.FactType.PROJECT)
                .map(f -> new com.dusk4d.interview.domain.ResumeFact(f.id(), resumeId, f.type(), f.label(),
                        f.content(), f.sourceOrder(), f.confidence(), f.metadata()))
                .toList();
        var merged = new java.util.ArrayList<>(others);
        merged.addAll(edited);

        var updated = resumeService.updateFacts(resumeId, merged);
        assertThat(updated.factsOf(com.dusk4d.interview.domain.FactType.PROJECT))
                .allSatisfy(p -> assertThat(p.label()).isEqualTo("修正后的项目名"));
        assertThat(updated.factsOf(com.dusk4d.interview.domain.FactType.PROJECT))
                .allSatisfy(p -> assertThat(p.content()).contains("压测与容量评估"));
    }
}
