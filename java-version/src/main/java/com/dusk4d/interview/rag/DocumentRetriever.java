package com.dusk4d.interview.rag;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.llm.EmbeddingClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实/知识点检索（RAG 的召回层）。
 *
 * <p>混合检索：向量召回（语义）+ 关键词召回（字面），用 RRF 融合。
 * 这样做是因为本项目的向量要么是哈希向量（离线兜底），要么是通用 embedding 模型，
 * 单独使用都可能漏掉「Redis 分布式锁」这类精确术语，加一路字面召回显著稳定。
 *
 * <p>检索为空时返回空上下文而不是硬凑最近邻——遵循方案书「检索为空不能被当作有依据」的要求。
 */
@Component
public class DocumentRetriever {

    private static final Pattern LEXICAL_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{1,20}|[\\u4e00-\\u9fa5]{2,8}");
    private static final int RRF_K = 60;
    private static final double DENSE_WEIGHT = 0.65;
    private static final double LEXICAL_WEIGHT = 0.35;
    /** 关键词召回的最大候选规模（防止把整库拉进来算分）。 */
    private static final int LEXICAL_CANDIDATE_LIMIT = 400;

    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final AppProperties properties;

    public DocumentRetriever(VectorStore vectorStore, EmbeddingClient embeddingClient, AppProperties properties) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
    }

    /**
     * 检索结果。
     *
     * @param chunks    命中片段（按融合分排序）
     * @param citations 用于展示的引用来源
     * @param context   拼接好的上下文（受 maxContextChars 限制）
     * @param empty     是否为空召回
     */
    public record RetrievalResult(List<ScoredChunk> chunks, List<String> citations, String context, boolean empty) {

        public RetrievalResult {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            citations = citations == null ? List.of() : List.copyOf(citations);
            context = context == null ? "" : context;
        }

        /** 空召回（命名避免与 record 的 empty() 访问器冲突）。 */
        public static RetrievalResult none() {
            return new RetrievalResult(List.of(), List.of(), "", true);
        }

        /** 是否没有命中任何片段。 */
        public boolean noHits() {
            return chunks.isEmpty();
        }

        public List<String> chunkIds() {
            return chunks.stream().map(sc -> sc.chunk().id()).toList();
        }

        public List<String> snippets(int maxChars) {
            List<String> snippets = new ArrayList<>();
            for (ScoredChunk scored : chunks) {
                String text = scored.chunk().text() == null
                        ? "" : scored.chunk().text().replaceAll("\\s+", " ").trim();
                if (text.length() > maxChars) {
                    text = text.substring(0, maxChars) + "…";
                }
                snippets.add(scored.citation() + " ｜ " + text);
            }
            return snippets;
        }
    }

    /** 项目面：检索个人事实。 */
    public RetrievalResult retrieveFacts(String resumeId, String query) {
        return retrieve(query, VectorStore.ChunkFilter.resumeFacts(resumeId), properties.retrieval().resolvedTopK());
    }

    /** 八股面：检索基础知识。 */
    public RetrievalResult retrieveKnowledge(String query) {
        return retrieve(query, VectorStore.ChunkFilter.knowledge(),
                properties.retrieval().resolvedKnowledgeTopK());
    }

    /** 混合面试：两类同时检索，引用中会标注来源区分。 */
    public RetrievalResult retrieveMixed(String resumeId, String query) {
        int topK = properties.retrieval().resolvedTopK();
        RetrievalResult facts = retrieve(query, VectorStore.ChunkFilter.resumeFacts(resumeId), topK);
        RetrievalResult knowledge = retrieve(query, VectorStore.ChunkFilter.knowledge(), Math.max(1, topK / 2));
        List<ScoredChunk> merged = new ArrayList<>(facts.chunks());
        merged.addAll(knowledge.chunks());
        merged.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed()
                .thenComparing(sc -> sc.chunk().sourceOrder()));
        if (merged.isEmpty()) {
            return RetrievalResult.none();
        }
        return new RetrievalResult(merged, citationsOf(merged), buildContext(merged), false);
    }

    /** 通用检索入口。 */
    public RetrievalResult retrieve(String query, VectorStore.ChunkFilter filter, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return RetrievalResult.none();
        }
        double minScore = properties.retrieval().minScore();
        int candidateSize = Math.max(topK * 3, topK);
        List<ScoredChunk> dense = vectorStore.search(query, filter, candidateSize, minScore);
        List<ScoredChunk> lexical = lexicalSearch(query, filter, candidateSize);
        List<ScoredChunk> fused = fuse(dense, lexical, topK);
        if (fused.isEmpty()) {
            return RetrievalResult.none();
        }
        return new RetrievalResult(fused, citationsOf(fused), buildContext(fused), false);
    }

    // ---------------------------------------------------------------- 融合与关键词召回

    private List<ScoredChunk> fuse(List<ScoredChunk> dense, List<ScoredChunk> lexical, int topK) {
        List<ScoredChunk> all = new ArrayList<>(dense);
        all.addAll(lexical);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();
        for (ScoredChunk scored : all) {
            byId.merge(scored.chunk().id(), scored, (a, b) -> {
                double score = Math.max(a.score(), b.score());
                int denseRank = a.denseRank() > 0 ? a.denseRank() : b.denseRank();
                int lexicalRank = a.lexicalRank() > 0 ? a.lexicalRank() : b.lexicalRank();
                return new ScoredChunk(a.chunk(), score, denseRank, lexicalRank, 0d);
            });
        }
        List<ScoredChunk> fused = new ArrayList<>(byId.size());
        for (ScoredChunk scored : byId.values()) {
            double fusedScore = 0d;
            if (scored.denseRank() > 0) {
                fusedScore += DENSE_WEIGHT * (1.0 / (RRF_K + scored.denseRank()));
            }
            if (scored.lexicalRank() > 0) {
                fusedScore += LEXICAL_WEIGHT * (1.0 / (RRF_K + scored.lexicalRank()));
            }
            fused.add(scored.withFusion(fusedScore));
        }
        fused.sort(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed()
                .thenComparing(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .thenComparing(sc -> sc.chunk().sourceOrder()));
        return fused.size() > topK ? new ArrayList<>(fused.subList(0, topK)) : fused;
    }

    /** 关键词召回：查询 token 覆盖率 + 项目/主题名命中，作为向量召回的补充。 */
    List<ScoredChunk> lexicalSearch(String query, VectorStore.ChunkFilter filter, int topK) {
        List<String> tokens = lexicalTokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<TextChunk> candidates = vectorStore.chunks(filter, LEXICAL_CANDIDATE_LIMIT);
        List<ScoredChunk> scored = new ArrayList<>();
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (TextChunk chunk : candidates) {
            String haystack = ((chunk.text() == null ? "" : chunk.text()) + " "
                    + String.join(" ", chunk.extra()) + " "
                    + (chunk.projectName() == null ? "" : chunk.projectName()) + " "
                    + (chunk.label() == null ? "" : chunk.label()) + " "
                    + (chunk.topic() == null ? "" : chunk.topic())).toLowerCase(Locale.ROOT);
            double matched = 0d;
            for (String token : tokens) {
                if (haystack.contains(token)) {
                    matched += 1.0 + Math.min(1.0, token.length() / 6.0);
                }
            }
            if (chunk.projectName() != null && !chunk.projectName().isBlank()
                    && lowerQuery.contains(chunk.projectName().toLowerCase(Locale.ROOT))) {
                matched += 2.0;
            }
            if (chunk.label() != null && !chunk.label().isBlank()
                    && lowerQuery.contains(chunk.label().toLowerCase(Locale.ROOT))) {
                matched += 1.5;
            }
            if (chunk.topic() != null && !chunk.topic().isBlank()
                    && lowerQuery.contains(chunk.topic().toLowerCase(Locale.ROOT))) {
                matched += 1.0;
            }
            if (matched > 0) {
                double norm = matched / (tokens.size() + 2.0);
                scored.add(new ScoredChunk(chunk, Math.min(1.0, norm), 0, 0, 0d));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(sc -> sc.chunk().sourceOrder()));
        List<ScoredChunk> limited = scored.size() > topK ? scored.subList(0, topK) : scored;
        List<ScoredChunk> result = new ArrayList<>(limited.size());
        for (int i = 0; i < limited.size(); i++) {
            ScoredChunk sc = limited.get(i);
            result.add(new ScoredChunk(sc.chunk(), sc.score(), 0, i + 1, 0d));
        }
        return result;
    }

    private List<String> lexicalTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = LEXICAL_TOKEN.matcher(query);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        // 中文没有分词器：补充 2-gram，提升「分布式锁」这类术语的召回
        List<String> extra = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() > 2 && token.chars()
                    .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i + 2 <= token.length(); i++) {
                    extra.add(token.substring(i, i + 2));
                }
            }
        }
        tokens.addAll(extra);
        return new ArrayList<>(tokens);
    }

    private List<String> citationsOf(List<ScoredChunk> chunks) {
        Set<String> citations = new LinkedHashSet<>();
        for (ScoredChunk scored : chunks) {
            citations.add(scored.citation());
        }
        return new ArrayList<>(citations);
    }

    /** 拼接上下文：带编号与来源，方便模型在反馈中引用，也方便用户核对。 */
    private String buildContext(List<ScoredChunk> chunks) {
        int limit = properties.retrieval().maxContextChars();
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (ScoredChunk scored : chunks) {
            String text = scored.chunk().text() == null ? "" : scored.chunk().text().trim();
            String block = "[" + index + "] " + scored.citation() + "\n" + text + "\n";
            if (sb.length() + block.length() > limit) {
                break;
            }
            sb.append(block);
            index++;
        }
        return sb.toString().strip();
    }

    /** 当前使用的向量化提供方，用于前端展示与健康检查。 */
    public String embeddingProvider() {
        return embeddingClient.provider();
    }
}
