package com.dusk4d.interview.storage;

import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.DimensionScore;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.domain.ResumeStatus;
import com.dusk4d.interview.domain.SessionStatus;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件持久化回归测试。
 *
 * <p>背景：曾经出现过「重启后所有数据文件都被判定为损坏」的严重缺陷，
 * 根因是读回时使用了匿名 {@code TypeReference<List<T>>}，运行时被解析成 {@code List<Object>}。
 * 这里用真实的写盘 + 重新构造仓储（模拟进程重启）来锁定该行为。
 */
@DisplayName("文件持久化 JsonFileStore")
class JsonFileStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static <T> JsonFileStore<T> store(Path dir, String name, Class<T> type,
                                              java.util.function.Function<T, String> id,
                                              Comparator<T> order) {
        JavaType listType = MAPPER.getTypeFactory().constructCollectionType(List.class, type);
        return new JsonFileStore<>(dir.resolve(name), listType, MAPPER, id, order);
    }

    private static Resume sampleResume(String id) {
        ResumeFact fact = new ResumeFact("resume-project-001", id, FactType.PROJECT,
                "FinanceAgent 智能财务问答系统", "个人职责：负责文档解析与检索链路\n结果：支持 12 类财务问题",
                1, 0.9, List.of("时间：2023.09-2024.05", "Java", "pgvector"));
        ResumeFact skill = new ResumeFact("resume-skill-002", id, FactType.SKILL, "技能",
                "Java、Python、Spring Boot", 2, 0.8, List.of());
        return new Resume(id, "张伟-简历.txt", "txt", 2089, "脱敏文本", "脱敏文本",
                ResumeStatus.PARSED, null, List.of("技术栈识别完整"), List.of(fact, skill),
                Instant.parse("2024-06-01T10:00:00Z"), Instant.parse("2024-06-01T10:05:00Z"));
    }

    @Test
    @DisplayName("写入后重新打开仓储（模拟重启）应完整读回简历与嵌套事实")
    void resumeSurvivesRestart(@TempDir Path dir) {
        JsonFileStore<Resume> store = store(dir, "resumes.json", Resume.class, Resume::id,
                Comparator.comparing(Resume::createdAt));
        store.save(sampleResume("resume-1"));
        assertThat(store.count()).isEqualTo(1);

        // 模拟进程重启：用同一个文件重新构造仓储
        JsonFileStore<Resume> reloaded = store(dir, "resumes.json", Resume.class, Resume::id,
                Comparator.comparing(Resume::createdAt));
        assertThat(reloaded.count()).isEqualTo(1);
        Resume resume = reloaded.findById("resume-1").orElseThrow();
        assertThat(resume.fileName()).isEqualTo("张伟-简历.txt");
        assertThat(resume.status()).isEqualTo(ResumeStatus.PARSED);
        assertThat(resume.createdAt()).isEqualTo(Instant.parse("2024-06-01T10:00:00Z"));
        assertThat(resume.warnings()).containsExactly("技术栈识别完整");
        assertThat(resume.facts()).hasSize(2);
        assertThat(resume.factsOf(FactType.PROJECT)).hasSize(1);
        ResumeFact project = resume.factsOf(FactType.PROJECT).get(0);
        assertThat(project.label()).isEqualTo("FinanceAgent 智能财务问答系统");
        assertThat(project.type()).isEqualTo(FactType.PROJECT);
        assertThat(project.metadata()).contains("时间：2023.09-2024.05", "pgvector");
        assertThat(project.confidence()).isEqualTo(0.9);

        // 数据文件不应残留 .corrupt 备份
        assertThat(dir.resolve("resumes.json.corrupt")).doesNotExist();
    }

    @Test
    @DisplayName("会话、问题、回答、评分、报告都能跨重启读回")
    void sessionAggregateSurvivesRestart(@TempDir Path dir) {
        Instant now = Instant.parse("2024-06-01T10:00:00Z");
        InterviewSession session = new InterviewSession("s-1", "resume-1", InterviewMode.PROJECT,
                com.dusk4d.interview.domain.InterviewStage.SINGLE_QUESTION, SessionStatus.REPORT_READY,
                "q-1", "a-1", "FinanceAgent 智能财务问答系统", 2, 2, 1, 1, 6,
                List.of("FinanceAgent 智能财务问答系统"), List.of("第 1 题 3.5 分：回答可用"),
                List.of("请说明你在该项目中的职责"), "用户主动结束",
                now, now.plusSeconds(5), now.plusSeconds(300), now.plusSeconds(300));
        InterviewQuestion question = new InterviewQuestion("q-1", "s-1", 1, QuestionType.PROJECT_TECH,
                Difficulty.MEDIUM, "你在该项目中负责哪一部分？", "考察职责边界",
                List.of("个人职责", "技术选型"), List.of("resume-project-001"),
                List.of("简历片段：FinanceAgent 智能财务问答系统"), "追问实现机制", null, false,
                "SINGLE_QUESTION", now);
        InterviewAnswer answer = new InterviewAnswer("a-1", "q-1", "s-1", "我负责文档解析与检索链路",
                12, false, now.plusSeconds(30));
        AnswerEvaluation evaluation = new AnswerEvaluation("e-1", "a-1", "q-1", "s-1", 3.5,
                Map.of("technical_correctness",
                        new DimensionScore("technical_correctness", "技术正确性", 4.0, 0.25, "提到了机制")),
                List.of("给出了具体实现"), List.of("缺少量化结果"), List.of(), List.of("补充边界条件"),
                "背景 → 职责 → 机制 → 结果", List.of(), true, "追问边界", "总体可用", false, null,
                now.plusSeconds(31));

        store(dir, "sessions.json", InterviewSession.class, InterviewSession::id,
                Comparator.comparing(InterviewSession::createdAt)).save(session);
        store(dir, "questions.json", InterviewQuestion.class, InterviewQuestion::id,
                Comparator.comparingInt(InterviewQuestion::sequence)).save(question);
        store(dir, "answers.json", InterviewAnswer.class, InterviewAnswer::id,
                Comparator.comparing(InterviewAnswer::createdAt)).save(answer);
        store(dir, "evaluations.json", AnswerEvaluation.class, AnswerEvaluation::id,
                Comparator.comparing(AnswerEvaluation::createdAt)).save(evaluation);
        store(dir, "reports.json", com.dusk4d.interview.domain.InterviewReport.class,
                com.dusk4d.interview.domain.InterviewReport::id,
                Comparator.comparing(com.dusk4d.interview.domain.InterviewReport::createdAt)).save(
                new com.dusk4d.interview.domain.InterviewReport("r-1", "s-1", InterviewMode.PROJECT, now,
                        now.plusSeconds(300), 300, 1, 1, 3.5,
                        List.of(new com.dusk4d.interview.domain.AbilityDimension("project_expression", "项目表达", 3.5, 1)),
                        List.of("[3.5 分] 你在该项目中负责哪一部分？"),
                        List.of(new com.dusk4d.interview.domain.WeakTopic("个人职责", "回答不完整", List.of("缺少量化结果"))),
                        List.of("可能追问边界条件"), List.of("重答该题"), "总体可用", "# 面试复盘报告",
                        now.plusSeconds(301)));

        // 重启后重新装配仓储并组装聚合视图
        InterviewRepository repository = new MapBackedInterviewRepository(
                store(dir, "resumes.json", Resume.class, Resume::id, Comparator.comparing(Resume::createdAt)),
                store(dir, "sessions.json", InterviewSession.class, InterviewSession::id,
                        Comparator.comparing(InterviewSession::createdAt)),
                store(dir, "questions.json", InterviewQuestion.class, InterviewQuestion::id,
                        Comparator.comparingInt(InterviewQuestion::sequence)),
                store(dir, "answers.json", InterviewAnswer.class, InterviewAnswer::id,
                        Comparator.comparing(InterviewAnswer::createdAt)),
                store(dir, "evaluations.json", AnswerEvaluation.class, AnswerEvaluation::id,
                        Comparator.comparing(AnswerEvaluation::createdAt)),
                store(dir, "reports.json", com.dusk4d.interview.domain.InterviewReport.class,
                        com.dusk4d.interview.domain.InterviewReport::id,
                        Comparator.comparing(com.dusk4d.interview.domain.InterviewReport::createdAt)));

        InterviewRepository.SessionDetail detail = repository.detail("s-1").orElseThrow();
        assertThat(detail.session().status()).isEqualTo(SessionStatus.REPORT_READY);
        assertThat(detail.session().coveredTopics()).containsExactly("FinanceAgent 智能财务问答系统");
        assertThat(detail.questions()).hasSize(1);
        assertThat(detail.answers()).hasSize(1);
        assertThat(detail.evaluations()).hasSize(1);
        assertThat(detail.evaluationOf("a-1")).isPresent();
        assertThat(detail.evaluationOf("a-1").orElseThrow().dimensionScores())
                .containsKey("technical_correctness");
        assertThat(detail.answerOf("q-1").orElseThrow().content()).isEqualTo("我负责文档解析与检索链路");

        var report = repository.findReport("s-1").orElseThrow();
        assertThat(report.abilityRadar()).hasSize(1);
        assertThat(report.knowledgeGaps().get(0).topic()).isEqualTo("个人职责");
        assertThat(report.markdown()).contains("# 面试复盘报告");
        assertThat(dir.resolve("sessions.json.corrupt")).doesNotExist();
    }

    @Test
    @DisplayName("更新与删除会立即落盘")
    void updateAndDeleteArePersisted(@TempDir Path dir) {
        JsonFileStore<Resume> store = store(dir, "resumes.json", Resume.class, Resume::id,
                Comparator.comparing(Resume::createdAt));
        store.save(sampleResume("resume-1"));
        store.save(sampleResume("resume-2"));
        Resume updated = sampleResume("resume-1").withStatus(ResumeStatus.NEEDS_REVIEW, "置信度较低");
        store.save(updated);
        store.deleteById("resume-2");

        JsonFileStore<Resume> reloaded = store(dir, "resumes.json", Resume.class, Resume::id,
                Comparator.comparing(Resume::createdAt));
        assertThat(reloaded.count()).isEqualTo(1);
        assertThat(reloaded.findById("resume-1").orElseThrow().status()).isEqualTo(ResumeStatus.NEEDS_REVIEW);
        assertThat(reloaded.findById("resume-2")).isEmpty();
    }

    @Test
    @DisplayName("确实损坏的文件被隔离为 .corrupt，服务仍可启动")
    void corruptFileIsQuarantined(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("resumes.json");
        java.nio.file.Files.writeString(file, "{这不是合法 JSON");
        JsonFileStore<Resume> store = store(dir, "resumes.json", Resume.class, Resume::id,
                Comparator.comparing(Resume::createdAt));
        assertThat(store.count()).isZero();
        assertThat(dir.resolve("resumes.json.corrupt")).exists();
        // 之后仍然可以正常写入
        store.save(sampleResume("resume-1"));
        assertThat(store(dir, "resumes.json", Resume.class, Resume::id,
                Comparator.comparing(Resume::createdAt)).count()).isEqualTo(1);
    }
}
