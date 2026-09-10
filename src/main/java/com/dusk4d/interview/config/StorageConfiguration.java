package com.dusk4d.interview.config;

import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.storage.InMemoryStore;
import com.dusk4d.interview.storage.InterviewRepository;
import com.dusk4d.interview.storage.JsonFileStore;
import com.dusk4d.interview.storage.MapBackedInterviewRepository;
import com.dusk4d.interview.storage.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Function;

/**
 * 存储装配。
 *
 * <p>{@code app.storage.mode}：
 * <ul>
 *   <li>{@code file}（默认）：数据写入 {@code <app.home>/data/*.json}，重启可恢复；</li>
 *   <li>{@code memory}：纯内存，适合测试与一次性演示。</li>
 * </ul>
 * 两种模式共用同一套仓储接口，切换到 PostgreSQL 时只需新增一个 {@code InterviewRepository} 实现。
 */
@Configuration
public class StorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    @Bean
    public InterviewRepository interviewRepository(AppProperties properties, ObjectMapper objectMapper) {
        boolean inMemory = properties.storage().inMemory();
        StorageMode mode = inMemory ? StorageMode.MEMORY : StorageMode.FILE;
        log.info("存储模式：{}", mode);

        return new MapBackedInterviewRepository(
                store(mode, "resumes.json", Resume.class, objectMapper, Resume::id,
                        Comparator.comparing(Resume::createdAt).reversed()),
                store(mode, "sessions.json", InterviewSession.class, objectMapper, InterviewSession::id,
                        Comparator.comparing(InterviewSession::createdAt).reversed()),
                store(mode, "questions.json", InterviewQuestion.class, objectMapper, InterviewQuestion::id,
                        Comparator.comparingInt(InterviewQuestion::sequence)),
                store(mode, "answers.json", InterviewAnswer.class, objectMapper, InterviewAnswer::id,
                        Comparator.comparing(InterviewAnswer::createdAt)),
                store(mode, "evaluations.json", AnswerEvaluation.class, objectMapper, AnswerEvaluation::id,
                        Comparator.comparing(AnswerEvaluation::createdAt)),
                store(mode, "reports.json", InterviewReport.class, objectMapper, InterviewReport::id,
                        Comparator.comparing(InterviewReport::createdAt)));
    }

    private enum StorageMode {
        MEMORY, FILE
    }

    private <T> Store<T> store(StorageMode mode, String fileName, Class<T> type, ObjectMapper objectMapper,
                               Function<T, String> idExtractor, Comparator<T> order) {
        if (mode == StorageMode.MEMORY) {
            return new InMemoryStore<>(idExtractor, order);
        }
        return new JsonFileStore<>(dataDir().resolve(fileName), type, objectMapper, idExtractor, order);
    }

    /** 数据目录：优先 INTERVIEW_DATA_DIR / app.storage.data-dir，其次 <app.home>/data。 */
    private Path dataDir() {
        String configured = System.getenv("INTERVIEW_DATA_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("app.storage.data-dir", "");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        String home = System.getProperty("app.home", ".");
        return Path.of(home).resolve("data").toAbsolutePath();
    }
}
