package com.dusk4d.interview.rag;

import java.util.List;
import java.util.Map;

/**
 * 向量库抽象。
 *
 * <p>第一版是进程内的余弦相似度检索（{@code InMemoryVectorStore}）：简历只有几十个片段、
 * 知识库几百条，暴力检索足够快，而且离线可跑、结果可复现。
 * 需要横向扩展时替换为 pgvector 实现即可，上层不受影响。
 */
public interface VectorStore {

    /** 写入/覆盖片段（幂等：同 ID 覆盖）。 */
    void upsert(List<TextChunk> chunks);

    /** 删除某份简历的所有片段。 */
    void deleteByResume(String resumeId);

    /**
     * 相似度检索。
     *
     * @param query     查询文本
     * @param filter    元数据过滤条件
     * @param topK      返回条数
     * @param minScore  最小相似度阈值，低于该值不返回（避免「检索为空却硬凑」）
     */
    List<ScoredChunk> search(String query, ChunkFilter filter, int topK, double minScore);

    /** 仅按元数据过滤返回片段（关键词召回用），不做相似度计算。 */
    List<TextChunk> chunks(ChunkFilter filter, int limit);

    /** 清空（测试使用）。 */
    void clear();

    /** 当前片段数。 */
    int size();

    /**
     * 元数据过滤条件。
     *
     * @param resumeId  限定简历（知识库检索时为 null）
     * @param kinds     限定来源类型
     * @param factTypes 限定区块类型（可空）
     */
    record ChunkFilter(String resumeId, List<ChunkKind> kinds, List<com.dusk4d.interview.domain.FactType> factTypes) {

        public ChunkFilter {
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            factTypes = factTypes == null ? List.of() : List.copyOf(factTypes);
        }

        public static ChunkFilter resumeFacts(String resumeId) {
            return new ChunkFilter(resumeId, List.of(ChunkKind.RESUME_FACT), List.of());
        }

        public static ChunkFilter resumeFacts(String resumeId, List<com.dusk4d.interview.domain.FactType> factTypes) {
            return new ChunkFilter(resumeId, List.of(ChunkKind.RESUME_FACT), factTypes);
        }

        public static ChunkFilter knowledge() {
            return new ChunkFilter(null, List.of(ChunkKind.KNOWLEDGE), List.of());
        }

        public static ChunkFilter any() {
            return new ChunkFilter(null, List.of(), List.of());
        }

        public boolean matches(TextChunk chunk) {
            if (resumeId != null && !resumeId.equals(chunk.resumeId())) {
                return false;
            }
            if (!kinds.isEmpty() && !kinds.contains(chunk.kind())) {
                return false;
            }
            return factTypes.isEmpty() || factTypes.contains(chunk.factType());
        }
    }

    /** 供实现复用的空统计。 */
    Map<String, Object> stats();
}
