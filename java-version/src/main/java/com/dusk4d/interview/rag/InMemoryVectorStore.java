package com.dusk4d.interview.rag;

import com.dusk4d.interview.llm.EmbeddingClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程内向量库：余弦相似度 + 标签命中加权。
 *
 * <p>实现要点：
 * <ul>
 *   <li>写入时不立即向量化，首次检索时惰性批量向量化（避免启动即阻塞，也便于测试注入 Embedding）；</li>
 *   <li>向量预先 L2 归一化，检索退化为点积；</li>
 *   <li>标签/主题精确命中给予小幅加权，补偿哈希向量在短语匹配上的不足；</li>
 *   <li>低于 {@code minScore} 直接过滤，遵循「检索为空就是空」，不硬凑上下文。</li>
 * </ul>
 */
@Component
public class InMemoryVectorStore implements VectorStore {

    private static final Pattern QUERY_TERM = Pattern.compile("[A-Za-z][A-Za-z0-9+#.]{2,}|[\\u4e00-\\u9fa5]{2,6}");
    /** 标签命中加权上限，避免压过语义相似度。 */
    private static final double LABEL_BOOST = 0.12;

    private final Map<String, TextChunk> chunks = new ConcurrentHashMap<>();
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
    private final EmbeddingClient embeddingClient;
    private final AtomicLong embeddedCount = new AtomicLong();
    private volatile boolean embedFailed;

    public InMemoryVectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @Override
    public void upsert(List<TextChunk> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) {
            return;
        }
        for (TextChunk chunk : newChunks) {
            if (chunk == null || chunk.id() == null) {
                continue;
            }
            chunks.put(chunk.id(), chunk);
            vectors.remove(chunk.id());
        }
    }

    @Override
    public void deleteByResume(String resumeId) {
        if (resumeId == null) {
            return;
        }
        List<String> ids = chunks.values().stream()
                .filter(c -> resumeId.equals(c.resumeId()))
                .map(TextChunk::id)
                .toList();
        ids.forEach(id -> {
            chunks.remove(id);
            vectors.remove(id);
        });
    }

    @Override
    public List<ScoredChunk> search(String query, ChunkFilter filter, int topK, double minScore) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        ChunkFilter effectiveFilter = filter == null ? ChunkFilter.any() : filter;
        List<TextChunk> candidates = chunks.values().stream()
                .filter(effectiveFilter::matches)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        ensureEmbedded(candidates);

        float[] queryVector = normalize(embeddingClient.embed(query));
        List<String> queryTerms = queryTerms(query);

        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (TextChunk chunk : candidates) {
            float[] vector = vectors.get(chunk.id());
            double score = vector == null ? 0d : dot(queryVector, vector);
            score += labelBoost(chunk, queryTerms);
            if (score >= minScore) {
                scored.add(ScoredChunk.dense(chunk, score, 0));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(sc -> sc.chunk().sourceOrder()));
        List<ScoredChunk> limited = scored.size() > topK ? scored.subList(0, topK) : scored;
        List<ScoredChunk> result = new ArrayList<>(limited.size());
        for (int i = 0; i < limited.size(); i++) {
            ScoredChunk sc = limited.get(i);
            result.add(new ScoredChunk(sc.chunk(), round(sc.score()), i + 1, 0, 0d));
        }
        return result;
    }

    @Override
    public List<TextChunk> chunks(ChunkFilter filter, int limit) {
        ChunkFilter effective = filter == null ? ChunkFilter.any() : filter;
        return chunks.values().stream()
                .filter(effective::matches)
                .sorted(Comparator.comparingInt(TextChunk::sourceOrder))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public void clear() {
        chunks.clear();
        vectors.clear();
        embeddedCount.set(0);
        embedFailed = false;
    }

    @Override
    public int size() {
        return chunks.size();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chunks", chunks.size());
        stats.put("embedded", embeddedCount.get());
        stats.put("embeddingProvider", embeddingClient.provider());
        stats.put("embeddingDimension", embeddingClient.dimension());
        stats.put("embedFailed", embedFailed);
        return stats;
    }

    // ---------------------------------------------------------------- internals

    private void ensureEmbedded(List<TextChunk> candidates) {
        List<TextChunk> missing = candidates.stream()
                .filter(c -> !vectors.containsKey(c.id()))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        try {
            List<String> texts = missing.stream().map(TextChunk::embeddingText).toList();
            List<float[]> vectorsOfTexts = embeddingClient.embed(texts);
            for (int i = 0; i < missing.size(); i++) {
                float[] vector = i < vectorsOfTexts.size() ? vectorsOfTexts.get(i) : new float[0];
                vectors.put(missing.get(i).id(), normalize(vector));
                embeddedCount.incrementAndGet();
            }
            embedFailed = false;
        } catch (RuntimeException e) {
            // 向量化失败不应让检索整体不可用：退化为纯关键词召回
            embedFailed = true;
            for (TextChunk chunk : missing) {
                vectors.putIfAbsent(chunk.id(), new float[0]);
            }
        }
    }

    private float[] normalize(float[] vector) {
        if (vector == null) {
            return new float[0];
        }
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum <= 0) {
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        float[] copy = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            copy[i] = vector[i] / norm;
        }
        return copy;
    }

    private double dot(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0) {
            return 0d;
        }
        int length = Math.min(a.length, b.length);
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /** 标签命中加权：项目名/主题/标题出现在查询中时小幅提升。 */
    private double labelBoost(TextChunk chunk, List<String> queryTerms) {
        double boost = 0d;
        boost += hitRatio(chunk.projectName(), queryTerms) * LABEL_BOOST;
        boost += hitRatio(chunk.topic(), queryTerms) * LABEL_BOOST * 0.6;
        boost += hitRatio(chunk.label(), queryTerms) * LABEL_BOOST * 0.4;
        return Math.min(boost, LABEL_BOOST * 1.4);
    }

    private double hitRatio(String field, List<String> queryTerms) {
        if (field == null || field.isBlank() || queryTerms.isEmpty()) {
            return 0d;
        }
        String lower = field.toLowerCase(Locale.ROOT);
        long hits = queryTerms.stream().filter(t -> lower.contains(t.toLowerCase(Locale.ROOT))).count();
        return queryTerms.isEmpty() ? 0d : (double) hits / queryTerms.size();
    }

    private List<String> queryTerms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = QUERY_TERM.matcher(query);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }

    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
