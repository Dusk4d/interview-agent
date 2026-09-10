package com.dusk4d.interview.agent;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.InterviewStage;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.error.LlmException;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LlmRequest;
import com.dusk4d.interview.rag.DocumentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 问题生成器（对应方案书 6.4）。
 *
 * <p>两类问题使用不同的提示与推进顺序：
 * <ul>
 *   <li>项目面：事实确认 → 技术机制 → 设计取舍 → 异常边界 → 验证结果</li>
 *   <li>八股面：概念 → 原理 → 应用 → 边界 → 对比</li>
 * </ul>
 *
 * <p>生成后做三项校验：问题是否引用到了正确来源、是否与已问问题重复、是否超出事实边界。
 * 模型不可用时降级为「模板出题」——仍然只用检索到的片段作为事实来源，
 * 不会编造简历中不存在的内容，同时明确标记 degraded，前端会提示「模型不可用，已降级」。
 */
@Component
public class QuestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerator.class);

    private static final List<String> SCHEMA_FIELDS =
            List.of("type", "difficulty", "question", "intent", "focus", "followUpPlan");

    private final LlmClient llmClient;
    private final StructuredOutputParser parser;
    private final AppProperties properties;

    public QuestionGenerator(LlmClient llmClient, StructuredOutputParser parser, AppProperties properties) {
        this.llmClient = llmClient;
        this.parser = parser;
        this.properties = properties;
    }

    /** 出题上下文：检索到的片段与其来源。 */
    public record QuestionContext(String context, List<String> sourceIds, List<String> citations, String projectName) {

        public QuestionContext {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            citations = citations == null ? List.of() : List.copyOf(citations);
            context = context == null ? "" : context;
        }

        public static QuestionContext of(DocumentRetriever.RetrievalResult result) {
            return new QuestionContext(result.context(), result.chunkIds(), result.citations(), null);
        }

        public boolean empty() {
            return context.isBlank();
        }
    }

    /** 生成结果。 */
    public record GeneratedQuestion(
            QuestionPlan plan,
            boolean degraded,
            String degradationReason
    ) {
    }

    /**
     * 生成一道主问题。
     *
     * @param session   当前会话
     * @param mode      面试模式
     * @param stage     阶段
     * @param difficulty 目标难度
     * @param context   出题上下文
     * @param requestedType 指定题型（可为 null，由生成器决定）
     */
    public GeneratedQuestion generate(InterviewSession session, InterviewMode mode, InterviewStage stage,
                                      Difficulty difficulty, QuestionContext context, QuestionType requestedType) {
        if (context == null || context.empty()) {
            // 检索为空：不硬凑，直接给出明确原因，由上层提示用户补简历或换主题
            return new GeneratedQuestion(
                    fallback(mode, stage, difficulty, null, List.of()),
                    true,
                    "没有检索到可用的事实片段，已使用通用模板出题。请确认简历解析结果或更换练习主题。");
        }

        String userPrompt = Prompts.questionUser(mode, stage, difficulty, context.context(),
                session == null ? List.of() : session.askedQuestionDigests(), context.projectName());
        if (requestedType != null) {
            userPrompt = userPrompt + "\n【指定题型】本次必须使用题型：" + requestedType.name() + "\n";
        }
        try {
            var response = llmClient.chat(LlmRequest.structured(Prompts.QUESTION_SYSTEM, userPrompt,
                    "question", SCHEMA_FIELDS, properties.llm().temperature(), properties.llm().maxTokens()));
            Map<String, Object> parsed = parser.parseObject(response.content(), SCHEMA_FIELDS);
            return toPlan(parsed, mode, stage, difficulty, context, false);
        } catch (LlmException e) {
            log.warn("问题生成失败（{}），已降级为模板出题：{}", e.kind(), e.getMessage());
            return new GeneratedQuestion(
                    fallback(mode, stage, difficulty, context, List.of()),
                    true,
                    degradeReason(e));
        }
    }

    /** 依据评估遗漏点生成追问。 */
    public GeneratedQuestion generateFollowUp(InterviewQuestion parent, String answer, String missingPoints,
                                              String followUpFocus, QuestionContext context) {
        String userPrompt = Prompts.followUpUser(parent, answer, missingPoints,
                context == null ? "" : context.context(), followUpFocus);
        try {
            var response = llmClient.chat(LlmRequest.structured(Prompts.FOLLOW_UP_SYSTEM, userPrompt,
                    "followup", SCHEMA_FIELDS, properties.llm().temperature(), properties.llm().maxTokens()));
            Map<String, Object> parsed = parser.parseObject(response.content(), SCHEMA_FIELDS);
            QuestionPlan plan = new QuestionPlan(
                    QuestionType.FOLLOW_UP,
                    Difficulty.parse(StructuredOutputParser.string(parsed, "difficulty", "HARD")),
                    StructuredOutputParser.string(parsed, "question", defaultFollowUpText(missingPoints)),
                    StructuredOutputParser.string(parsed, "intent", "验证回答深度与真实性"),
                    StructuredOutputParser.stringList(parsed, "focus"),
                    StructuredOutputParser.string(parsed, "followUpPlan", "不再追问，进入下一题"),
                    context == null ? List.of() : context.sourceIds());
            return new GeneratedQuestion(plan, false, null);
        } catch (LlmException e) {
            log.warn("追问生成失败（{}），已降级为模板追问：{}", e.kind(), e.getMessage());
            QuestionPlan plan = new QuestionPlan(
                    QuestionType.FOLLOW_UP,
                    Difficulty.HARD,
                    defaultFollowUpText(missingPoints),
                    "验证回答深度与真实性",
                    List.of("实现细节", "异常边界"),
                    "不再追问，进入下一题",
                    context == null ? List.of() : context.sourceIds());
            return new GeneratedQuestion(plan, true, degradeReason(e));
        }
    }

    // ---------------------------------------------------------------- 结果转换与校验

    private GeneratedQuestion toPlan(Map<String, Object> parsed, InterviewMode mode, InterviewStage stage,
                                     Difficulty difficulty, QuestionContext context, boolean degraded) {
        String questionText = StructuredOutputParser.string(parsed, "question", "");
        if (questionText.isBlank()) {
            throw LlmException.invalidStructure("生成的问题为空");
        }
        QuestionType type = parseType(StructuredOutputParser.string(parsed, "type", ""), mode, stage);
        List<String> focus = StructuredOutputParser.stringList(parsed, "focus");
        List<String> sourceIds = new ArrayList<>(context.sourceIds());
        List<String> modelSourceIds = StructuredOutputParser.stringList(parsed, "sourceIds");
        for (String id : modelSourceIds) {
            // 只接受真实存在的来源 ID，避免模型自造引用
            if (context.sourceIds().contains(id) && !sourceIds.contains(id)) {
                sourceIds.add(id);
            }
        }
        QuestionPlan plan = new QuestionPlan(
                type,
                Difficulty.parse(StructuredOutputParser.string(parsed, "difficulty", difficulty.name())),
                questionText.strip(),
                StructuredOutputParser.string(parsed, "intent", ""),
                focus.isEmpty() ? List.of("技术机制") : focus,
                StructuredOutputParser.string(parsed, "followUpPlan", ""),
                sourceIds);
        return new GeneratedQuestion(plan, degraded, null);
    }

    private String degradeReason(LlmException e) {
        return switch (e.kind()) {
            case CONNECTION -> "模型服务未连接，已使用模板出题。请启动本地模型（LM Studio / Ollama）后重试。";
            case TIMEOUT -> "模型响应超时，已使用模板出题。";
            case BAD_RESPONSE -> "模型返回异常，已使用模板出题。";
            case INVALID_STRUCTURE -> "模型输出格式不符合要求，已使用模板出题。";
        };
    }

    private QuestionType parseType(String raw, InterviewMode mode, InterviewStage stage) {
        if (raw != null && !raw.isBlank()) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            for (QuestionType type : QuestionType.values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
        }
        return defaultType(mode, stage);
    }

    private QuestionType defaultType(InterviewMode mode, InterviewStage stage) {
        if (mode == InterviewMode.PROJECT) {
            return QuestionType.PROJECT_TECH;
        }
        if (mode == InterviewMode.KNOWLEDGE) {
            return QuestionType.PRINCIPLE;
        }
        return switch (stage) {
            case SELF_INTRO -> QuestionType.BEHAVIOR;
            case PROJECT -> QuestionType.PROJECT;
            case PROJECT_DEEP_DIVE -> QuestionType.PROJECT_TRADEOFF;
            case FUNDAMENTALS -> QuestionType.PRINCIPLE;
            case CANDIDATE_QUESTIONS -> QuestionType.BEHAVIOR;
            default -> QuestionType.PROJECT_TECH;
        };
    }

    /**
     * 模板出题（降级路径）。
     *
     * <p>模板只在检索到的片段上做「提问角度」变换，不引入新事实。
     */
    private QuestionPlan fallback(InterviewMode mode, InterviewStage stage, Difficulty difficulty,
                                  QuestionContext context, List<String> sourceIds) {
        String subject = context == null ? "" : firstFactLine(context.context());
        String project = context == null || context.projectName() == null || context.projectName().isBlank()
                ? "你简历中的这个项目" : "「" + context.projectName() + "」";
        List<String> effectiveSources = context == null ? sourceIds : context.sourceIds();

        if (mode == InterviewMode.PROJECT) {
            return new QuestionPlan(QuestionType.PROJECT_TECH, difficulty,
                    project + "用到的主要技术方案是什么？请说明你个人负责的部分、为什么这样选，以及它解决了什么问题。",
                    "考察项目事实、个人职责与技术选型动机",
                    List.of("个人职责", "技术选型", "解决的问题"),
                    "如果只回答了技术名词，追问具体实现机制与替代方案对比",
                    effectiveSources);
        }
        if (mode == InterviewMode.KNOWLEDGE) {
            String topic = subject.isBlank() ? "该知识点" : subject;
            return new QuestionPlan(QuestionType.PRINCIPLE, difficulty,
                    "请说明「" + shorten(topic, 40) + "」的实现原理、适用边界以及常见误区。",
                    "考察基础知识的原理理解与边界意识",
                    List.of("原理机制", "适用边界", "常见误区"),
                    "如果只回答了定义，追问一个边界条件或反例",
                    effectiveSources);
        }
        return switch (stage) {
            case SELF_INTRO -> new QuestionPlan(QuestionType.BEHAVIOR, Difficulty.EASY,
                    "请用 1 分钟做自我介绍：技术方向、最匹配的一段经历，以及你希望深入的方向。",
                    "考察表达结构与岗位匹配度",
                    List.of("表达结构", "岗位匹配度"),
                    "如果缺少量化信息，追问一个最能体现能力的项目细节",
                    effectiveSources);
            case FUNDAMENTALS -> new QuestionPlan(QuestionType.PRINCIPLE, difficulty,
                    "结合你简历中用到的技术，说明其中一个你最有把握的知识点的原理与边界条件。",
                    "考察基础知识与项目结合能力",
                    List.of("原理机制", "边界条件"),
                    "如果停留在概念层面，追问与项目中实际用法的差异",
                    effectiveSources);
            case CANDIDATE_QUESTIONS -> new QuestionPlan(QuestionType.BEHAVIOR, Difficulty.EASY,
                    "你有什么想问面试官的问题？请说明你关注这个问题的原因。",
                    "考察对岗位与团队的理解",
                    List.of("关注点", "岗位理解"),
                    "如果问题过于宽泛，追问你最在意的技术挑战",
                    effectiveSources);
            default -> new QuestionPlan(QuestionType.PROJECT_TRADEOFF, difficulty,
                    project + "中最难的技术点是什么？你当时如何定位问题、如何验证解决效果？",
                    "考察技术难点与验证意识",
                    List.of("技术难点", "定位过程", "验证方式"),
                    "如果只描述现象，追问根因分析与量化验证",
                    effectiveSources);
        };
    }

    private String defaultFollowUpText(String missingPoints) {
        String focus = missingPoints == null || missingPoints.isBlank()
                ? "实现细节与异常边界" : firstLine(missingPoints);
        return "刚才的回答还停留在结论层面，请具体说明：" + shorten(focus, 60) + "。请给出具体实现方式与验证手段。";
    }

    private String firstFactLine(String context) {
        for (String line : context.split("\n")) {
            String cleaned = line.strip();
            if (!cleaned.isEmpty() && !cleaned.startsWith("[")) {
                return cleaned;
            }
        }
        return "";
    }

    private String firstLine(String value) {
        String cleaned = value.replaceAll("[\\[\\]\"']", "").strip();
        int index = cleaned.indexOf('\n');
        String line = index < 0 ? cleaned : cleaned.substring(0, index);
        return line.replaceAll("^[-*、\\d.]+\\s*", "").strip();
    }

    private String shorten(String value, int max) {
        String flattened = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }
}
