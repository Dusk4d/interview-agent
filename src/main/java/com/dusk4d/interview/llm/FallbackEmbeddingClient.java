package com.dusk4d.interview.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 可降级的向量化客户端。
 *
 * <p>首选远端向量模型（质量更好），失败时自动切换本地哈希向量并记录一次告警，
 * 保证「检索链路永远可用」。降级后维度可能与远端不同，
 * 因此 {@link #dimension()} 返回远端维度仅用于展示，真正入库的维度以实际产出为准。
 */
public class FallbackEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackEmbeddingClient.class);

    private final EmbeddingClient primary;
    private final EmbeddingClient fallback;
    private volatile boolean degraded;
    private volatile String lastError;

    public FallbackEmbeddingClient(EmbeddingClient primary, EmbeddingClient fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!degraded) {
            try {
                List<float[]> vectors = primary.embed(texts);
                if (vectors.size() == texts.size()) {
                    return vectors;
                }
                log.warn("远端向量化返回条数不匹配（{} != {}），本次降级为本地哈希向量",
                        vectors.size(), texts.size());
            } catch (RuntimeException e) {
                degraded = true;
                lastError = e.getMessage();
                log.warn("远端向量化不可用，已自动降级为本地哈希向量：{}", e.getMessage());
            }
        }
        return fallback.embed(texts);
    }

    @Override
    public int dimension() {
        return degraded ? fallback.dimension() : primary.dimension();
    }

    @Override
    public String provider() {
        return degraded ? fallback.provider() + "(fallback)" : primary.provider();
    }

    /** 是否已降级；供健康检查接口展示。 */
    public boolean degraded() {
        return degraded;
    }

    public String lastError() {
        return lastError;
    }
}
