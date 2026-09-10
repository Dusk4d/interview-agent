package com.dusk4d.interview.llm;

import java.util.List;

/**
 * 文本向量化入口。
 *
 * <p>实现：{@code LocalHashEmbeddingClient}（离线确定性哈希，测试默认）、
 * {@code OpenAiCompatibleEmbeddingClient}（LM Studio + nomic-embed-text）。
 * 远程不可用时由 {@code FallbackEmbeddingClient} 自动降级，保证系统仍可运行。
 */
public interface EmbeddingClient {

    /** 批量向量化，返回顺序与入参一致。 */
    List<float[]> embed(List<String> texts);

    /** 单条向量化。 */
    default float[] embed(String text) {
        List<float[]> vectors = embed(List.of(text == null ? "" : text));
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }

    int dimension();

    String provider();
}
