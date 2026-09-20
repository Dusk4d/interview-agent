package com.dusk4d.interview.api;

import com.dusk4d.interview.agent.InterviewStateMachine;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.error.NotFoundException;
import com.dusk4d.interview.error.ValidationException;
import com.dusk4d.interview.llm.EmbeddingClient;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LlmDiagnostics;
import com.dusk4d.interview.llm.MockLlmClient;
import com.dusk4d.interview.rag.DocumentRetriever;
import com.dusk4d.interview.rag.KnowledgeBase;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.service.InterviewService;
import com.dusk4d.interview.service.ResumeImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * REST 接口层（对应方案书第八节 API 草案）。
 *
 * <p>接口层只做三件事：参数校验、调用服务、把领域对象转成视图。
 * 业务规则（状态机、次数上限、降级策略）都在服务层，保证 HTTP 与未来其它入口行为一致。
 */
@RestController
@RequestMapping("/api")
public class InterviewController {

    private final ResumeImportService resumeService;
    private final InterviewService interviewService;
    private final KnowledgeBase knowledgeBase;
    private final VectorStore vectorStore;
    private final LlmClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final DocumentRetriever retriever;
    private final AppProperties properties;
    private final org.springframework.core.env.Environment environment;
    private final LlmDiagnostics diagnostics;

    public InterviewController(ResumeImportService resumeService,
                               InterviewService interviewService,
                               KnowledgeBase knowledgeBase,
                               VectorStore vectorStore,
                               LlmClient llmClient,
                               EmbeddingClient embeddingClient,
                               DocumentRetriever retriever,
                               AppProperties properties,
                               org.springframework.core.env.Environment environment,
                               LlmDiagnostics diagnostics) {
        this.resumeService = resumeService;
        this.interviewService = interviewService;
        this.knowledgeBase = knowledgeBase;
        this.vectorStore = vectorStore;
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.retriever = retriever;
        this.properties = properties;
        this.environment = environment;
        this.diagnostics = diagnostics;
    }

    // ---------------------------------------------------------------- 系统状态

    @GetMapping("/health")
    public Dtos.HealthView health() {
        // Mock 模式没有真实端点可探测，直接给出结论，避免去连 1234 产生无意义的等待。
        boolean mock = llmClient instanceof MockLlmClient;
        LlmDiagnostics.Diagnosis diagnosis = mock
                ? new LlmDiagnostics.Diagnosis(true, "离线 Mock 模式：模板出题 + 启发式评分，不会调用真实模型", null, List.of())
                : diagnostics.diagnose(properties.llm().resolvedBaseUrl(), properties.llm().resolvedChatModel());
        return new Dtos.HealthView(
                "UP",
                "0.1.0",
                llmClient.provider(),
                llmClient.modelName(),
                diagnosis.available(),
                properties.llm().resolvedBaseUrl(),
                diagnosis.status(),
                diagnosis.hint(),
                diagnosis.availableModels(),
                properties.llm().shouldDisableThinking(),
                embeddingClient.provider(),
                embeddingClient.dimension(),
                vectorStore.size(),
                knowledgeBase.size(),
                resumeService.list().size(),
                interviewService.listSessions().size(),
                properties.storage().inMemory() ? "memory" : "file",
                knowledgeBase.topics());
    }

    /**
     * 配置诊断：排查「外部配置未生效」类问题（环境变量 / 命令行参数 / 配置文件优先级）。
     *
     * <p>为什么保留这个接口：曾经出现过一个很难发现的缺陷——{@code Environment} 能解析出正确的
     * {@code app.llm.base-url}，但注入服务的 {@code AppProperties} 却是默认值（绑定被静默放弃）。
     * 当时只能靠临时加字段来定位。保留本接口后，同类问题可以直接对比
     * {@code env.*}（进程环境变量）与 {@code bound.*}（最终绑定结果）来一眼定位，
     * 不必再改代码重新打包。
     *
     * <p>返回内容只涉及运行环境与配置，不含任何简历或用户数据。
     */
    @GetMapping("/diagnostics/config")
    public java.util.Map<String, Object> configDiagnostics() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("appHome", System.getProperty("app.home", "(unset)"));
        result.put("workingDir", System.getProperty("user.dir"));
        result.put("activeProfiles", java.util.List.of(environment.getActiveProfiles()));

        // 环境变量（可能是 null，说明容器/进程没有注入）
        java.util.Map<String, Object> env = new java.util.LinkedHashMap<>();
        for (String name : java.util.List.of("INTERVIEW_LLM_BASE_URL", "INTERVIEW_LLM_CHAT_MODEL",
                "INTERVIEW_LLM_MAX_TOKENS", "INTERVIEW_LLM_EVAL_TEMPERATURE", "INTERVIEW_LLM_EVAL_SAMPLES",
                "INTERVIEW_DATA_DIR", "INTERVIEW_STORAGE_MODE", "INTERVIEW_EMBEDDING_MODE",
                "INTERVIEW_KNOWLEDGE_PATH")) {
            env.put(name, System.getenv(name));
        }
        result.put("env", env);

        // Spring 解析后的属性值（含占位符替换结果）
        java.util.Map<String, Object> resolved = new java.util.LinkedHashMap<>();
        for (String key : java.util.List.of("app.llm.base-url", "app.llm.chat-model", "app.llm.max-tokens",
                "app.llm.eval-temperature", "app.llm.eval-samples", "app.storage.mode")) {
            resolved.put(key, environment.getProperty(key));
        }
        result.put("resolved", resolved);

        // 最终绑定到 AppProperties 的值：必须与上面的 resolved 一致，否则就是绑定出了问题
        java.util.Map<String, Object> bound = new java.util.LinkedHashMap<>();
        bound.put("baseUrl", properties.llm().resolvedBaseUrl());
        bound.put("chatModel", properties.llm().resolvedChatModel());
        bound.put("maxTokens", properties.llm().maxTokens());
        bound.put("evalTemperature", properties.llm().resolvedEvalTemperature());
        bound.put("evalSamples", properties.llm().resolvedEvalSamples());
        bound.put("disableThinking", properties.llm().shouldDisableThinking());
        bound.put("storageMode", properties.storage().inMemory() ? "memory" : "file");
        result.put("bound", bound);

        java.util.List<String> sources = new java.util.ArrayList<>();
        for (var source : ((org.springframework.core.env.ConfigurableEnvironment) environment).getPropertySources()) {
            sources.add(source.getName());
        }
        result.put("propertySources", sources);
        return result;
    }

    // ---------------------------------------------------------------- 简历

    /** 上传并解析简历（PDF / DOCX / TXT）。 */
    @PostMapping(value = "/resumes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.ResumeImportResponse importResume(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("请选择要上传的简历文件（支持 PDF / DOCX / TXT）。");
        }
        try {
            return ApiMapper.toImportView(resumeService.importFile(file.getOriginalFilename(), file.getBytes()));
        } catch (IOException e) {
            throw new ValidationException("读取上传文件失败：" + e.getMessage());
        }
    }

    /** 粘贴纯文本导入（兜底输入）。 */
    @PostMapping("/resumes/text")
    public Dtos.ResumeImportResponse importText(@RequestBody Dtos.TextImportRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ValidationException("粘贴的简历内容不能为空。");
        }
        return ApiMapper.toImportView(resumeService.importText(request.text(), request.fileName()));
    }

    @GetMapping("/resumes")
    public List<Dtos.ResumeView> listResumes() {
        return resumeService.list().stream().map(ApiMapper::toView).toList();
    }

    @GetMapping("/resumes/{id}")
    public Dtos.ResumeView getResume(@PathVariable String id) {
        return ApiMapper.toView(resumeService.require(id));
    }

    /** 人工修正解析结果。 */
    @PutMapping("/resumes/{id}/facts")
    public Dtos.ResumeView updateFacts(@PathVariable String id, @RequestBody Dtos.FactUpdateRequest request) {
        if (request == null || request.facts() == null || request.facts().isEmpty()) {
            throw new ValidationException("修正内容不能为空。");
        }
        List<ResumeFact> facts = new ArrayList<>();
        for (Dtos.FactInput input : request.facts()) {
            if (input == null) {
                continue;
            }
            FactType type = ApiMapper.parseFactType(input.type());
            facts.add(new ResumeFact(input.id(), id, type, input.label(), input.content(), 0, 1.0, List.of()));
        }
        return ApiMapper.toView(resumeService.updateFacts(id, facts));
    }

    @DeleteMapping("/resumes/{id}")
    public Dtos.OkResponse deleteResume(@PathVariable String id) {
        resumeService.require(id);
        resumeService.delete(id);
        return Dtos.OkResponse.of("简历已删除，相关检索片段已清理。");
    }

    // ---------------------------------------------------------------- 面试会话

    @PostMapping("/interviews")
    public Dtos.SessionView createInterview(@RequestBody Dtos.CreateSessionRequest request) {
        if (request == null) {
            throw new ValidationException("请求体不能为空。");
        }
        InterviewMode mode = ApiMapper.parseMode(request.mode());
        InterviewSession session = interviewService.createSession(request.resumeId(), mode, request.maxQuestions());
        return ApiMapper.toView(session, InterviewStateMachine.describe(session.status()));
    }

    @GetMapping("/interviews")
    public List<Dtos.SessionView> listInterviews() {
        return interviewService.listSessions().stream()
                .map(s -> ApiMapper.toView(s, InterviewStateMachine.describe(s.status())))
                .toList();
    }

    @GetMapping("/interviews/{id}")
    public Dtos.SessionView getInterview(@PathVariable String id) {
        InterviewService.SessionView view = interviewService.view(id);
        return ApiMapper.toView(view.session(), view.stateDescription());
    }

    /** 获取下一道题。 */
    @PostMapping("/interviews/{id}/next-question")
    public Dtos.QuestionView nextQuestion(@PathVariable String id) {
        InterviewService.QuestionView view = interviewService.nextQuestion(id);
        return ApiMapper.toView(view.question(), view.degraded(), view.degradationReason());
    }

    /** 提交文字回答。 */
    @PostMapping("/interviews/{id}/answers")
    public Dtos.AnswerResultView submitAnswer(@PathVariable String id, @RequestBody Dtos.AnswerRequest request) {
        if (request == null || request.content() == null) {
            throw new ValidationException("回答内容不能为空。");
        }
        InterviewService.AnswerResult result = interviewService.submitAnswer(id, request.questionId(),
                request.content());
        return new Dtos.AnswerResultView(
                result.answer().id(),
                result.answer().questionId(),
                ApiMapper.toView(result.evaluation()),
                ApiMapper.toView(result.session(), InterviewStateMachine.describe(result.session().status())),
                result.nextAction(),
                result.nextActionHint());
    }

    /** 查询最近一次回答的反馈。 */
    @GetMapping("/interviews/{id}/evaluation")
    public Dtos.EvaluationResponse evaluation(@PathVariable String id) {
        InterviewSession session = interviewService.requireSession(id);
        Optional<com.dusk4d.interview.domain.AnswerEvaluation> evaluation = interviewService.latestEvaluation(id);
        if (evaluation.isEmpty()) {
            throw new NotFoundException("评分反馈（请先提交回答）", id);
        }
        return new Dtos.EvaluationResponse(
                ApiMapper.toView(evaluation.get()),
                ApiMapper.toView(session, InterviewStateMachine.describe(session.status())),
                session.status().name(),
                "可以继续追问、获取下一题或结束面试。");
    }

    /** 请求一次追问。 */
    @PostMapping("/interviews/{id}/follow-up")
    public Dtos.QuestionView followUp(@PathVariable String id) {
        InterviewService.QuestionView view = interviewService.followUp(id);
        return ApiMapper.toView(view.question(), view.degraded(), view.degradationReason());
    }

    /** 结束面试。 */
    @PostMapping("/interviews/{id}/finish")
    public Dtos.SessionView finish(@PathVariable String id,
                                   @RequestBody(required = false) Dtos.FinishRequest request) {
        InterviewSession session = interviewService.finish(id,
                request == null ? null : request.reason());
        return ApiMapper.toView(session, InterviewStateMachine.describe(session.status()));
    }

    /** 取消面试。 */
    @PostMapping("/interviews/{id}/cancel")
    public Dtos.SessionView cancel(@PathVariable String id) {
        InterviewSession session = interviewService.cancel(id);
        return ApiMapper.toView(session, InterviewStateMachine.describe(session.status()));
    }

    // ---------------------------------------------------------------- 报告

    /** 生成（或重新生成）复盘报告。 */
    @PostMapping("/interviews/{id}/report")
    public Dtos.ReportResponse generateReport(@PathVariable String id) {
        InterviewReport report = interviewService.generateReport(id);
        return new Dtos.ReportResponse(ApiMapper.toView(report), report.markdown());
    }

    /** 查看复盘报告（需先生成）。 */
    @GetMapping("/interviews/{id}/report")
    public Dtos.ReportResponse report(@PathVariable String id) {
        InterviewReport report = interviewService.requireReport(id);
        return new Dtos.ReportResponse(ApiMapper.toView(report), report.markdown());
    }

    /** 下载 Markdown 报告。 */
    @GetMapping(value = "/interviews/{id}/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> reportMarkdown(@PathVariable String id) {
        InterviewReport report = interviewService.requireReport(id);
        byte[] body = report.markdown().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"interview-report-" + id + ".md\"")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(body);
    }

    // ---------------------------------------------------------------- 知识库

    @GetMapping("/knowledge/topics")
    public List<String> knowledgeTopics() {
        return knowledgeBase.topics();
    }

    @GetMapping("/knowledge/items")
    public List<java.util.Map<String, Object>> knowledgeItems(@RequestParam(required = false) String topic) {
        return knowledgeBase.byTopic(topic).stream()
                .map(item -> java.util.Map.<String, Object>of(
                        "id", item.id(),
                        "topic", item.topic(),
                        "subtopic", item.subtopic() == null ? "" : item.subtopic(),
                        "difficulty", item.difficulty().name(),
                        "title", item.title(),
                        "keyPoints", item.keyPoints()))
                .toList();
    }

    /** 检索调试接口：用于验证「问题是否有依据」与来源追溯。 */
    @GetMapping("/retrieval/search")
    public java.util.Map<String, Object> search(@RequestParam String q,
                                                @RequestParam(required = false) String resumeId,
                                                @RequestParam(defaultValue = "PROJECT") String mode,
                                                @RequestParam(defaultValue = "5") int topK) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("查询内容不能为空。");
        }
        DocumentRetriever.RetrievalResult result = switch (ApiMapper.parseMode(mode)) {
            case KNOWLEDGE -> retriever.retrieveKnowledge(q);
            case PROJECT -> retriever.retrieveFacts(resumeId, q);
            case FULL -> retriever.retrieveMixed(resumeId, q);
        };
        return java.util.Map.of(
                "query", q,
                "empty", result.noHits(),
                "citations", result.citations(),
                "chunks", result.chunks().stream().map(sc -> java.util.Map.of(
                        "id", sc.chunk().id(),
                        "kind", sc.chunk().kind().name(),
                        "label", sc.chunk().label() == null ? "" : sc.chunk().label(),
                        "score", sc.score(),
                        "fusedScore", sc.fusedScore(),
                        "text", abbreviate(sc.chunk().text()))).toList(),
                "contextPreview", abbreviate(result.context()));
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }
}
