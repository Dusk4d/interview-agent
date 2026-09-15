package com.dusk4d.interview;

import com.dusk4d.interview.agent.AnswerEvaluator;
import com.dusk4d.interview.agent.QuestionGenerator;
import com.dusk4d.interview.agent.ReportBuilder;
import com.dusk4d.interview.agent.StructuredOutputParser;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.domain.SessionStatus;
import com.dusk4d.interview.error.InvalidSessionStateException;
import com.dusk4d.interview.error.NotFoundException;
import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.error.ValidationException;
import com.dusk4d.interview.llm.EmbeddingClient;
import com.dusk4d.interview.llm.LlmFailureKind;
import com.dusk4d.interview.llm.LlmClient;
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
import com.dusk4d.interview.storage.JsonFileStore;
import com.dusk4d.interview.storage.MapBackedInterviewRepository;
import com.dusk4d.interview.storage.Store;
import com.dusk4d.interview.support.DocxFileBuilder;
import com.dusk4d.interview.support.Fixtures;
import com.dusk4d.interview.support.PdfFileBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 总验收测试（对应方案书第九节的 MVP 验收标准）。
 *
 * <p>这是「一套跑完就能判断系统是否可用」的门禁测试：不启动 HTTP，直接驱动服务层，
 * 覆盖导入 → 出题 → 回答 → 评分 → 追问 → 换题 → 结束 → 报告全链路，
 * 以及所有「不通过验收」的反面场景（脱离简历、串题、模型失败无提示、隐私泄露等）。
 *
 * <p>与其它测试的分工：单测覆盖各自模块的细节，本类只回答一个问题——
 * 「按方案书的验收标准，这个系统现在算不算做完了」。
 */
@DisplayName("MVP 总验收")
class MvpAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant FIXED = Instant.parse("2024-06-01T10:00:00Z");
    private static final AppProperties PROPERTIES = new AppProperties(
            new AppProperties.Llm("http://127.0.0.1:1/v1", "test", "mock", "mock-embed",
                    0.3, 900, 400, 800, 0, false, null, null, null),
            new AppProperties.Embedding("local", 128, 8),
            new AppProperties.Retrieval(4, 0.05, 4000, 3),
            new AppProperties.Interview(6, 1, 8, 4000),
            new AppProperties.Privacy(true, false),
            new AppProperties.Storage("memory", ""),
            new AppProperties.Parser(10 * 1024 * 1024));

    private PrivacyMasker masker;
    private TextCleaner cleaner;
    private ResumeFactExtractor factExtractor;
    private DocumentExtractorRouter router;
    private InterviewRepository repository;
    private VectorStore vectorStore;
    private KnowledgeBase knowledgeBase;
    private ResumeImportService resumeService;
    private InterviewService interviewService;
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        this.masker = new PrivacyMasker();
        this.cleaner = new TextCleaner();
        this.factExtractor = new ResumeFactExtractor(cleaner, masker);
        this.router = new DocumentExtractorRouter();
        this.repository = inMemoryRepository();
        EmbeddingClient embedding = new LocalHashEmbeddingClient(PROPERTIES);
        this.vectorStore = new InMemoryVectorStore(embedding);
        this.knowledgeBase = new KnowledgeBase(MAPPER, vectorStore);
        this.llmClient = new MockLlmClient(MAPPER);
        buildServices(llmClient, repository, vectorStore, knowledgeBase);
    }

    private void buildServices(LlmClient client, InterviewRepository repo, VectorStore store,
                               KnowledgeBase knowledge) {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        StructuredOutputParser parser = new StructuredOutputParser(MAPPER);
        DocumentRetriever retriever = new DocumentRetriever(store, new LocalHashEmbeddingClient(PROPERTIES),
                PROPERTIES);
        this.resumeService = new ResumeImportService(router, cleaner, factExtractor, masker, repo, store,
                PROPERTIES, clock);
        this.interviewService = new InterviewService(repo, resumeService, retriever, knowledge,
                new QuestionGenerator(client, parser, PROPERTIES),
                new AnswerEvaluator(client, parser, PROPERTIES, clock),
                new ReportBuilder(client, parser, PROPERTIES, clock),
                PROPERTIES, clock);
    }

    private static InterviewRepository inMemoryRepository() {
        return new MapBackedInterviewRepository(
                new InMemoryStore<>(Resume::id, Comparator.comparing(Resume::createdAt)),
                new InMemoryStore<>(InterviewSession::id, Comparator.comparing(InterviewSession::createdAt)),
                new InMemoryStore<>(q -> q.id(), Comparator.comparingInt(q -> q.sequence())),
                new InMemoryStore<>(a -> a.id(), Comparator.comparing(a -> a.createdAt())),
                new InMemoryStore<>(e -> e.id(), Comparator.comparing(e -> e.createdAt())),
                new InMemoryStore<>(r -> r.id(), Comparator.comparing(r -> r.createdAt())));
    }

    // ================================================================ 验收项 1~7

    @Test
    @DisplayName("验收1：支持 PDF/DOCX/TXT 三种格式 + 纯文本兜底导入")
    void acceptance1_importAllFormats() {
        // TXT
        String txtId = importFixture("resume-standard.txt").id();
        assertThat(txtId).isNotBlank();

        // DOCX（用测试生成的 OOXML 包）
        byte[] docx = DocxFileBuilder.build(List.of(
                "李娜", "教育经历", "2019.09-2023.06 华中科技大学 软件工程 本科",
                "项目经历", "智学助手在线教育平台 2023.02-2023.11",
                "技术栈：FastAPI、MySQL、Redis", "个人职责：负责课程推荐与埋点数据模型",
                "结果：写入压力下降 60%"));
        var docxResult = resumeService.importFile("李娜-简历.docx", docx);
        assertThat(docxResult.resume().fileType()).isEqualTo("docx");
        assertThat(docxResult.resume().facts()).isNotEmpty();

        // PDF（单页多行）。注意：英文 PDF 能被正确提取，但结构化抽取只认中文区块标题，
        // 因此没有可检索事实 → 状态为 NEEDS_REVIEW 并提示需要人工补充，这是刻意设计而非缺陷。
        byte[] pdf = PdfFileBuilder.page(List.of(
                "Zhang Wei - Java Backend Developer",
                "Education: Beijing University of Posts and Telecommunications",
                "Project: FinanceAgent RAG platform with pgvector",
                "Skills: Java, Spring Boot, Redis, Docker"));
        var pdfResult = resumeService.importFile("resume.pdf", pdf);
        assertThat(pdfResult.resume().fileType()).isEqualTo("pdf");
        assertThat(pdfResult.resume().maskedText()).contains("FinanceAgent").contains("Redis");
        assertThat(pdfResult.resume().status()).isEqualTo(com.dusk4d.interview.domain.ResumeStatus.NEEDS_REVIEW);
        assertThat(pdfResult.warnings()).anySatisfy(w -> assertThat(w).contains("项目"));

        // 纯文本兜底
        var textResult = resumeService.importText(Fixtures.text("resume-numbered-projects.txt"), null);
        assertThat(textResult.resume().fileName()).isEqualTo("手动粘贴的简历");
        assertThat(textResult.resume().facts()).isNotEmpty();
    }

    @Test
    @DisplayName("验收2：展示解析结果并可人工修正")
    void acceptance2_reviewAndCorrectFacts() {
        Resume resume = importFixture("resume-standard.txt");
        assertThat(resume.factsOf(com.dusk4d.interview.domain.FactType.PROJECT)).isNotEmpty();
        assertThat(resume.factsOf(com.dusk4d.interview.domain.FactType.SKILL)).isNotEmpty();

        var updated = resumeService.updateFacts(resume.id(), resume.facts().stream()
                .map(fact -> new ResumeFact(fact.id(), resume.id(), fact.type(),
                        fact.type() == com.dusk4d.interview.domain.FactType.PROJECT ? "修正后的项目" : fact.label(),
                        fact.content() + "\n人工确认：已核对无误。", fact.sourceOrder(), 1.0, fact.metadata()))
                .toList());

        assertThat(updated.factsOf(com.dusk4d.interview.domain.FactType.PROJECT))
                .allSatisfy(fact -> assertThat(fact.label()).isEqualTo("修正后的项目"));
        assertThat(updated.facts()).allSatisfy(fact -> assertThat(fact.content()).contains("人工确认"));

        // 修正后的内容立即进入检索索引
        var search = new DocumentRetriever(vectorStore, new LocalHashEmbeddingClient(PROPERTIES), PROPERTIES)
                .retrieveFacts(resume.id(), "修正后的项目 人工确认");
        assertThat(search.chunks()).isNotEmpty();
        assertThat(search.context()).contains("人工确认");
    }

    @Test
    @DisplayName("验收3：项目面与八股面两种模式都能出题并区分来源")
    void acceptance3_twoModes() {
        Resume resume = importFixture("resume-standard.txt");

        InterviewSession projectSession = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 3);
        var projectQuestion = interviewService.nextQuestion(projectSession.id());
        assertThat(projectQuestion.question().sourceIds()).isNotEmpty();
        assertThat(projectQuestion.question().sourceIds())
                .allSatisfy(id -> assertThat(id).startsWith("resume-"));
        assertThat(projectQuestion.citations()).allSatisfy(c -> assertThat(c).startsWith("简历片段"));

        InterviewSession knowledgeSession = interviewService.createSession(null, InterviewMode.KNOWLEDGE, 3);
        var knowledgeQuestion = interviewService.nextQuestion(knowledgeSession.id());
        assertThat(knowledgeQuestion.question().sourceIds())
                .anySatisfy(id -> assertThat(id).startsWith("knowledge-"));
        assertThat(knowledgeQuestion.citations()).anySatisfy(c -> assertThat(c).startsWith("知识点"));
    }

    @Test
    @DisplayName("验收4：出题 → 回答 → 评分 → 纠错全链路，评分含分项依据")
    void acceptance4_questionAnswerEvaluate() {
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 3);
        var question = interviewService.nextQuestion(session.id());

        var result = interviewService.submitAnswer(session.id(), question.question().id(),
                "背景是订单中心需要幂等。我负责优惠券核销链路，使用 Redis SET NX PX 加锁并用唯一索引兜底，"
                        + "难点是锁超时，做了看门狗续期，压测重复下单为 0。");

        var evaluation = result.evaluation();
        assertThat(evaluation.dimensionScores()).hasSize(4);
        assertThat(evaluation.dimensionScores().values())
                .allSatisfy(dimension -> assertThat(dimension.reason()).isNotBlank());
        assertThat(evaluation.totalScore()).isBetween(0.0, 5.0);
        assertThat(evaluation.referenceAnswerStructure()).isNotBlank();
        assertThat(evaluation.summary()).isNotBlank();
        // 评分必须能追溯到具体回答
        assertThat(evaluation.answerId()).isEqualTo(result.answer().id());
        assertThat(interviewService.evaluationOfAnswer(session.id(), result.answer().id())).isPresent();
    }

    @Test
    @DisplayName("验收5：支持追问与换题，且追问不串题")
    void acceptance5_followUpAndNextQuestion() {
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 5);
        var first = interviewService.nextQuestion(session.id());
        var answered = interviewService.submitAnswer(session.id(), first.question().id(),
                "用了 Redis 和数据库唯一索引，具体细节记不清了。");
        assertThat(answered.nextAction()).isEqualTo("FOLLOW_UP");

        var followUp = interviewService.followUp(session.id());
        assertThat(followUp.question().followUp()).isTrue();
        assertThat(followUp.question().parentQuestionId()).isEqualTo(first.question().id());
        assertThat(followUp.question().sequence()).isEqualTo(2);

        interviewService.submitAnswer(session.id(), followUp.question().id(),
                "具体用 SET NX PX 加锁，value 是请求 ID，解锁用 Lua 比对后删除，续期 10 秒一次，压测重复下单为 0。");

        // 换题：不会把上一题的上下文带过去
        var next = interviewService.nextQuestion(session.id());
        assertThat(next.question().followUp()).isFalse();
        assertThat(next.question().parentQuestionId()).isNull();
        assertThat(next.question().sequence()).isEqualTo(3);
        assertThat(next.question().id()).isNotEqualTo(followUp.question().id());
    }

    @Test
    @DisplayName("验收6：结束面试并生成可下载的复盘报告")
    void acceptance6_finishAndReport() {
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 3);
        for (int i = 0; i < 2; i++) {
            var question = interviewService.nextQuestion(session.id());
            interviewService.submitAnswer(session.id(), question.question().id(),
                    i == 0 ? "用 Redis 加锁保证幂等，细节记不清。" :
                            "背景是订单系统，我负责核销链路，通过 Redis 与唯一索引保证幂等，"
                                    + "难点在锁续期，用看门狗解决，压测重复下单为 0。");
        }
        interviewService.finish(session.id(), "验收测试结束");

        InterviewReport report = interviewService.generateReport(session.id());
        assertThat(report.overallScore()).isBetween(0.0, 5.0);
        assertThat(report.abilityRadar()).isNotEmpty();
        assertThat(report.weakestQuestions()).isNotEmpty();
        assertThat(report.actionItems()).isNotEmpty();
        assertThat(report.markdown())
                .contains("# 面试复盘报告")
                .contains("## 二、能力雷达")
                .contains("## 七、逐题明细");

        // 重复生成可覆盖，且状态稳定
        InterviewReport again = interviewService.generateReport(session.id());
        assertThat(again.sessionId()).isEqualTo(session.id());
        assertThat(interviewService.requireSession(session.id()).status()).isEqualTo(SessionStatus.REPORT_READY);
    }

    @Test
    @DisplayName("验收7：空回答、模型超时、非法结构化输出、检索为空都能被安全处理")
    void acceptance7_failurePaths() {
        // 7.1 空回答：400 语义，不调用模型，状态可重试
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 3);
        var question = interviewService.nextQuestion(session.id());
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), question.question().id(), "   "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("不能为空");
        assertThat(interviewService.requireSession(session.id()).status()).isEqualTo(SessionStatus.WAITING_ANSWER);

        // 7.2 模型连接失败：降级为模板出题 + 启发式评分，并标注原因
        // 注意：切换模型客户端后仓储与向量库都会重建，因此必须重新导入简历
        Resume connectionResume = switchLlmAndImport(MockLlmClient.failing(MAPPER, LlmFailureKind.CONNECTION));
        InterviewSession degraded = interviewService.createSession(connectionResume.id(), InterviewMode.PROJECT, 3);
        var degradedQuestion = interviewService.nextQuestion(degraded.id());
        assertThat(degradedQuestion.degraded()).isTrue();
        assertThat(degradedQuestion.degradationReason()).contains("模型服务未连接");
        var degradedAnswer = interviewService.submitAnswer(degraded.id(), degradedQuestion.question().id(),
                "用 Redis 加锁保证幂等，具体实现记不清了。");
        assertThat(degradedAnswer.evaluation().degraded()).isTrue();
        assertThat(degradedAnswer.evaluation().dimensionScores()).hasSize(4);
        assertThat(degradedAnswer.evaluation().totalScore()).isLessThanOrEqualTo(3.5);
        assertThat(degradedAnswer.evaluation().summary()).contains("降级评估");

        // 7.3 模型超时：同样降级，不抛异常给用户
        Resume timeoutResume = switchLlmAndImport(MockLlmClient.failing(MAPPER, LlmFailureKind.TIMEOUT));
        InterviewSession timeout = interviewService.createSession(timeoutResume.id(), InterviewMode.PROJECT, 2);
        var timeoutQuestion = interviewService.nextQuestion(timeout.id());
        assertThat(timeoutQuestion.degraded()).isTrue();
        assertThat(timeoutQuestion.degradationReason()).contains("超时");

        // 7.4 非法结构化输出：解析器修复失败后降级，而不是把缺失当 0 分
        Resume malformedResume = switchLlmAndImport(MockLlmClient.malformed(MAPPER));
        InterviewSession malformed = interviewService.createSession(malformedResume.id(), InterviewMode.PROJECT, 2);
        var malformedQuestion = interviewService.nextQuestion(malformed.id());
        assertThat(malformedQuestion.degraded()).isTrue();
        var malformedAnswer = interviewService.submitAnswer(malformed.id(), malformedQuestion.question().id(),
                "用 Redis 加锁保证幂等，配合唯一索引兜底。");
        assertThat(malformedAnswer.evaluation().degraded()).isTrue();

        // 7.5 空模型响应
        Resume emptyLlmResume = switchLlmAndImport(MockLlmClient.emptyLlm(MAPPER));
        InterviewSession emptyLlm = interviewService.createSession(emptyLlmResume.id(), InterviewMode.PROJECT, 2);
        assertThat(interviewService.nextQuestion(emptyLlm.id()).degraded()).isTrue();

        // 7.6 检索为空：不编造，回退到事实原文并给出明确提示
        // 7.6 检索为空：不编造，回退到事实原文并给出明确提示
        VectorStore blindStore = new InMemoryVectorStore(new LocalHashEmbeddingClient(PROPERTIES)) {
            @Override
            public List<com.dusk4d.interview.rag.ScoredChunk> search(String query,
                                                                     com.dusk4d.interview.rag.VectorStore.ChunkFilter filter,
                                                                     int topK, double minScore) {
                return List.of();
            }

            @Override
            public List<com.dusk4d.interview.rag.TextChunk> chunks(
                    com.dusk4d.interview.rag.VectorStore.ChunkFilter filter, int limit) {
                return List.of();
            }
        };
        InterviewRepository blindRepo = inMemoryRepository();
        KnowledgeBase blindKnowledge = new KnowledgeBase(MAPPER,
                new InMemoryVectorStore(new LocalHashEmbeddingClient(PROPERTIES)));
        buildServices(new MockLlmClient(MAPPER), blindRepo, blindStore, blindKnowledge);
        // 简历仍需写入仓储（这样才有回退用的原文事实），但它的片段刻意不进向量库
        Resume emptyRetrievalResume = importFixture("resume-standard.txt");
        InterviewSession emptyRetrieval = interviewService.createSession(emptyRetrievalResume.id(),
                InterviewMode.PROJECT, 2);
        var fallbackQuestion = interviewService.nextQuestion(emptyRetrieval.id());
        // 检索为空时仍要有事实依据（回退到简历事实原文），而不是凭空出题
        assertThat(fallbackQuestion.degraded()).isFalse();
        assertThat(fallbackQuestion.question().sourceIds())
                .as("兜底路径也必须保留来源 ID，否则无法核对问题依据")
                .isNotEmpty();
        assertThat(fallbackQuestion.citations()).isNotEmpty();
        // 来源 ID 必须能在该简历的事实里找到，而不是模型编造的
        List<String> factIds = emptyRetrievalResume.facts().stream().map(ResumeFact::id).toList();
        assertThat(fallbackQuestion.question().sourceIds()).allSatisfy(id -> assertThat(factIds).contains(id));
    }

    @Test
    @DisplayName("验收8：简历事实、隐私字段不被泄露，敏感信息不进模型上下文")
    void acceptance8_privacy() {
        byte[] content = Fixtures.bytes("resume-standard.txt");
        var result = resumeService.importFile("张伟-简历.txt", content);
        Resume resume = result.resume();

        assertThat(resume.maskedText()).doesNotContain("13812345678");
        assertThat(resume.maskedText()).doesNotContain("zhangwei_dev@example.com");
        assertThat(resume.maskedText()).contains("[手机号已脱敏]").contains("[邮箱已脱敏]");
        assertThat(masker.containsSensitive(resume.maskedText())).isFalse();

        // 出题上下文（检索结果）也不含联系方式
        var retrieval = new DocumentRetriever(vectorStore, new LocalHashEmbeddingClient(PROPERTIES), PROPERTIES)
                .retrieveFacts(resume.id(), "个人信息 联系方式 手机号");
        assertThat(masker.containsSensitive(retrieval.context())).isFalse();

        // 事实内容同样脱敏
        String allFacts = resume.facts().stream().map(ResumeFact::content).reduce("", (a, b) -> a + "\n" + b);
        assertThat(masker.containsSensitive(allFacts)).isFalse();

        // 出题提示词里不能出现原始联系方式
        var question = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 2);
        var generated = interviewService.nextQuestion(question.id());
        assertThat(generated.question().content()).doesNotContain("13812345678");
        assertThat(String.join(" ", generated.citations())).doesNotContain("@");
    }

    // ================================================================ 反面场景

    @Test
    @DisplayName("反面场景：问题不脱离简历——来源 ID 必须来自该简历的片段")
    void noQuestionWithoutResumeEvidence() {
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 4);
        List<String> factIds = resume.facts().stream().map(ResumeFact::id).toList();

        for (int i = 0; i < 3; i++) {
            var question = interviewService.nextQuestion(session.id());
            assertThat(question.question().sourceIds())
                    .as("第 %d 题的来源必须来自本简历", i + 1)
                    .isNotEmpty()
                    .allSatisfy(id -> assertThat(factIds).contains(id));
            interviewService.submitAnswer(session.id(), question.question().id(),
                    "回答内容：我负责该模块的设计与实现，使用缓存与索引优化，结果响应时间下降 40%。");
        }
    }

    @Test
    @DisplayName("反面场景：追问不串题、重复提交被拒绝、终态不可继续")
    void noCrossQuestionContamination() {
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 3);
        var q1 = interviewService.nextQuestion(session.id());
        interviewService.submitAnswer(session.id(), q1.question().id(), "简答：用了 Redis。");

        // 重复提交同一题 → 409
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), q1.question().id(), "再次回答的内容。"))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已经提交过回答");

        // 覆盖式追问只会针对当前题
        if (interviewService.requireSession(session.id()).status() == SessionStatus.FOLLOW_UP) {
            var followUp = interviewService.followUp(session.id());
            assertThat(followUp.question().parentQuestionId()).isEqualTo(q1.question().id());
        }

        interviewService.finish(session.id(), "测试结束");
        assertThatThrownBy(() -> interviewService.nextQuestion(session.id()))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("已结束");
        assertThatThrownBy(() -> interviewService.submitAnswer(session.id(), q1.question().id(), "结束后还想回答。"))
                .isInstanceOf(InvalidSessionStateException.class);
    }

    @Test
    @DisplayName("反面场景：模型失败后页面不会一直等待——降级路径有明确结果")
    void noInfiniteWaitingOnModelFailure() {
        buildServices(MockLlmClient.failing(MAPPER, LlmFailureKind.TIMEOUT), repository, vectorStore, knowledgeBase);
        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 2);

        long start = System.currentTimeMillis();
        var question = interviewService.nextQuestion(session.id());
        var answer = interviewService.submitAnswer(session.id(), question.question().id(),
                "我负责该模块，使用缓存优化，结果提升明显。");
        long elapsed = System.currentTimeMillis() - start;

        // 降级路径必须立即返回（Mock 不模拟真实等待），且结果字段完整
        assertThat(elapsed).isLessThan(5000);
        assertThat(question.degraded()).isTrue();
        assertThat(question.degradationReason()).isNotBlank();
        assertThat(answer.evaluation().degraded()).isTrue();
        assertThat(answer.evaluation().summary()).isNotBlank();
        assertThat(answer.nextActionHint()).isNotBlank();
    }

    @Test
    @DisplayName("反面场景：不存在的简历/会话给出 404 语义异常")
    void notFoundSemantics() {
        assertThatThrownBy(() -> resumeService.require("missing-resume"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> interviewService.view("missing-session"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> interviewService.requireReport("missing-session"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("反面场景：解析失败必须返回明确原因（图片 PDF / 旧版 doc / 空文件 / 纯符号）")
    void parseFailuresAreExplicit() {
        assertThatThrownBy(() -> resumeService.importFile("scan.pdf", PdfFileBuilder.imageOnly()))
                .isInstanceOf(ResumeParseException.class)
                .hasMessageContaining("图片");
        assertThatThrownBy(() -> resumeService.importFile("old.doc",
                new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0}))
                .isInstanceOf(ResumeParseException.class)
                .hasMessageContaining("不支持");
        assertThatThrownBy(() -> resumeService.importFile("empty.txt", new byte[0]))
                .isInstanceOf(ResumeParseException.class);
        assertThatThrownBy(() -> resumeService.importText("★☆★☆ ※※※※ ◆◆◆◆", null))
                .isInstanceOf(ResumeParseException.class)
                .hasMessageContaining("没有可用文本");
    }

    // ================================================================ 持久化

    @Test
    @DisplayName("验收9：完整流程落盘后可跨重启恢复（含检索索引重建）")
    void acceptance9_persistenceAcrossRestart(@TempDir Path dir) {
        // 第一次运行：使用文件仓储完成一场面试
        InterviewRepository fileRepo = fileRepository(dir);
        VectorStore firstStore = new InMemoryVectorStore(new LocalHashEmbeddingClient(PROPERTIES));
        KnowledgeBase firstKnowledge = new KnowledgeBase(MAPPER, firstStore);
        buildServices(new MockLlmClient(MAPPER), fileRepo, firstStore, firstKnowledge);

        Resume resume = importFixture("resume-standard.txt");
        InterviewSession session = interviewService.createSession(resume.id(), InterviewMode.PROJECT, 2);
        var question = interviewService.nextQuestion(session.id());
        interviewService.submitAnswer(session.id(), question.question().id(),
                "我负责检索链路，使用向量检索与关键词融合，结果平均响应 1.8 秒。");
        interviewService.finish(session.id(), "持久化测试");
        interviewService.generateReport(session.id());
        // 记录重启前的真实状态（此时已下发 1 题、已回答 1 题）
        int questionCountBeforeRestart = interviewService.requireSession(session.id()).questionCount();
        assertThat(questionCountBeforeRestart).isEqualTo(1);

        // 第二次运行：模拟重启（新的向量库 + 新的仓储实例，指向同一目录）
        InterviewRepository reloadedRepo = fileRepository(dir);
        VectorStore reloadedStore = new InMemoryVectorStore(new LocalHashEmbeddingClient(PROPERTIES));
        KnowledgeBase reloadedKnowledge = new KnowledgeBase(MAPPER, reloadedStore);
        buildServices(new MockLlmClient(MAPPER), reloadedRepo, reloadedStore, reloadedKnowledge);
        new com.dusk4d.interview.config.VectorIndexInitializer(reloadedRepo, reloadedStore).run(null);

        // 数据完整
        Resume reloadedResume = resumeService.require(resume.id());
        assertThat(reloadedResume.facts()).hasSize(resume.facts().size());
        InterviewSession reloadedSession = interviewService.requireSession(session.id());
        assertThat(reloadedSession.status()).isEqualTo(SessionStatus.REPORT_READY);
        assertThat(reloadedSession.questionCount()).isEqualTo(questionCountBeforeRestart);
        assertThat(reloadedSession.answerCount()).isEqualTo(1);
        assertThat(reloadedSession.coveredTopics()).isNotEmpty();

        // 报告与评分可读
        InterviewReport report = interviewService.requireReport(session.id());
        assertThat(report.overallScore()).isEqualTo(interviewService.requireReport(session.id()).overallScore());
        assertThat(interviewService.detail(session.id()).evaluations()).isNotEmpty();

        // 检索索引已重建：重启后仍能检索到简历事实
        var retrieval = new DocumentRetriever(reloadedStore, new LocalHashEmbeddingClient(PROPERTIES), PROPERTIES)
                .retrieveFacts(resume.id(), "检索链路 向量检索");
        assertThat(retrieval.chunks()).isNotEmpty();
        assertThat(retrieval.citations()).isNotEmpty();

        // 未产生任何 .corrupt 文件
        assertThat(dir.resolve("resumes.json.corrupt")).doesNotExist();
        assertThat(dir.resolve("reports.json.corrupt")).doesNotExist();
    }

    // ================================================================ 工具

    private InterviewRepository fileRepository(Path dir) {
        return new MapBackedInterviewRepository(
                fileStore(dir, "resumes.json", Resume.class, Resume::id, Comparator.comparing(Resume::createdAt)),
                fileStore(dir, "sessions.json", InterviewSession.class, InterviewSession::id,
                        Comparator.comparing(InterviewSession::createdAt)),
                fileStore(dir, "questions.json", com.dusk4d.interview.domain.InterviewQuestion.class,
                        q -> q.id(), Comparator.comparingInt(q -> q.sequence())),
                fileStore(dir, "answers.json", com.dusk4d.interview.domain.InterviewAnswer.class,
                        a -> a.id(), Comparator.comparing(a -> a.createdAt())),
                fileStore(dir, "evaluations.json", com.dusk4d.interview.domain.AnswerEvaluation.class,
                        e -> e.id(), Comparator.comparing(e -> e.createdAt())),
                fileStore(dir, "reports.json", InterviewReport.class, r -> r.id(),
                        Comparator.comparing(InterviewReport::createdAt)));
    }

    private <T> Store<T> fileStore(Path dir, String fileName, Class<T> type,
                                   java.util.function.Function<T, String> idExtractor, Comparator<T> order) {
        return new JsonFileStore<>(dir.resolve(fileName),
                MAPPER.getTypeFactory().constructCollectionType(List.class, type),
                MAPPER, idExtractor, order);
    }

    private Resume importFixture(String fixture) {
        return resumeService.importText(Fixtures.text(fixture), fixture).resume();
    }

    /**
     * 切换模型客户端（模拟模型不可用等场景）。
     *
     * <p>切换会重建仓储、向量库与服务对象，因此调用方必须使用返回的「重新导入后的简历」，
     * 否则会拿着旧仓储里的 ID 去访问新仓储（这正是本测试第一版踩到的坑）。
     */
    private Resume switchLlmAndImport(LlmClient client) {
        InterviewRepository freshRepo = inMemoryRepository();
        VectorStore freshStore = new InMemoryVectorStore(new LocalHashEmbeddingClient(PROPERTIES));
        KnowledgeBase freshKnowledge = new KnowledgeBase(MAPPER, freshStore);
        buildServices(client, freshRepo, freshStore, freshKnowledge);
        return importFixture("resume-standard.txt");
    }
}
