package com.dusk4d.interview.llm;

import com.dusk4d.interview.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

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
                0.2, 1200, 1000, 30000, 0, false, disableThinking);
    }

    private JsonNode requestBody(boolean disableThinking, String model, LlmRequest request) throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:11434/v1", "k", model,
                1200, 1000, 30000, MAPPER, disableThinking);
        Method buildBody = OpenAiCompatibleClient.class.getDeclaredMethod("buildBody", LlmRequest.class);
        buildBody.setAccessible(true);
        return MAPPER.readTree((String) buildBody.invoke(client, request));
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
    @DisplayName("baseUrl 结尾多余斜杠被规整，provider/modelName 正确暴露")
    void normalisesBaseUrl() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:1234/v1///", "k",
                "qwen2.5-7b-instruct", 800, 500, 5000, MAPPER, false);
        assertThat(client.provider()).isEqualTo("openai-compatible");
        assertThat(client.modelName()).isEqualTo("qwen2.5-7b-instruct");
        // 未启动服务时 available() 应安全返回 false，而不是抛异常
        assertThat(client.available()).isFalse();
    }
}
