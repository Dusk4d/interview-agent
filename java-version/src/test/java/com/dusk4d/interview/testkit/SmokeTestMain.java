package com.dusk4d.interview.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 独立冒烟测试（针对「已经在运行的实例」，例如 dist/app.cmd 启动的服务）。
 *
 * <p>与 {@code HttpEndToEndTest} 的区别：那个测试自己拉起 Spring 上下文，
 * 这个只通过 HTTP 访问外部进程，因此可以用于「打包后真实部署」的验收，
 * 并且能在 CI/脚本里对任意 base-url 执行。
 *
 * <p>用法：{@code java -cp <test-classpath> com.dusk4d.interview.testkit.SmokeTestMain --base-url=http://127.0.0.1:8090}
 * 退出码：0 表示全部通过，1 表示存在失败项。
 */
public final class SmokeTestMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> FAILURES = new ArrayList<>();
    private static HttpClient client;
    private static String baseUrl = "http://127.0.0.1:8090";

    private SmokeTestMain() {
    }

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.startsWith("--base-url=")) {
                baseUrl = arg.substring("--base-url=".length());
            }
        }
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        System.out.println("=== smoke test against " + baseUrl + " ===");

        JsonNode health = get("/api/health");
        step("health");
        check(health != null, "service responds");
        if (health != null) {
            check("UP".equals(health.path("status").asText()), "status=UP");
            check(health.path("knowledgeItems").asInt() > 20,
                    "knowledge base loaded (" + health.path("knowledgeItems").asInt() + " items)");
            check(health.path("knownChunks").asInt() > 20,
                    "vector store indexed (" + health.path("knownChunks").asInt() + " chunks)");
            System.out.printf("    llm=%s/%s available=%s embedding=%s%n",
                    health.path("llmProvider").asText(), health.path("llmModel").asText(),
                    health.path("llmAvailable").asBoolean(), health.path("embeddingProvider").asText());
        }

        step("static frontend");
        HttpResponse<String> page = getRaw("/index.html");
        check(page != null && page.statusCode() == 200, "index.html served");
        check(page != null && page.body().contains("AI 面试陪练系统"), "page content matches the app");

        step("import resume (pasted text)");
        JsonNode imported = post("/api/resumes/text", Map.of("text", SAMPLE_RESUME, "fileName", "smoke-resume.txt"));
        check(imported != null && !imported.path("resumeId").asText().isBlank(), "resume imported");
        check(imported != null && imported.path("factCount").asInt() > 3,
                "facts extracted (" + (imported == null ? 0 : imported.path("factCount").asInt()) + ")");
        String resumeId = imported == null ? "" : imported.path("resumeId").asText();

        step("resume detail and privacy check");
        JsonNode detail = get("/api/resumes/" + resumeId);
        check(detail != null && detail.path("projects").size() > 0, "projects detected");
        check(detail != null && !detail.path("text").asText().contains("13812345678"),
                "phone number masked in stored text");
        check(detail != null && !detail.path("text").asText().contains("zhangwei_dev@example.com"),
                "email masked in stored text");
        check(detail != null && detail.path("techStack").size() > 3,
                "tech stack extracted (" + (detail == null ? 0 : detail.path("techStack").size()) + ")");

        step("retrieval debug (evidence for questions)");
        JsonNode search = get("/api/retrieval/search?q=Redis%20%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81&mode=PROJECT&resumeId=" + resumeId);
        check(search != null && search.path("chunks").size() > 0, "retrieval returns chunks");
        check(search != null && search.path("citations").size() > 0, "retrieval returns citations");

        JsonNode knowledgeSearch = get("/api/retrieval/search?q=MySQL%20%E7%B4%A2%E5%BC%95&mode=KNOWLEDGE");
        check(knowledgeSearch != null && "KNOWLEDGE".equals(knowledgeSearch.path("chunks").path(0).path("kind").asText()),
                "knowledge retrieval hits the knowledge base");

        step("create interview session (PROJECT)");
        JsonNode session = post("/api/interviews", Map.of("resumeId", resumeId, "mode", "PROJECT", "maxQuestions", 4));
        check(session != null && !session.path("id").asText().isBlank(), "session created");
        String sessionId = session == null ? "" : session.path("id").asText();

        step("next question");
        JsonNode question = post("/api/interviews/" + sessionId + "/next-question", null);
        check(question != null && question.path("content").asText().length() > 5, "question generated");
        check(question != null && question.path("sourceIds").size() > 0,
                "question carries source ids (grounded in retrieved facts)");
        String questionId = question == null ? "" : question.path("id").asText();

        step("submit answer (deliberately incomplete)");
        JsonNode answer = post("/api/interviews/" + sessionId + "/answers",
                Map.of("questionId", questionId, "content", "用 Redis 加锁保证幂等，细节记不清了。"));
        check(answer != null && answer.path("evaluation").path("dimensions").size() == 4,
                "four scoring dimensions returned");
        double score = answer == null ? -1 : answer.path("evaluation").path("totalScore").asDouble(-1);
        check(score >= 0 && score <= 5, "score within 0..5 (got " + score + ")");
        String nextAction = answer == null ? "" : answer.path("nextAction").asText();
        System.out.println("    nextAction=" + nextAction);

        step("follow-up when recommended");
        if ("FOLLOW_UP".equals(nextAction)) {
            JsonNode followUp = post("/api/interviews/" + sessionId + "/follow-up", null);
            check(followUp != null && followUp.path("followUp").asBoolean(), "follow-up question created");
            if (followUp != null) {
                JsonNode followUpAnswer = post("/api/interviews/" + sessionId + "/answers",
                        Map.of("questionId", followUp.path("id").asText(),
                                "content", "具体用 SET key value NX PX 加锁，解锁用 Lua 比对 value 后删除，续期每 10 秒一次，压测重复下单为 0。"));
                check(followUpAnswer != null && !followUpAnswer.path("answerId").asText().isBlank(),
                        "follow-up answer evaluated");
            }
        } else {
            System.out.println("    (backend did not recommend a follow-up; skipping)");
        }

        step("remaining questions until budget is exhausted");
        for (int i = 0; i < 6; i++) {
            JsonNode next = post("/api/interviews/" + sessionId + "/next-question", null);
            if (next == null || next.path("id").isMissingNode()) {
                System.out.println("    question budget reached after " + i + " additional questions");
                break;
            }
            JsonNode answered = post("/api/interviews/" + sessionId + "/answers",
                    Map.of("questionId", next.path("id").asText(),
                            "content", "背景是订单系统，我负责核销链路，通过 Redis 与唯一索引保证幂等，"
                                    + "难点在锁续期，用看门狗解决，压测显示重复下单为 0。"));
            check(answered != null, "question " + next.path("sequence").asInt() + " evaluated");
        }

        step("retry and replace question (scenario 1 of the spec)");
        JsonNode retrySession = post("/api/interviews", Map.of(
                "resumeId", resumeId, "mode", "PROJECT", "maxQuestions", 3));
        if (retrySession == null) {
            check(false, "second session for retry/replace created");
        } else {
            String retrySessionId = retrySession.path("id").asText();
            JsonNode droppedQuestion = post("/api/interviews/" + retrySessionId + "/next-question", null);
            JsonNode replaced = post("/api/interviews/" + retrySessionId + "/replace-question", null);
            check(replaced != null && !replaced.path("id").asText().isBlank(), "replaced question generated");
            check(replaced != null && droppedQuestion != null
                            && !replaced.path("id").asText().equals(droppedQuestion.path("id").asText()),
                    "replaced question differs from the dropped one");
            JsonNode afterReplace = get("/api/interviews/" + retrySessionId);
            // 换题不消耗题量配额：仍应只有 1 题
            check(afterReplace != null && afterReplace.path("questionCount").asInt() == 1,
                    "replace does not consume the question budget");
            check(afterReplace != null && afterReplace.path("answerCount").asInt() == 0,
                    "replace leaves the session unanswered");

            String replacedId = replaced == null ? "" : replaced.path("id").asText();
            JsonNode firstAttempt = post("/api/interviews/" + retrySessionId + "/answers",
                    Map.of("questionId", replacedId, "content", "大概是用了锁和唯一索引，细节记不清了。"));
            check(firstAttempt != null && firstAttempt.path("evaluation").has("referenceAnswer"),
                    "evaluation carries the referenceAnswer field");
            double firstScore = firstAttempt == null ? -1
                    : firstAttempt.path("evaluation").path("totalScore").asDouble(-1);

            JsonNode retried = post("/api/interviews/" + retrySessionId + "/retry", null);
            check(retried != null && "WAITING_ANSWER".equals(retried.path("session").path("status").asText()),
                    "retry puts the session back to WAITING_ANSWER");
            check(retried != null && Math.abs(retried.path("discardedScore").asDouble(-1) - firstScore) < 0.001,
                    "retry reports the discarded score");
            JsonNode afterRetry = get("/api/interviews/" + retrySessionId);
            check(afterRetry != null && afterRetry.path("answerCount").asInt() == 0,
                    "retry removes the previous answer from the session counters");
            HttpResponse<String> staleEvaluation = getRaw("/api/interviews/" + retrySessionId + "/evaluation");
            check(staleEvaluation != null && staleEvaluation.statusCode() == 404,
                    "discarded evaluation is really gone (404)");

            JsonNode secondAttempt = post("/api/interviews/" + retrySessionId + "/answers",
                    Map.of("questionId", replacedId,
                            "content", "背景是任务重复执行。我负责幂等设计，通过 Redis 加锁与唯一索引兜底，"
                                    + "难点在锁续期，用看门狗解决，压测显示重复执行为 0。"));
            check(secondAttempt != null && secondAttempt.path("session").path("answerCount").asInt() == 1,
                    "re-answering counts exactly one answer");
        }

        step("finish interview");
        JsonNode finished = post("/api/interviews/" + sessionId + "/finish", Map.of("reason", "smoke test"));
        check(finished != null && "FINISHED".equals(finished.path("status").asText()), "session finished");

        step("generate report");
        JsonNode report = post("/api/interviews/" + sessionId + "/report", null);
        check(report != null && report.path("report").path("overallScore").asDouble(-1) >= 0, "report scored");
        check(report != null && report.path("report").path("abilityRadar").size() > 3, "ability radar computed");
        check(report != null && report.path("markdown").asText().contains("# 面试复盘报告"), "markdown rendered");

        step("download markdown");
        HttpResponse<String> markdown = getRaw("/api/interviews/" + sessionId + "/report.md");
        check(markdown != null && markdown.statusCode() == 200, "markdown download works");
        check(markdown != null && String.valueOf(markdown.headers().firstValue("content-disposition"))
                .contains("attachment"), "download sets attachment header");

        step("error handling");
        HttpResponse<String> badMode = postRaw("/api/interviews", Map.of("mode", "NOT_A_MODE"));
        check(badMode != null && badMode.statusCode() == 400, "invalid mode rejected with 400");
        HttpResponse<String> notFound = getRaw("/api/interviews/does-not-exist");
        check(notFound != null && notFound.statusCode() == 404, "unknown session returns 404");
        HttpResponse<String> emptyAnswer = postRaw("/api/interviews/" + sessionId + "/answers",
                Map.of("content", "   "));
        check(emptyAnswer != null && emptyAnswer.statusCode() == 409,
                "answering a finished session returns 409 (state guard)");
        HttpResponse<String> noRoute = getRaw("/api/not-a-real-endpoint");
        check(noRoute != null && noRoute.statusCode() == 404, "unknown route returns 404 (not 500)");

        System.out.println("=== smoke test finished ===");
        if (!FAILURES.isEmpty()) {
            System.out.println("FAILED checks: " + FAILURES.size());
            FAILURES.forEach(failure -> System.out.println("  - " + failure));
            System.exit(1);
        }
        System.out.println("ALL SMOKE CHECKS PASSED");
        System.exit(0);
    }

    // ---------------------------------------------------------------- helpers

    private static void step(String name) {
        System.out.println("- " + name);
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("    OK   " + message);
        } else {
            System.out.println("    FAIL " + message);
            FAILURES.add(message);
        }
    }

    private static JsonNode get(String path) throws Exception {
        HttpResponse<String> response = getRaw(path);
        if (response == null || response.statusCode() / 100 != 2) {
            return null;
        }
        return MAPPER.readTree(response.body());
    }

    private static JsonNode post(String path, Object body) throws Exception {
        HttpResponse<String> response = postRaw(path, body);
        if (response == null || response.statusCode() / 100 != 2) {
            return null;
        }
        return MAPPER.readTree(response.body());
    }

    private static HttpResponse<String> getRaw(String path) throws Exception {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("    (request failed: " + e.getMessage() + ")");
            return null;
        }
    }

    private static HttpResponse<String> postRaw(String path, Object body) throws Exception {
        try {
            String json = body == null ? "{}" : MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("    (request failed: " + e.getMessage() + ")");
            return null;
        }
    }

    private static final String SAMPLE_RESUME = """
            张伟
            手机：13812345678  邮箱：zhangwei_dev@example.com

            教育经历
            2021.09 - 2025.06  北京邮电大学  计算机科学与技术  本科

            实习经历
            2024.06 - 2024.12  杭州云启科技有限公司  Java 后端开发实习生
            参与订单中心重构，负责优惠券核销链路，使用 Redis 分布式锁保证并发下单幂等

            项目经历
            2023.09 - 2024.05  FinanceAgent 智能财务问答系统
            技术栈：Java 21、Spring Boot 3、PostgreSQL、pgvector、Vue3
            个人职责：负责文档解析与检索链路，设计分段与元数据方案
            结果：支持 12 类财务问题，平均响应 1.8 秒

            专业技能
            Java、Python、Spring Boot、Redis、Kafka、MySQL、Docker
            """;
}
