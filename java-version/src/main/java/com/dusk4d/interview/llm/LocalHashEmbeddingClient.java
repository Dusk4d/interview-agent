package com.dusk4d.interview.llm;

import com.dusk4d.interview.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程内确定性向量化（特性哈希 + 子线性 TF + L2 归一化）。
 *
 * <p>为什么需要它：
 * <ol>
 *   <li>离线/无本地模型时系统仍要能完成整条检索链路，而不是退化成一锅粥；</li>
 *   <li>测试必须可复现——同一输入永远得到同一向量与同一排序；</li>
 *   <li>作为远程 Embedding 的兜底，本地模型未启动时自动降级。</li>
 * </ol>
 *
 * <p>它不是语义模型：同义改写召回弱于真实 Embedding。因此 {@code DocumentRetriever}
 * 把向量召回与关键词召回做 RRF 融合，弥补这一短板；生产环境建议在
 * {@code app.embedding.mode=remote} 下使用 nomic-embed-text 等真实向量模型。
 *
 * <p>本类刻意不做 Spring 组件扫描注册：它有两个构造方式（配置对象 / 显式维度），
 * 由 {@code LlmConfiguration#embeddingClient} 显式装配，保证维度只有一个来源。
 */
public class LocalHashEmbeddingClient implements EmbeddingClient {

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]|[A-Za-z]+|\\d+");
    private static final double SUBLINEAR_K = 1.5;
    private static final int BUCKETS = 4;

    private final int dimension;

    public LocalHashEmbeddingClient(AppProperties properties) {
        this(properties.embedding().resolvedDimension());
    }

    public LocalHashEmbeddingClient(int dimension) {
        this.dimension = Math.max(32, dimension);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embedOne(text));
        }
        return result;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String provider() {
        return "local-hash";
    }

    private float[] embedOne(String text) {
        float[] vector = new float[dimension];
        for (String term : tokenize(text)) {
            double weight = 1.0 + Math.log(1.0 + term.length());
            long hash = fnv1a(term);
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                long mixed = mix(hash + 0x9E3779B97F4A7C15L * (bucket + 1));
                int index = (int) Math.floorMod(mixed, dimension);
                double sign = ((mixed >>> 63) & 1L) == 0L ? 1.0 : -1.0;
                vector[index] += (float) (sign * weight);
            }
        }
        normalize(vector);
        return vector;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> raw = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(lower);
        while (matcher.find()) {
            raw.add(matcher.group());
        }
        // 中文按单字（因为无分词器），加入相邻二元组以保留局部词序信息
        Set<String> terms = new LinkedHashSet<>(raw);
        for (int i = 0; i + 1 < raw.size(); i++) {
            String a = raw.get(i);
            String b = raw.get(i + 1);
            if (isHan(a) || isHan(b)) {
                terms.add(a + b);
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean isHan(String token) {
        return token.length() == 1 && Character.UnicodeScript.of(token.charAt(0)) == Character.UnicodeScript.HAN;
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum <= 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    private long fnv1a(String term) {
        byte[] bytes = term.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 词频统计（供关键词召回 BM25 风格打分复用）。 */
    public Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String term : tokenize(text)) {
            frequency.merge(term, 1, Integer::sum);
        }
        return frequency;
    }

    /** 暴露摘要算法，避免被误用为安全用途时缺少显式说明。 */
    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
