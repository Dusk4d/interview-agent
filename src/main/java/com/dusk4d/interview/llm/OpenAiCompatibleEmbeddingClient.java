package com.dusk4d.interview.llm;

import com.dusk4d.interview.error.LlmException;

import java.util.List;

/**
 * 向量化客户端（OpenAI 兼容 {@code /embeddings}）。
 *
 * <p>与聊天客户端分离，是因为两者的失败语义不同：向量化失败可以静默降级，
 * 而聊天失败必须让用户看到明确提示。
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final int batchSize;
    private final java.net.http.HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final java.time.Duration readTimeout;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model, int dimension, int batchSize,
                                           int connectTimeoutMs, int readTimeoutMs,
                                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed.isEmpty() ? "http://127.0.0.1:1234/v1" : trimmed;
        this.apiKey = apiKey == null || apiKey.isBlank() ? "not-needed" : apiKey;
        this.model = model;
        this.dimension = dimension <= 0 ? 768 : dimension;
        this.batchSize = batchSize <= 0 ? 16 : batchSize;
        this.readTimeout = java.time.Duration.ofMillis(Math.max(1000, readTimeoutMs));
        this.objectMapper = objectMapper;
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(Math.max(500, connectTimeoutMs)))
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new java.util.ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            result.addAll(embedBatch(texts.subList(start, end)));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> batch) {
        var root = objectMapper.createObjectNode();
        root.put("model", model);
        var input = root.putArray("input");
        for (String text : batch) {
            input.add(text == null ? "" : text);
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw LlmException.badResponse("构造向量化请求失败：" + e.getMessage(), e);
        }
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/embeddings"))
                .timeout(readTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            var response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw LlmException.badResponse("向量化接口返回 HTTP " + response.statusCode(), null);
            }
            return parse(response.body(), batch.size());
        } catch (java.net.http.HttpTimeoutException e) {
            throw LlmException.timeout("向量化请求超时", e);
        } catch (java.net.ConnectException e) {
            throw LlmException.connection("无法连接向量化服务（" + baseUrl + "）", e);
        } catch (java.io.IOException e) {
            throw LlmException.badResponse("向量化调用失败：" + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.badResponse("向量化调用被中断", e);
        }
    }

    private List<float[]> parse(String body, int expected) {
        try {
            var root = objectMapper.readTree(body);
            var data = root.path("data");
            if (!data.isArray() || data.size() != expected) {
                throw LlmException.badResponse("向量化响应条数不匹配：期望 " + expected + "，实际 " + data.size(), null);
            }
            List<float[]> vectors = new java.util.ArrayList<>(expected);
            for (var item : data) {
                var values = item.path("embedding");
                float[] vector = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    vector[i] = (float) values.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw LlmException.badResponse("向量化响应不是合法 JSON", e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String provider() {
        return "openai-compatible-embedding";
    }
}
