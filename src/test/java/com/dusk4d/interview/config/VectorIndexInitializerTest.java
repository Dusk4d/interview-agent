package com.dusk4d.interview.config;

import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.domain.ResumeStatus;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.storage.InMemoryStore;
import com.dusk4d.interview.storage.InterviewRepository;
import com.dusk4d.interview.storage.MapBackedInterviewRepository;
import com.dusk4d.interview.storage.Store;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期检索索引重建测试。
 *
 * <p>背景：向量库在内存中，简历在磁盘上。如果不重建索引，重启后项目面检索会全部为空
 * （只能走「回退到事实原文」的降级路径），来源引用也会消失。这里验证重建逻辑确实把
 * 已持久化的事实写回了向量库。
 */
@DisplayName("启动期检索索引重建")
class VectorIndexInitializerTest {

    @Test
    @DisplayName("已持久化的简历事实在启动时被重新写入向量库")
    void rebuildsIndexFromPersistedResumes() {
        // 模拟「进程重启后」的状态：向量库为空，仓储里有历史简历
        InMemoryVectorStoreStub vectorStore = new InMemoryVectorStoreStub();
        InterviewRepository repository = repositoryWith(resume("resume-1"));

        new VectorIndexInitializer(repository, vectorStore).run(null);

        assertThat(vectorStore.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("重建是幂等的：重复启动不会产生重复片段")
    void rebuildIsIdempotent() {
        InMemoryVectorStoreStub vectorStore = new InMemoryVectorStoreStub();
        InterviewRepository repository = repositoryWith(resume("resume-1"));
        VectorIndexInitializer initializer = new VectorIndexInitializer(repository, vectorStore);

        initializer.run(null);
        initializer.run(null);
        initializer.run(null);

        assertThat(vectorStore.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("没有历史简历时跳过，不报错")
    void skipsWhenNoResume() {
        InMemoryVectorStoreStub vectorStore = new InMemoryVectorStoreStub();
        InterviewRepository repository = repositoryWith();

        new VectorIndexInitializer(repository, vectorStore).run(null);

        assertThat(vectorStore.size()).isZero();
    }

    @Test
    @DisplayName("空内容的事实不进入索引")
    void skipsBlankFacts() {
        InMemoryVectorStoreStub vectorStore = new InMemoryVectorStoreStub();
        Resume resume = new Resume("resume-1", "f.txt", "txt", 10, "text", "text", ResumeStatus.PARSED,
                null, List.of(),
                List.of(new ResumeFact("resume-project-001", "resume-1", FactType.PROJECT, "项目", "有内容", 1, 0.9, List.of()),
                        new ResumeFact("resume-project-002", "resume-1", FactType.PROJECT, "空项目", "   ", 2, 0.5, List.of())),
                Instant.now(), Instant.now());
        InterviewRepository repository = repositoryWith(resume);

        new VectorIndexInitializer(repository, vectorStore).run(null);

        assertThat(vectorStore.size()).isEqualTo(1);
    }

    /**
     * 配置绑定验证（真实 Spring 上下文）。
     *
     * <p>存在的理由：真实进程回归时发现 {@code Environment.getProperty("app.llm.base-url")}
     * 能解析出正确值，但注入到服务里的 {@code AppProperties} 全是默认值——「配置源正确、绑定结果错误」。
     * 根因是 {@code AppProperties.Llm} 当时多了一个便捷构造器，绑定器无法确定规范构造器，
     * 于是静默放弃绑定。这类问题在纯单元测试里看不出来（手动 Binder 使用规范构造器），
     * 因此必须有这样一组启动真实上下文的断言守住它，同时覆盖环境变量/命令行参数的最终生效结果。
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "app.llm.base-url=http://127.0.0.1:11434/v1",
            "app.llm.chat-model=qwen3:1.7b",
            "app.llm.disable-thinking=true",
            "app.interview.max-questions=3",
            "app.retrieval.top-k=7",
            "app.storage.mode=memory",
            "app.privacy.mask-name=true"
    })
    @DisplayName("AppProperties 绑定真实 Spring 上下文")
    class BindingTest {

        @Autowired
        AppProperties properties;

        @Autowired
        org.springframework.core.env.Environment environment;

        @Test
        @DisplayName("外部属性必须绑定到 AppProperties，而不是静默保留默认值")
        void externalPropertiesAreBound() {
            // 配置源本身可解析
            assertThat(environment.getProperty("app.llm.base-url")).isEqualTo("http://127.0.0.1:11434/v1");

            // 关键断言：绑定结果必须与配置源一致
            assertThat(properties.llm().baseUrl())
                    .as("绑定结果必须与配置源一致（曾因多构造器导致绑定被静默放弃）")
                    .isEqualTo("http://127.0.0.1:11434/v1");
            assertThat(properties.llm().chatModel()).isEqualTo("qwen3:1.7b");
            assertThat(properties.llm().disableThinking()).isTrue();
            assertThat(properties.llm().shouldDisableThinking()).isTrue();
            assertThat(properties.interview().maxQuestions()).isEqualTo(3);
            assertThat(properties.retrieval().topK()).isEqualTo(7);
            assertThat(properties.storage().mode()).isEqualTo("memory");
            assertThat(properties.storage().inMemory()).isTrue();
            assertThat(properties.privacy().maskName()).isTrue();
        }

        @Test
        @DisplayName("每个子配置块都必须有具体实例，不能是默认兜底产生的空壳")
        void allSectionsAreBound() {
            assertThat(properties.llm()).isNotNull();
            assertThat(properties.embedding()).isNotNull();
            assertThat(properties.retrieval()).isNotNull();
            assertThat(properties.interview()).isNotNull();
            assertThat(properties.privacy()).isNotNull();
            assertThat(properties.storage()).isNotNull();
            assertThat(properties.parser()).isNotNull();
            // 未显式配置的字段应保留配置文件中的值，而不是 null
            assertThat(properties.llm().maxTokens()).isEqualTo(1600);
            assertThat(properties.embedding().mode()).isEqualTo("local");
        }

        @Test
        @DisplayName("每个配置 record 只应有一个构造器（多构造器会让绑定静默失效）")
        void recordsHaveSingleConstructor() {
            for (Class<?> type : List.of(AppProperties.class, AppProperties.Llm.class,
                    AppProperties.Embedding.class, AppProperties.Retrieval.class,
                    AppProperties.Interview.class, AppProperties.Privacy.class,
                    AppProperties.Storage.class, AppProperties.Parser.class)) {
                assertThat(type.getDeclaredConstructors())
                        .as("%s 不应定义额外构造器，否则 Spring 绑定器无法确定规范构造器", type.getSimpleName())
                        .hasSize(1);
            }
        }
    }

    // ---------------------------------------------------------------- 测试替身

    /** 只统计片段数量的向量库替身，避免测试依赖真实向量化。 */
    private static final class InMemoryVectorStoreStub implements VectorStore {
        private final java.util.Map<String, TextChunkHolder> chunks = new java.util.LinkedHashMap<>();

        @Override
        public void upsert(List<com.dusk4d.interview.rag.TextChunk> newChunks) {
            newChunks.forEach(chunk -> chunks.put(chunk.id(), new TextChunkHolder(chunk)));
        }

        @Override
        public void deleteByResume(String resumeId) {
            chunks.values().removeIf(holder -> resumeId.equals(holder.chunk.resumeId()));
        }

        @Override
        public List<com.dusk4d.interview.rag.ScoredChunk> search(String query, ChunkFilter filter, int topK,
                                                                 double minScore) {
            return List.of();
        }

        @Override
        public List<com.dusk4d.interview.rag.TextChunk> chunks(ChunkFilter filter, int limit) {
            return chunks.values().stream().map(holder -> holder.chunk).limit(limit).toList();
        }

        @Override
        public void clear() {
            chunks.clear();
        }

        @Override
        public int size() {
            return chunks.size();
        }

        @Override
        public java.util.Map<String, Object> stats() {
            return java.util.Map.of("chunks", chunks.size());
        }

        private record TextChunkHolder(com.dusk4d.interview.rag.TextChunk chunk) {
        }
    }

    private static Resume resume(String id) {
        return new Resume(id, "张伟-简历.txt", "txt", 100, "text", "text", ResumeStatus.PARSED, null,
                List.of(),
                List.of(new ResumeFact("resume-project-001", id, FactType.PROJECT, "FinanceAgent",
                                "个人职责：负责文档解析与检索链路", 1, 0.9, List.of("Java")),
                        new ResumeFact("resume-skill-002", id, FactType.SKILL, "技能",
                                "Java、Spring Boot、Redis", 2, 0.8, List.of())),
                Instant.now(), Instant.now());
    }

    private static InterviewRepository repositoryWith(Resume... resumes) {
        Store<Resume> resumeStore = new InMemoryStore<>(Resume::id);
        for (Resume resume : resumes) {
            resumeStore.save(resume);
        }
        return new MapBackedInterviewRepository(
                resumeStore,
                new InMemoryStore<>(s -> s.id(), Comparator.comparing(s -> s.createdAt())),
                new InMemoryStore<>(q -> q.id(), Comparator.comparingInt(q -> q.sequence())),
                new InMemoryStore<>(a -> a.id(), Comparator.comparing(a -> a.createdAt())),
                new InMemoryStore<>(e -> e.id(), Comparator.comparing(e -> e.createdAt())),
                new InMemoryStore<>(r -> r.id(), Comparator.comparing(r -> r.createdAt())));
    }
}
