package com.dusk4d.interview.rag;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.llm.LocalHashEmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("向量化与向量检索")
class VectorStoreTest {

    private static AppProperties props() {
        return new AppProperties(null, null, null, null, null, null, null);
    }

    private static InMemoryVectorStore store() {
        return new InMemoryVectorStore(new LocalHashEmbeddingClient(128));
    }

    private static TextChunk chunk(String id, String text, String project, int order) {
        return TextChunk.resumeFact(id, "resume-1",
                com.dusk4d.interview.domain.FactType.PROJECT, project, project, text, order, List.of());
    }

    @Test
    @DisplayName("本地哈希向量：同文本同向量、维度固定、已归一化")
    void localEmbeddingIsDeterministic() {
        LocalHashEmbeddingClient client = new LocalHashEmbeddingClient(64);
        float[] first = client.embed("Redis 分布式锁在订单幂等中的应用");
        float[] second = client.embed("Redis 分布式锁在订单幂等中的应用");
        assertThat(first).hasSize(64).containsExactly(second);

        double norm = 0;
        for (float v : first) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("语义相近的文本相似度高于无关文本")
    void similarTextScoresHigher() {
        LocalHashEmbeddingClient client = new LocalHashEmbeddingClient(256);
        float[] query = client.embed("Redis 分布式锁 幂等");
        float[] related = client.embed("使用 Redis 分布式锁保证下单幂等，避免重复扣减库存");
        float[] unrelated = client.embed("Vue3 前端组件的响应式渲染原理与 diff 算法");
        assertThat(dot(query, related)).isGreaterThan(dot(query, unrelated));
    }

    @Test
    @DisplayName("检索按元数据过滤：项目面不会召回知识库条目")
    void searchRespectsFilter() {
        InMemoryVectorStore store = store();
        store.upsert(List.of(
                chunk("resume-project-001", "使用 Redis 分布式锁保证下单幂等", "秒杀系统", 1),
                chunk("resume-project-002", "实现点赞与热度排行，使用 Redis ZSet", "摄影社区", 2),
                TextChunk.knowledge("knowledge-redis-lock", "Redis", "分布式锁", Difficulty.HARD,
                        "Redis 分布式锁的正确实现",
                        "加锁使用 SET key value NX PX ttl，解锁必须用 Lua 脚本先比对 value 再删除。",
                        List.of("NX PX", "Lua 解锁"))));

        List<ScoredChunk> facts = store.search("Redis 分布式锁", VectorStore.ChunkFilter.resumeFacts("resume-1"), 5, 0.0);
        assertThat(facts).isNotEmpty();
        assertThat(facts).allSatisfy(sc -> assertThat(sc.chunk().kind()).isEqualTo(ChunkKind.RESUME_FACT));

        List<ScoredChunk> knowledge = store.search("Redis 分布式锁", VectorStore.ChunkFilter.knowledge(), 5, 0.0);
        assertThat(knowledge).isNotEmpty();
        assertThat(knowledge).allSatisfy(sc -> assertThat(sc.chunk().kind()).isEqualTo(ChunkKind.KNOWLEDGE));
    }

    @Test
    @DisplayName("按简历过滤：不会命中其他简历的片段")
    void searchIsolatesResumes() {
        InMemoryVectorStore store = store();
        TextChunk other = TextChunk.resumeFact("resume-project-900", "resume-2", 
                com.dusk4d.interview.domain.FactType.PROJECT, "其他项目", "其他项目",
                "使用 Redis 分布式锁", 1, List.of());
        store.upsert(List.of(chunk("resume-project-001", "使用 Redis 分布式锁", "秒杀系统", 1), other));

        List<ScoredChunk> hits = store.search("Redis 分布式锁", VectorStore.ChunkFilter.resumeFacts("resume-1"), 5, 0.0);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(sc -> assertThat(sc.chunk().resumeId()).isEqualTo("resume-1"));
    }

    @Test
    @DisplayName("低于最小相似度的片段被过滤（检索为空就是空）")
    void respectsMinScore() {
        InMemoryVectorStore store = store();
        store.upsert(List.of(chunk("resume-project-001", "使用 Redis 分布式锁", "秒杀系统", 1)));
        assertThat(store.search("完全不相关的量子物理内容", VectorStore.ChunkFilter.any(), 5, 0.9)).isEmpty();
    }

    @Test
    @DisplayName("upsert 幂等：同 ID 覆盖，不产生重复条目")
    void upsertIsIdempotent() {
        InMemoryVectorStore store = store();
        store.upsert(List.of(chunk("resume-project-001", "第一版内容", "项目A", 1)));
        store.upsert(List.of(chunk("resume-project-001", "第二版内容", "项目A", 1)));
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.chunks(VectorStore.ChunkFilter.any(), 10).get(0).text()).isEqualTo("第二版内容");
    }

    @Test
    @DisplayName("删除简历片段后不可再检索到")
    void deleteByResume() {
        InMemoryVectorStore store = store();
        store.upsert(List.of(chunk("resume-project-001", "使用 Redis 分布式锁", "秒杀系统", 1)));
        store.deleteByResume("resume-1");
        assertThat(store.size()).isZero();
        assertThat(store.search("Redis", VectorStore.ChunkFilter.any(), 5, 0.0)).isEmpty();
    }

    @Test
    @DisplayName("知识库条目可统计主题分布")
    void statsIncludesProvider() {
        InMemoryVectorStore store = store();
        store.upsert(List.of(chunk("resume-project-001", "内容", "项目A", 1)));
        Map<String, Object> stats = store.stats();
        assertThat(stats).containsEntry("chunks", 1)
                .containsEntry("embeddingProvider", "local-hash");
        assertThat((Integer) stats.get("embeddingDimension")).isEqualTo(128);
    }

    @Test
    @DisplayName("配置访问器提供合理默认值")
    void propertiesDefaults() {
        AppProperties properties = props();
        assertThat(properties.retrieval().resolvedTopK()).isEqualTo(5);
        assertThat(properties.interview().resolvedMaxQuestions()).isEqualTo(8);
        assertThat(properties.embedding().resolvedDimension()).isEqualTo(256);
        assertThat(properties.storage().inMemory()).isFalse();
        assertThat(properties.llm().resolvedBaseUrl()).isEqualTo("http://127.0.0.1:1234/v1");
    }

    private double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
