package com.dusk4d.interview.llm;

import com.dusk4d.interview.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 兼容客户端的请求构造与推理模型适配。
 *
 * <p>背景（真实模型实测发现）：Qwen3 这类混合推理模型默认会先输出一大段思考内容，
 * 在结构化任务里会吃掉 token 预算并可能让 {@code content} 为空
 * （本机 qwen3:1.7b 实测：一个问题 6~10 秒，正文可能为空）。
 * 因此客户端需要自动追加 {@code /no_think} 并显式传 {@code think:false}。
 *
 * <p>这里不启动真实服务，而是直接校验构造出的请求体——把「容易回退的配置逻辑」测住。
 */
@DisplayName("模型请求构造与推理模型适配")
class OpenAiCompatibleClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AppProperties.Llm llm(String model, Boolean disableThinking) {
        return new AppProperties.Llm("http://127.0.0.1:11434/v1", "k", model, "nomic-embed",
                0.2, 1200, 1000, 30000, 0, false, disableThinking, null, null);
    }

    private JsonNode requestBody(boolean disableThinking, String model, LlmRequest request) throws Exception {
        // 模拟 chat() 的行为：纯文本请求不发送 response_format
        return requestBody(disableThinking, model, request, null,
                request.expectsJson() ? "json_object" : null);
    }

    private JsonNode requestBody(boolean disableThinking, String model, LlmRequest request, String jsonFormat,
                                 String effectiveFormat) throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:11434/v1", "k", model,
                1200, 1000, 30000, MAPPER, disableThinking, jsonFormat);
        Method buildBody = OpenAiCompatibleClient.class.getDeclaredMethod("buildBody", LlmRequest.class, String.class);
        buildBody.setAccessible(true);
        return MAPPER.readTree((String) buildBody.invoke(client, request, effectiveFormat));
    }

    @Test
    @DisplayName("response_format 按服务能力协商：json_object 默认，json_schema 用于 LM Studio")
    void negotiatesResponseFormat() throws Exception {
        LlmRequest structured = LlmRequest.structured("系统", "出题", "question", java.util.List.of("question"),
                0.2, 1200);

        // 默认（Ollama 等）：json_object
        JsonNode jsonObjectBody = requestBody(false, "qwen2.5-7b-instruct", structured, null, "json_object");
        assertThat(jsonObjectBody.path("response_format").path("type").asText()).isEqualTo("json_object");

        // LM Studio 只接受 json_schema：带上 schema 名称与基础结构
        JsonNode jsonSchemaBody = requestBody(false, "google/gemma-3-4b", structured, null, "json_schema");
        JsonNode schema = jsonSchemaBody.path("response_format").path("json_schema");
        assertThat(jsonSchemaBody.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(schema.path("name").asText()).isEqualTo("question");
        assertThat(schema.path("schema").path("type").asText()).isEqualTo("object");

        // 降级到 text：完全不发送 response_format，纯靠提示词要求 JSON
        JsonNode textBody = requestBody(false, "google/gemma-3-4b", structured, null, "text");
        assertThat(textBody.has("response_format")).isFalse();

        // 显式固定为 json_schema 时，即使给了 json_object 也应被覆盖
        JsonNode forced = requestBody(false, "google/gemma-3-4b", structured, "json_schema", "json_schema");
        assertThat(forced.path("response_format").path("type").asText()).isEqualTo("json_schema");

        // 纯文本请求不带 response_format
        JsonNode plain = requestBody(false, "google/gemma-3-4b", new LlmRequest("s", "u", 0.2, 100), null, null);
        assertThat(plain.has("response_format")).isFalse();
    }

    @Test
    @DisplayName("服务返回 response_format 不支持时会被识别为可降级失败")
    void detectsUnsupportedResponseFormat() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:1234/v1", "k",
                "google/gemma-3-4b", 800, 500, 5000, MAPPER, false, null);
        Method detect = OpenAiCompatibleClient.class.getDeclaredMethod("isUnsupportedResponseFormat",
                com.dusk4d.interview.error.LlmException.class);
        detect.setAccessible(true);

        // LM Studio 的真实报错文案
        var unsupported = com.dusk4d.interview.error.LlmException.badResponse(
                "模型服务返回 HTTP 400：{\"error\":\"'response_format.type' must be 'json_schema' or 'text'\"}", null);
        assertThat((Boolean) detect.invoke(client, unsupported)).isTrue();

        // 其它 400（例如模型名不存在）不应触发格式降级
        var otherBadRequest = com.dusk4d.interview.error.LlmException.badResponse(
                "模型服务返回 HTTP 400：{\"error\":\"model not found\"}", null);
        assertThat((Boolean) detect.invoke(client, otherBadRequest)).isFalse();

        // 超时也不是格式问题
        assertThat((Boolean) detect.invoke(client, com.dusk4d.interview.error.LlmException.timeout("t", null)))
                .isFalse();
    }

    @Test
    @DisplayName("推理模型自动关闭思考链：请求体带 think=false 且正文追加 /no_think")
    void suppressesThinkingForReasoningModels() throws Exception {
        // 自动判断：模型名以 qwen3 开头 → 关闭思考
        assertThat(llm("qwen3:1.7b", null).shouldDisableThinking()).isTrue();
        assertThat(llm("deepseek-r1:7b", null).shouldDisableThinking()).isTrue();
        assertThat(llm("qwq-32b", null).shouldDisableThinking()).isTrue();
        // 普通模型不应被干预
        assertThat(llm("qwen2.5-7b-instruct", null).shouldDisableThinking()).isFalse();
        assertThat(llm("gemma-3-4b-it", null).shouldDisableThinking()).isFalse();
        // 显式配置优先于自动判断
        assertThat(llm("qwen3:1.7b", Boolean.FALSE).shouldDisableThinking()).isFalse();
        assertThat(llm("gemma-3-4b-it", Boolean.TRUE).shouldDisableThinking()).isTrue();

        LlmRequest request = LlmRequest.structured("系统提示", "生成一道题", "question",
                java.util.List.of("question"), 0.2, 1200);
        JsonNode body = requestBody(true, "qwen3:1.7b", request);

        assertThat(body.path("think").asBoolean()).isFalse();
        assertThat(body.path("messages").get(1).path("content").asText()).endsWith("/no_think");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1200);
        assertThat(body.path("model").asText()).isEqualTo("qwen3:1.7b");
    }

    @Test
    @DisplayName("普通模型不追加 /no_think，也不传 think 字段")
    void leavesNormalModelsAlone() throws Exception {
        LlmRequest request = new LlmRequest("系统提示", "你好", 0.2, 200);
        JsonNode body = requestBody(false, "gemma-3-4b-it", request);

        assertThat(body.has("think")).isFalse();
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("你好");
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    @DisplayName("已包含 /no_think 的提示不会重复追加")
    void doesNotDuplicateMarker() throws Exception {
        LlmRequest request = new LlmRequest("系统", "帮我出题 /no_think", 0.2, 200);
        JsonNode body = requestBody(true, "qwen3:1.7b", request);
        String content = body.path("messages").get(1).path("content").asText();
        assertThat(content).isEqualTo("帮我出题 /no_think");
        assertThat(content.split("/no_think", -1)).hasSize(2);
    }

    @Test
    @DisplayName("系统提示为空时不发送 system 消息")
    void omitsEmptySystemPrompt() throws Exception {
        JsonNode body = requestBody(false, "gemma-3-4b-it", new LlmRequest("  ", "hi", 0.2, 100));
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("非推理模型（gemma 等）不会被追加思考抑制标记")
    void doesNotAnnotateNonReasoningModels() throws Exception {
        // 回归：曾经担心 /no_think 被无条件加给所有模型。实测代码是有条件的，
        // 这里把「gemma 不加、qwen3 才加」的边界固定下来。
        assertThat(llm("google/gemma-3-4b", null).shouldDisableThinking())
                .as("gemma 系列不是推理模型，不应被注入 /no_think")
                .isFalse();
        JsonNode body = requestBody(false, "google/gemma-3-4b", new LlmRequest("系统", "出题", 0.2, 100));
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("出题");
        assertThat(body.has("think")).isFalse();
    }

    @Test
    @DisplayName("必须强制 HTTP/1.1：JDK 默认的 HTTP/2 升级在 LM Studio 上会读超时")
    void forcesHttp11() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:1234/v1", "k",
                "google/gemma-3-4b", 800, 500, 5000, MAPPER, false);
        Method field = OpenAiCompatibleClient.class.getDeclaredMethod("buildHttpClient", int.class);
        field.setAccessible(true);
        java.net.http.HttpClient httpClient = (java.net.http.HttpClient) field.invoke(client, 500);
        // 默认版本即实际使用版本：必须锁定为 HTTP/1.1
        assertThat(httpClient.version())
                .as("JDK HttpClient 默认尝试 h2c 升级，LM Studio 无法协商会挂到超时，必须强制 1.1")
                .isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }

    @Test
    @DisplayName("baseUrl 结尾多余斜杠被规整，provider/modelName 正确暴露")
    void normalisesBaseUrl() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:1234/v1///", "k",
                "qwen2.5-7b-instruct", 800, 500, 5000, MAPPER, false);
        assertThat(client.provider()).isEqualTo("openai-compatible");
        assertThat(client.modelName()).isEqualTo("qwen2.5-7b-instruct");
    }

    @Test
    @DisplayName("服务不可达时 available() 安全返回 false，而不是抛异常")
    void availableIsSafeWhenUnreachable() {
        // 故意指向一个确定不会有服务监听的端口：断言的是「不抛异常」而不是「一定没有服务」，
        // 这样测试不受本机是否正在运行 LM Studio / Ollama 影响。
        // 端口 1 是特权端口且不会有本地模型服务占用（此时沙箱不允许绑定）。
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:1/v1", "k",
                "no-such-model", 800, 300, 1500, MAPPER, false);
        assertThat(client.available()).isFalse();
        // 调用同样要给出明确异常，而不是挂死
        assertThatThrownBy(() -> client.chat(new LlmRequest("s", "u", 0.2, 50)))
                .isInstanceOf(com.dusk4d.interview.error.LlmException.class);
    }
}
