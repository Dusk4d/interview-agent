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
    /** 混合推理模型（Qwen3 等）的思考链抑制标记，Ollama 兼容。 */
    private static final String NO_THINK_SUFFIX = " /no_think";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration readTimeout;
    private final boolean disableThinking;
    /** 显式指定的 JSON 模式（auto / json_object / json_schema / text）；null 表示按服务能力自动选择。 */
    private final String jsonFormatOverride;
    /** 已确认该服务不支持的 JSON 模式，避免每次调用都重复撞 400。 */
    private final java.util.Set<String> unsupportedFormats =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, int maxTokens,
                                  int connectTimeoutMs, int readTimeoutMs, ObjectMapper objectMapper,
                                  boolean disableThinking) {
        this(baseUrl, apiKey, model, maxTokens, connectTimeoutMs, readTimeoutMs, objectMapper,
                disableThinking, null);
    }

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, int maxTokens,
                                  int connectTimeoutMs, int readTimeoutMs, ObjectMapper objectMapper,
                                  boolean disableThinking, String jsonFormat) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.readTimeout = Duration.ofMillis(Math.max(1000, readTimeoutMs));
        this.objectMapper = objectMapper;
        this.disableThinking = disableThinking;
        this.jsonFormatOverride = jsonFormat;
        this.httpClient = buildHttpClient(connectTimeoutMs);
    }

    /**
     * 构造 HTTP 客户端。
     *
     * <p><b>必须强制 HTTP/1.1。</b>JDK {@link HttpClient} 默认会先尝试 HTTP/2 升级
     * （{@code Upgrade: h2c}），而 LM Studio 的 Express 服务器无法正确协商该升级：
     * 请求会一直挂着直到读超时。实测同一地址 curl 92ms 返回 200、JDK 默认版本 8 秒超时；
     * 强制 HTTP/1.1 后同样 92ms 返回。Ollama 支持 HTTP/2，所以这个问题此前没有暴露。
     *
     * <p>本地模型服务都在本机，HTTP/2 没有实际收益，强制 1.1 换来确定性。
     */
    private HttpClient buildHttpClient(int connectTimeoutMs) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        int attempts = request.expectsJson() ? 2 : 1;
        LlmException lastFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String format = currentFormat(request);
            try {
                return sendOnce(request, format);
            } catch (LlmException e) {
                lastFailure = e;
                if (attempt + 1 >= attempts || !isUnsupportedResponseFormat(e)) {
                    throw e;
                }
                // 服务不支持当前 JSON 模式（例如 LM Studio 只认 json_schema，拒绝 json_object）。
                // 降级到 text 并按服务能力记忆，避免后续每次调用都重复撞墙。
                log.warn("模型服务不支持 response_format={}（{}），下次改用 text 模式：{}",
                        format, baseUrl, abbreviate(e.getMessage()));
                markFormatUnsupported(format);
            }
        }
        throw lastFailure == null ? LlmException.badResponse("模型调用失败", null) : lastFailure;
    }

    /** 当前请求应使用的 JSON 模式。 */
    private String currentFormat(LlmRequest request) {
        if (!request.expectsJson()) {
            return null;
        }
        if (jsonFormatOverride != null && !jsonFormatOverride.isBlank()) {
            return "auto".equalsIgnoreCase(jsonFormatOverride) ? preferredJsonFormat() : jsonFormatOverride;
        }
        return preferredJsonFormat();
    }

    private String preferredJsonFormat() {
        if (unsupportedFormats.contains("json_object")) {
            return unsupportedFormats.contains("json_schema") ? "text" : "json_schema";
        }
        return "json_object";
    }

    private void markFormatUnsupported(String format) {
        if (format == null) {
            return;
        }
        if (!unsupportedFormats.add(format)) {
            return;
        }
        String next = preferredJsonFormat();
        log.warn("已记录模型服务 {} 不支持 response_format={}，后续结构化请求将使用 {}。"
                        + "如需固定行为，可设置 app.llm.json-format。", baseUrl, format, next);
    }

    /** 判断失败是否源于「服务不支持该 response_format」。 */
    private boolean isUnsupportedResponseFormat(LlmException e) {
        if (e.toFailureKind() != LlmFailureKind.BAD_RESPONSE) {
            return false;
        }
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("response_format") || message.contains("json_object");
    }

    private LlmResponse sendOnce(LlmRequest request, String format) {
        long start = System.nanoTime();
        String body = buildBody(request, format);
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

    private String buildBody(LlmRequest request, String jsonFormat) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", request.temperature());
        root.put("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : maxTokens);
        root.put("stream", false);
        // Ollama 原生参数：显式关闭思考链（其它兼容服务会忽略未知字段）
        if (disableThinking) {
            root.put("think", false);
        }
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", withThinkingSuppressed(request.userPrompt()));
        if (jsonFormat != null && !"text".equals(jsonFormat)) {
            // 兼容性说明（真实服务实测）：
            // · Ollama 支持 json_object；
            // · LM Studio 只接受 json_schema / text，传 json_object 会返回 400。
            // 因此这里按「服务能力」选择模式，不支持时由 chat() 自动降级到 text 并记忆结果。
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", jsonFormat);
            if ("json_schema".equals(jsonFormat)) {
                ObjectNode schema = responseFormat.putObject("json_schema");
                schema.put("name", request.schemaName() == null ? "structured_output" : request.schemaName());
                schema.putObject("schema").put("type", "object");
            }
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw LlmException.badResponse("构造模型请求失败：" + e.getMessage(), e);
        }
    }

    /** 对混合推理模型追加 {@code /no_think}，避免思考链吃掉结构化输出预算。 */
    private String withThinkingSuppressed(String prompt) {
        String text = prompt == null ? "" : prompt;
        if (!disableThinking || text.contains("/no_think")) {
            return text;
        }
        return text + NO_THINK_SUFFIX;
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
        String reasoning = message.path("reasoning_content").asText("");
        String finishReason = choices.get(0).path("finish_reason").asText("-");

        if (content.isBlank() && !reasoning.isBlank()) {
            // 思考内容不能当作正文使用：它不是结构化结果，直接解析只会污染下游。
            // 这里给出可操作的诊断，让用户知道该关掉 thinking 或换模型。
            throw LlmException.badResponse(
                    "模型只输出了思考过程、没有输出正文（finish_reason=" + finishReason + "）。"
                            + (disableThinking
                                    ? "已尝试关闭思考链仍失败，建议换用非推理模型或调大 app.llm.max-tokens。"
                                    : "请设置 app.llm.disable-thinking=true 或改用非推理模型。"), null);
        }
        if (content.isBlank()) {
            throw LlmException.badResponse(
                    "模型返回空正文（finish_reason=" + finishReason + "）。"
                            + ("length".equals(finishReason)
                                    ? "输出被 max-tokens 截断，建议调大 app.llm.max-tokens。"
                                    : "请确认模型名称是否正确、服务是否已加载该模型。"), null);
        }

        JsonNode usage = root.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int outputTokens = usage.path("completion_tokens").asInt(0);
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
