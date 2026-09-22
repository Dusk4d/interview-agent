package com.dusk4d.interview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型服务连通性诊断：回答「我明明开着 Ollama，为什么界面显示模型未连接」。
 *
 * <p>为什么需要它：默认配置指向 LM Studio 的 {@code 127.0.0.1:1234}。只启动 Ollama
 * （{@code 11434}）的用户会看到「模型未连接」，但界面上没有任何信息说明系统在找哪个地址、
 * 找的是哪个模型名——于是只能猜。这里把「探测了哪个地址、那里有哪些模型、该改哪个变量」
 * 直接变成可展示的文案（见 {@code /api/health} 的 {@code llmStatus} / {@code llmHint}）。
 *
 * <p>另外这里还覆盖了一个更隐蔽的坑：只要端口通就报「已连接」，但模型名写错时
 * 每次真实调用都会 404 并静默降级，界面却是绿色的。因此「模型名是否在服务端的模型列表里」
 * 也是连通性的一部分。
 */
@Component
public class LlmDiagnostics {

    /** 常见本地推理服务的默认地址，用于「你配置的地址不通，但这里有个服务」这类提示。 */
    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("http://127.0.0.1:11434/v1", "Ollama", "scripts\\start.cmd ollama"),
            new Candidate("http://127.0.0.1:1234/v1", "LM Studio", "scripts\\start.cmd lmstudio"),
            new Candidate("http://127.0.0.1:8000/v1", "vLLM / llama.cpp", null),
            new Candidate("http://127.0.0.1:8080/v1", "本地推理服务", null)
    );

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CANDIDATE_TIMEOUT = Duration.ofMillis(1200);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmDiagnostics(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(PROBE_TIMEOUT)
                .build();
    }

    /**
     * 诊断结果。
     *
     * @param available      是否可以正常调用（地址可达 **且** 模型名存在）
     * @param status         一句话说明当前状态（前端直接展示）
     * @param hint           不可用时的修复建议（可为空）
     * @param availableModels 服务端实际提供的模型名（拿不到时为空列表）
     */
    public record Diagnosis(boolean available, String status, String hint, List<String> availableModels) {
    }

    private record Candidate(String baseUrl, String label, String startHint) {
    }

    /** 探测结果：不可达 / 可达但模型列表未知 / 可达且有模型列表。 */
    private record Probe(boolean reachable, List<String> models) {
        static Probe unreachable() {
            return new Probe(false, List.of());
        }
    }

    public Diagnosis diagnose(String baseUrl, String chatModel) {
        String url = trimTrailingSlash(baseUrl);
        Probe probe = probe(url, PROBE_TIMEOUT);
        if (!probe.reachable()) {
            return unreachable(url, chatModel);
        }
        if (probe.models().isEmpty()) {
            // 服务端没有返回可解析的模型列表（某些兼容实现如此），只能确认端口可达
            return new Diagnosis(true, "已连接 " + url + "（服务端未返回模型列表，无法校验模型名）",
                    null, List.of());
        }
        if (probe.models().stream().anyMatch(m -> m.equalsIgnoreCase(chatModel))) {
            return new Diagnosis(true, "已连接 " + url + "，模型 " + chatModel + " 可用", null, probe.models());
        }
        String available = String.join("、", probe.models());
        return new Diagnosis(false,
                "地址 " + url + " 可达，但模型「" + chatModel + "」不在服务端模型列表里（服务端只有：" + available + "）",
                "把 INTERVIEW_LLM_CHAT_MODEL 设成上面列表中的一个，例如：set INTERVIEW_LLM_CHAT_MODEL="
                        + probe.models().get(0) + "，然后重启服务。",
                probe.models());
    }

    private Diagnosis unreachable(String url, String chatModel) {
        for (Candidate candidate : CANDIDATES) {
            if (candidate.baseUrl().equalsIgnoreCase(url)) {
                continue;
            }
            Probe probe = probe(candidate.baseUrl(), CANDIDATE_TIMEOUT);
            if (!probe.reachable()) {
                continue;
            }
            String models = probe.models().isEmpty() ? "（模型列表未返回）" : String.join("、", probe.models());
            String startHint = candidate.startHint() == null ? "" : "；或直接用 " + candidate.startHint() + " 一键配好";
            return new Diagnosis(false,
                    "当前配置的地址 " + url + " 连不上（模型 " + chatModel + "），但检测到 " + candidate.label()
                            + " 正在 " + candidate.baseUrl() + " 提供服务，可用模型：" + models,
                    "把地址指过去即可：set INTERVIEW_LLM_BASE_URL=" + candidate.baseUrl()
                            + (probe.models().isEmpty() ? "" : " 与 set INTERVIEW_LLM_CHAT_MODEL=" + probe.models().get(0))
                            + startHint + "，然后重启服务。",
                    probe.models());
        }
        return new Diagnosis(false,
                "模型服务地址 " + url + " 连不上，且本机常见端口（11434 Ollama / 1234 LM Studio）也没有服务在运行",
                "先启动一个本地模型服务：scripts\\start.cmd ollama（需 Ollama 已运行）"
                        + "或 scripts\\start.cmd lmstudio（需 lms server start 并已加载模型）；"
                        + "只想点界面可以用 scripts\\start.cmd mock。",
                List.of());
    }

    private Probe probe(String baseUrl, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Probe.unreachable();
            }
            return new Probe(true, parseModels(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Probe.unreachable();
        } catch (Exception e) {
            return Probe.unreachable();
        }
    }

    /**
     * 从 OpenAI 兼容的 {@code /v1/models} 响应里取模型 id。
     *
     * <p>拿不到结构化列表时返回空列表（而不是报错）：调用方据此退化为「只确认可达」，
     * 避免把「服务端没实现 /models 列表」误判成「模型不可用」。
     */
    private List<String> parseModels(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<String> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(id);
                }
            }
            return List.copyOf(models);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String trimTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "http://127.0.0.1:1234/v1" : trimmed;
    }
}
