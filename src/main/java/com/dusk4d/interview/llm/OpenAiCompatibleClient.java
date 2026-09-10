package com.dusk4d.interview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dusk4d.interview.error.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容协议的模型客户端。
 *
 * <p>适配范围：LM Studio（{@code http://127.0.0.1:1234/v1}，推荐本地开发）、
 * Ollama（{@code http://127.0.0.1:11434/v1}）、以及任何兼容 {@code /chat/completions}
 * 的云端服务。使用 JDK 自带 {@link HttpClient}，不引入额外 SDK，
 * 既能保证离线可编译，也把协议细节控制在一个文件内。
 *
 * <p>错误映射：连接失败 / 超时 / 非 2xx / 响应体非法 都转换为带明确错误码的
 * {@link LlmException}，上层据此给出用户可读提示或降级，而不是抛 500 堆栈。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration readTimeout;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, int maxTokens,
                                  int connectTimeoutMs, int readTimeoutMs, ObjectMapper objectMapper) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.readTimeout = Duration.ofMillis(Math.max(1000, readTimeoutMs));
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.nanoTime();
        String body = buildBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(readTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = elapsedMs(start);
            if (response.statusCode() / 100 != 2) {
                throw LlmException.badResponse(
                        "模型服务返回 HTTP " + response.statusCode() + "：" + abbreviate(response.body()), null);
            }
            return parse(response.body(), elapsed);
        } catch (HttpTimeoutException e) {
            throw LlmException.timeout("模型响应超时（" + readTimeout.toSeconds() + "s），请稍后重试或更换更小的模型。", e);
        } catch (ConnectException e) {
            throw LlmException.connection("无法连接模型服务（" + baseUrl + "）。请确认本地模型（LM Studio / Ollama）已启动。", e);
        } catch (IOException e) {
            // HttpClient 在连接层失败时也可能抛出被包装的 IOException
            if (isConnectionFailure(e)) {
                throw LlmException.connection("无法连接模型服务（" + baseUrl + "）。请确认本地模型已启动。", e);
            }
            throw LlmException.badResponse("模型调用失败：" + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.badResponse("模型调用被中断。", e);
        }
    }

    private boolean isConnectionFailure(IOException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketException && message.contains("connect")
                || message.contains("connection refused")
                || message.contains("failed to connect");
    }

    private String buildBody(LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", request.temperature());
        root.put("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : maxTokens);
        root.put("stream", false);
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userPrompt() == null ? "" : request.userPrompt());
        if (request.expectsJson()) {
            // LM Studio / Ollama / OpenAI 均支持 response_format=json_object
            root.putObject("response_format").put("type", "json_object");
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw LlmException.badResponse("构造模型请求失败：" + e.getMessage(), e);
        }
    }

    private LlmResponse parse(String rawBody, long elapsedMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw LlmException.badResponse("模型响应不是合法 JSON：" + abbreviate(rawBody), e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                throw LlmException.badResponse("模型返回错误：" + error.path("message").asText("未知错误"), null);
            }
            if (root.hasNonNull("response")) {
                // Ollama 原生接口兼容
                return new LlmResponse(root.path("response").asText(""), model, elapsedMs);
            }
            throw LlmException.badResponse("模型响应缺少 choices 字段：" + abbreviate(rawBody), null);
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").asText("");
        if (content.isBlank() && message.has("reasoning_content")) {
            content = message.path("reasoning_content").asText("");
        }
        JsonNode usage = root.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int outputTokens = usage.path("completion_tokens").asInt(0);
        if (content.isBlank()) {
            log.debug("模型返回空内容，finish_reason={}", choices.get(0).path("finish_reason").asText("-"));
        }
        return new LlmResponse(content, root.path("model").asText(model), elapsedMs, promptTokens, outputTokens);
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public boolean available() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "…";
    }

    private String trimTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "http://127.0.0.1:1234/v1" : trimmed;
    }
}
