package com.dusk4d.interview.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「模型未连接」必须说清楚原因，否则用户只能猜。
 *
 * <p>回归背景：默认配置指向 LM Studio 的 1234，只开了 Ollama（11434）的用户看到
 * 「模型未连接」却没有任何线索；更隐蔽的是端口通但模型名写错时，旧逻辑只判断
 * 「/v1/models 返回 2xx」，圆点是绿的、每次调用却都在降级。
 */
@DisplayName("模型服务连通性诊断")
class LlmDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServers() {
        // HttpServer 只有 stop(int delay)，没有无参重载
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    /** 起一个假的 OpenAI 兼容服务，/v1/models 返回给定模型列表。 */
    private String startServer(String... modelIds) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", (HttpExchange exchange) -> {
            StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
            for (int i = 0; i < modelIds.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(modelIds[i]).append("\",\"object\":\"model\"}");
            }
            sb.append("]}");
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private LlmDiagnostics diagnostics() {
        return new LlmDiagnostics(MAPPER);
    }

    @Test
    @DisplayName("地址可达且模型名存在 → 可用")
    void reachableWithMatchingModel() throws IOException {
        String url = startServer("qwen3:1.7b", "llama3:8b");

        LlmDiagnostics.Diagnosis diagnosis = diagnostics().diagnose(url, "qwen3:1.7b");

        assertThat(diagnosis.available()).isTrue();
        assertThat(diagnosis.status()).contains(url).contains("qwen3:1.7b");
        assertThat(diagnosis.availableModels()).containsExactly("qwen3:1.7b", "llama3:8b");
        assertThat(diagnosis.hint()).isNull();
    }

    @Test
    @DisplayName("地址可达但模型名不在列表里 → 不可用，并给出该改成什么")
    void reachableButModelMissing() throws IOException {
        String url = startServer("qwen3:1.7b");

        LlmDiagnostics.Diagnosis diagnosis = diagnostics().diagnose(url, "qwen2.5-7b-instruct");

        assertThat(diagnosis.available()).isFalse();
        assertThat(diagnosis.status())
                .contains("qwen2.5-7b-instruct")
                .contains("不在服务端模型列表")
                .contains("qwen3:1.7b");
        assertThat(diagnosis.hint()).contains("INTERVIEW_LLM_CHAT_MODEL").contains("qwen3:1.7b");
    }

    @Test
    @DisplayName("服务端不返回模型列表时不误判：仍算可用")
    void unparsableModelListStillCountsAsReachable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", (HttpExchange exchange) -> {
            byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        servers.add(server);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        LlmDiagnostics.Diagnosis diagnosis = diagnostics().diagnose(url, "whatever");

        assertThat(diagnosis.available()).isTrue();
        assertThat(diagnosis.status()).contains("未返回模型列表");
    }

    @Test
    @DisplayName("地址不通 → 明确说明连不上，并列出本机该查哪些端口")
    void unreachableExplainsWhatWasProbed() throws IOException {
        // 占一个端口再关掉，确保该端口上确定没有服务（避免撞上真实运行的 Ollama/LM Studio）
        HttpServer temp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int deadPort = temp.getAddress().getPort();
        temp.stop(0);
        String url = "http://127.0.0.1:" + deadPort + "/v1";

        LlmDiagnostics.Diagnosis diagnosis = diagnostics().diagnose(url, "qwen3:1.7b");

        assertThat(diagnosis.available()).isFalse();
        assertThat(diagnosis.status()).contains(url).contains("连不上");
        assertThat(diagnosis.hint()).isNotBlank();
    }

    @Test
    @DisplayName("配置地址不通但本机另有服务 → 直接点名「检测到 XX 在 YY」")
    void suggestsDetectedServiceWhenConfiguredOneIsDown() throws IOException {
        // 本机是否存在 Ollama/LM Studio 不由测试决定，因此这里断言的是「两种情况下
        // 都必须给出可执行的下一步」，而不是断言某一种环境状态。
        HttpServer temp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int deadPort = temp.getAddress().getPort();
        temp.stop(0);

        LlmDiagnostics.Diagnosis diagnosis =
                diagnostics().diagnose("http://127.0.0.1:" + deadPort + "/v1", "qwen3:1.7b");

        assertThat(diagnosis.available()).isFalse();
        if (diagnosis.status().contains("检测到")) {
            // 发现了别的服务：必须报出是哪个地址，并告诉用户改 base-url
            assertThat(diagnosis.status()).contains("提供服务");
            assertThat(diagnosis.hint()).contains("INTERVIEW_LLM_BASE_URL");
        } else {
            // 什么都没发现：至少要点明查过哪些端口，并给出启动命令
            assertThat(diagnosis.status()).contains("11434").contains("1234");
            assertThat(diagnosis.hint()).contains("scripts\\start.cmd");
        }
    }

    @Test
    @DisplayName("尾部斜杠不影响探测")
    void toleratesTrailingSlash() throws IOException {
        String url = startServer("qwen3:1.7b");

        assertThat(diagnostics().diagnose(url + "/", "qwen3:1.7b").available()).isTrue();
    }
}
