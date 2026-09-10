package com.dusk4d.interview.api;

import com.dusk4d.interview.config.MockLlmTestConfiguration;
import com.dusk4d.interview.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 端到端测试：启动完整 Spring 上下文与内嵌 Tomcat，
 * 用 HTTP 客户端依次调用真实接口，验证「上传简历 → 出题 → 回答 → 评分 → 追问 → 报告」全链路。
 *
 * <p>与 {@code InterviewFlowTest}（服务层）互补：这一层额外覆盖 JSON 序列化、
 * 文件上传（multipart）、错误码映射与静态资源（前端页面）是否可访问。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MockLlmTestConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("HTTP 端到端 E2E")
class HttpEndToEndTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    @Test
    @Order(1)
    @DisplayName("健康检查返回模型、向量化与知识库状态")
    void health() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("/api/health"), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("llmProvider").asText()).isEqualTo("mock");
        assertThat(body.path("knowledgeItems").asInt()).isGreaterThan(20);
        assertThat(body.path("knownChunks").asInt()).isGreaterThan(20);
        assertThat(body.path("topics").isArray()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("前端静态资源可访问")
    void staticAssets() {
        ResponseEntity<String> page = rest.getForEntity(url("/index.html"), String.class);
        assertThat(page.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(page.getBody()).contains("AI 面试陪练系统").contains("简历导入");

        ResponseEntity<String> css = rest.getForEntity(url("/styles.css"), String.class);
        assertThat(css.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> js = rest.getForEntity(url("/app.js"), String.class);
        assertThat(js.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(js.getBody()).contains("startInterview");
    }

    @Test
    @Order(3)
    @DisplayName("multipart 上传 TXT 简历并解析出结构化事实")
    void uploadResumeViaMultipart() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource resource = new ByteArrayResource(
                Fixtures.bytes("resume-standard.txt")) {
            @Override
            public String getFilename() {
                return "张伟-简历.txt";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);

        ResponseEntity<String> response = rest.postForEntity(url("/api/resumes/import"),
                new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = json(response);
        assertThat(body.path("resumeId").asText()).isNotBlank();
        assertThat(body.path("factCount").asInt()).isGreaterThan(3);
        assertThat(body.path("confidence").asDouble()).isGreaterThan(0.5);

        // 详情接口返回脱敏后的文本与项目列表
        ResponseEntity<String> detail = rest.getForEntity(
                url("/api/resumes/" + body.path("resumeId").asText()), String.class);
        JsonNode resume = json(detail);
        assertThat(resume.path("projects").size()).isGreaterThan(0);
        assertThat(resume.path("text").asText()).doesNotContain("13812345678");
        assertThat(resume.path("text").asText()).doesNotContain("zhangwei_dev@example.com");
        assertThat(resume.path("text").asText()).contains("[手机号已脱敏]");
    }

    @Test
    @Order(4)
    @DisplayName("上传无文字层的 PDF 返回 422 与明确错误码，而不是 500")
    void uploadUnsupportedFileGivesClearError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource resource = new ByteArrayResource(imageOnlyPdf()) {
            @Override
            public String getFilename() {
                return "scan.pdf";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);

        ResponseEntity<String> response = rest.postForEntity(url("/api/resumes/import"),
                new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("UNSUPPORTED_FILE");
        assertThat(body.path("message").asText()).contains("图片");
    }

    @Test
    @Order(5)
    @DisplayName("完整闭环：创建会话 → 出题 → 回答 → 评分 → 追问 → 结束 → 报告（含 Markdown 下载）")
    void fullFlowOverHttp() throws Exception {
        String resumeId = uploadStandardResume();

        ResponseEntity<String> created = rest.postForEntity(url("/api/interviews"),
                Map.of("resumeId", resumeId, "mode", "PROJECT", "maxQuestions", 4), String.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String sessionId = json(created).path("id").asText();
        assertThat(sessionId).isNotBlank();

        ResponseEntity<String> questionResponse = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/next-question"), null, String.class);
        JsonNode question = json(questionResponse);
        String questionId = question.path("id").asText();
        assertThat(question.path("content").asText()).isNotBlank();
        assertThat(question.path("sourceIds").size()).isGreaterThan(0);
        assertThat(question.path("citations").size()).isGreaterThan(0);

        // 故意给一个不完整的回答，以便触发追问建议
        ResponseEntity<String> answerResponse = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/answers"),
                Map.of("questionId", questionId, "content", "用 Redis 加锁保证幂等，细节记不清了。"), String.class);
        JsonNode answer = json(answerResponse);
        assertThat(answer.path("evaluation").path("totalScore").asDouble()).isBetween(0.0, 5.0);
        assertThat(answer.path("evaluation").path("dimensions").size()).isEqualTo(4);
        assertThat(answer.path("nextAction").asText()).isIn("FOLLOW_UP", "NEXT_QUESTION", "FINISH");

        ResponseEntity<String> evaluation = rest.getForEntity(
                url("/api/interviews/" + sessionId + "/evaluation"), String.class);
        assertThat(evaluation.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(json(evaluation).path("evaluation").path("summary").asText()).isNotBlank();

        if ("FOLLOW_UP".equals(answer.path("nextAction").asText())) {
            ResponseEntity<String> followUp = rest.postForEntity(
                    url("/api/interviews/" + sessionId + "/follow-up"), null, String.class);
            JsonNode followUpQuestion = json(followUp);
            assertThat(followUpQuestion.path("followUp").asBoolean()).isTrue();
            rest.postForEntity(url("/api/interviews/" + sessionId + "/answers"),
                    Map.of("questionId", followUpQuestion.path("id").asText(),
                            "content", "具体用 SET NX PX 加锁，Lua 比对 value 后删除，续期 10 秒一次，压测 99.5%。"),
                    String.class);
        }

        // 继续出题直到上限（到达上限时后端会自动结束并返回 409）
        for (int i = 0; i < 6; i++) {
            ResponseEntity<String> next = rest.postForEntity(
                    url("/api/interviews/" + sessionId + "/next-question"), null, String.class);
            if (!next.getStatusCode().is2xxSuccessful()) {
                assertThat(json(next).path("code").asText()).isEqualTo("INVALID_SESSION_STATE");
                break;
            }
            String nextId = json(next).path("id").asText();
            ResponseEntity<String> answered = rest.postForEntity(
                    url("/api/interviews/" + sessionId + "/answers"),
                    Map.of("questionId", nextId,
                            "content", "背景是订单系统，我负责核销链路，通过 Redis 与唯一索引保证幂等，"
                                    + "难点在锁续期，用看门狗解决，压测显示重复下单为 0。"),
                    String.class);
            assertThat(answered.getStatusCode().is2xxSuccessful()).isTrue();
        }

        ResponseEntity<String> finished = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/finish"), Map.of("reason", "E2E 测试结束"), String.class);
        assertThat(json(finished).path("status").asText()).isEqualTo("FINISHED");

        ResponseEntity<String> report = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/report"), null, String.class);
        assertThat(report.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode reportBody = json(report);
        assertThat(reportBody.path("report").path("overallScore").asDouble()).isBetween(0.0, 5.0);
        assertThat(reportBody.path("report").path("abilityRadar").size()).isGreaterThan(3);
        assertThat(reportBody.path("markdown").asText()).contains("# 面试复盘报告");

        ResponseEntity<String> markdown = rest.getForEntity(
                url("/api/interviews/" + sessionId + "/report.md"), String.class);
        assertThat(markdown.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(markdown.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(markdown.getBody()).contains("## 二、能力雷达");

        ResponseEntity<String> sessions = rest.getForEntity(url("/api/interviews"), String.class);
        List<String> ids = new ArrayList<>();
        json(sessions).forEach(node -> ids.add(node.path("id").asText()));
        assertThat(ids).contains(sessionId);
    }

    @Test
    @Order(6)
    @DisplayName("错误处理：非法模式 400、未知会话 404、空回答 400、非法 JSON 400，且都不返回 500")
    void errorHandling() throws Exception {
        ResponseEntity<String> badMode = rest.postForEntity(url("/api/interviews"),
                Map.of("mode", "UNKNOWN_MODE"), String.class);
        assertThat(badMode.getStatusCode().value()).isEqualTo(400);
        assertThat(json(badMode).path("code").asText()).isEqualTo("VALIDATION_ERROR");

        ResponseEntity<String> unknown = rest.getForEntity(
                url("/api/interviews/does-not-exist"), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("NOT_FOUND");

        String resumeId = uploadStandardResume();
        ResponseEntity<String> created = rest.postForEntity(url("/api/interviews"),
                Map.of("resumeId", resumeId, "mode", "PROJECT", "maxQuestions", 2), String.class);
        String sessionId = json(created).path("id").asText();

        ResponseEntity<String> tooEarly = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/answers"),
                Map.of("content", "试图直接回答"), String.class);
        assertThat(tooEarly.getStatusCode().value()).isEqualTo(409);

        rest.postForEntity(url("/api/interviews/" + sessionId + "/next-question"), null, String.class);
        ResponseEntity<String> emptyAnswer = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/answers"),
                Map.of("content", "   "), String.class);
        assertThat(emptyAnswer.getStatusCode().value()).isEqualTo(400);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> malformed = rest.postForEntity(
                url("/api/interviews/" + sessionId + "/answers"),
                new HttpEntity<>("{这不是合法JSON}", headers), String.class);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(json(malformed).path("code").asText()).isEqualTo("INVALID_REQUEST_BODY");

        ResponseEntity<String> noRoute = rest.getForEntity(url("/api/not-a-real-endpoint"), String.class);
        assertThat(noRoute.getStatusCode().value()).isEqualTo(404);

        // 上传空文件：400
        HttpHeaders multipart = new HttpHeaders();
        multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource empty = new ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return "empty.txt";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", empty);
        ResponseEntity<String> emptyUpload = rest.postForEntity(url("/api/resumes/import"),
                new HttpEntity<>(form, multipart), String.class);
        assertThat(emptyUpload.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("检索调试接口能返回来源片段（用于验证问题是否有依据）")
    void retrievalDebugEndpoint() throws Exception {
        String resumeId = uploadStandardResume();
        ResponseEntity<String> response = rest.getForEntity(
                url("/api/retrieval/search?q=Redis%20分布式锁&mode=PROJECT&resumeId=" + resumeId), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = json(response);
        assertThat(body.path("chunks").size()).isGreaterThan(0);
        assertThat(body.path("citations").size()).isGreaterThan(0);
        assertThat(body.path("chunks").get(0).path("kind").asText()).isEqualTo("RESUME_FACT");

        ResponseEntity<String> knowledge = rest.getForEntity(
                url("/api/retrieval/search?q=MySQL%20索引&mode=KNOWLEDGE"), String.class);
        JsonNode knowledgeBody = json(knowledge);
        assertThat(knowledgeBody.path("chunks").get(0).path("kind").asText()).isEqualTo("KNOWLEDGE");
    }

    @Test
    @Order(8)
    @DisplayName("简历事实可人工修正，修正后出题依据同步更新")
    void resumeFactsEditableOverHttp() throws Exception {
        String resumeId = uploadStandardResume();
        ResponseEntity<String> detail = rest.getForEntity(url("/api/resumes/" + resumeId), String.class);
        JsonNode resume = json(detail);
        List<Map<String, Object>> facts = new ArrayList<>();
        resume.path("facts").forEach(fact -> facts.add(Map.of(
                "id", fact.path("id").asText(),
                "type", fact.path("type").asText(),
                "label", fact.path("type").asText().equals("PROJECT") ? "修正项目名" : fact.path("label").asText(),
                "content", fact.path("content").asText() + "（已人工确认）")));
        assertThat(facts).isNotEmpty();

        ResponseEntity<String> updated = rest.exchange(url("/api/resumes/" + resumeId + "/facts"),
                org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(Map.of("facts", facts), jsonHeaders()), String.class);
        assertThat(updated.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode updatedBody = json(updated);
        assertThat(updatedBody.path("projects").size()).isGreaterThan(0);
        assertThat(updatedBody.path("projects").get(0).path("name").asText()).isEqualTo("修正项目名");
        // 修正后的事实是出题与检索的依据，必须带上人工确认标记
        List<String> contents = new ArrayList<>();
        updatedBody.path("facts").forEach(fact -> contents.add(fact.path("content").asText()));
        assertThat(contents).allSatisfy(content -> assertThat(content).contains("已人工确认"));

        // 修正后再出题，检索到的依据应来自修正后的内容
        ResponseEntity<String> search = rest.getForEntity(
                url("/api/retrieval/search?q=修正项目名&mode=PROJECT&resumeId=" + resumeId), String.class);
        assertThat(json(search).path("chunks").size()).isGreaterThan(0);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String uploadStandardResume() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource resource = new ByteArrayResource(Fixtures.bytes("resume-standard.txt")) {
            @Override
            public String getFilename() {
                return "张伟-简历.txt";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);
        ResponseEntity<String> response = rest.postForEntity(url("/api/resumes/import"),
                new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return json(response).path("resumeId").asText();
    }

    /** 构造一个只有绘图指令、没有文字层的极小 PDF（模拟扫描件）。 */
    private byte[] imageOnlyPdf() {
        String body = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>\nendobj\n"
                + "4 0 obj\n<< /Length 44 >>\nstream\nq 100 0 0 100 10 10 cm /Im0 Do Q\nendstream\nendobj\n"
                + "trailer\n<< /Size 5 /Root 1 0 R >>\n%%EOF\n";
        return body.getBytes(StandardCharsets.ISO_8859_1);
    }
}
