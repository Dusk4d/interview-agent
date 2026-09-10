package com.dusk4d.interview.rag;

/**
 * 检索命中的片段。
 *
 * @param chunk      片段
 * @param score      相似度（0~1，越大越相关）
 * @param denseRank  向量召回排名（从 1 开始）
 * @param lexicalRank 关键词召回排名（从 1 开始，未命中为 0）
 * @param fusedScore 融合后的排序分（RRF）
 */
public record ScoredChunk(
        TextChunk chunk,
        double score,
        int denseRank,
        int lexicalRank,
        double fusedScore
) {
    public static ScoredChunk dense(TextChunk chunk, double score, int rank) {
        return new ScoredChunk(chunk, score, rank, 0, 0d);
    }

    public ScoredChunk withFusion(double fused) {
        return new ScoredChunk(chunk, score, denseRank, lexicalRank, fused);
    }

    public String citation() {
        return chunk.citation();
    }
}
