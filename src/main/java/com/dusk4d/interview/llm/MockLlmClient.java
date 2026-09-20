package com.dusk4d.interview.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dusk4d.interview.error.LlmException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性 Mock 模型客户端。
 *
 * <p>用途：让整条链路（出题 → 回答 → 评分 → 追问 → 报告）在没有本地模型、没有网络的环境下
 * 也能完整跑通，并且结果可复现——这是自动化测试与演示的基础。
 *
 * <p>行为约定：根据 systemPrompt 中的任务标记产出结构一致的 JSON；
 * 分数由「回答内容哈希」稳定映射得到，因此同一回答永远得到同一分数，
 * 使得「评分一致性」这类断言可写。
 *
 * <p>还可通过 {@link #failWith} / {@link #alwaysMalformedJson} / {@link #alwaysEmpty}
 * 注入失败路径，用于验证降级与错误提示。
 */
public class MockLlmClient implements LlmClient {

    private static final Pattern ANSWER_MARKER = Pattern.compile("【用户回答】\\s*(.*?)(?:\\n\\s*【|$)", Pattern.DOTALL);
    private static final Pattern QUESTION_MARKER = Pattern.compile("【当前问题】\\s*(.*?)(?:\\n\\s*【|$)", Pattern.DOTALL);
    /**
     * 聚焦项目行：由 {@code Prompts.questionUser} 输出，是取项目名最可靠的位置。
     *
     * <p>曾经这里用「【简历片段…】」正则去上下文里抓项目名，但检索上下文实际格式是
     * 「[1] 简历片段：项目名」，两边不一致 → 项目名取不到，模板出题退化成「该项目」。
     * 现在优先读这一行，正则只作为兜底，并同时兼容两种写法。
     */
    private static final Pattern FOCUS_PROJECT_MARKER =
            Pattern.compile("【当前聚焦项目】\\s*(.+?)\\s*(?:\\n|$)", Pattern.DOTALL);
    private static final Pattern PROJECT_MARKER =
            Pattern.compile("【简历片段[^】]*】\\s*(.*?)(?:\\n\\s*【|$)|简历片段[:：]\\s*(.+?)\\s*(?:\\n|$)",
                    Pattern.DOTALL);
    private static final Pattern KNOWLEDGE_MARKER =
            Pattern.compile("【知识点[^】]*】\\s*(.*?)(?:\\n\\s*【|$)|知识点[:：]\\s*(.+?)\\s*(?:\\n|$)",
                    Pattern.DOTALL);
    /** 「已经问过的问题」段落标题，必须与 {@code Prompts.questionUser} 保持一致。 */
    private static final String ASKED_HEADER = "【已经问过的问题，必须避免重复】";

    private final ObjectMapper objectMapper;
    private final boolean alwaysMalformedJson;
    private final boolean alwaysEmpty;
    private final LlmFailureKind failWith;
    private int callCount;

    public MockLlmClient(ObjectMapper objectMapper) {
        this(objectMapper, false, false, null);
    }

    public MockLlmClient(ObjectMapper objectMapper, boolean alwaysMalformedJson, boolean alwaysEmpty,
                         LlmFailureKind failWith) {
        this.objectMapper = objectMapper;
        this.alwaysMalformedJson = alwaysMalformedJson;
        this.alwaysEmpty = alwaysEmpty;
        this.failWith = failWith;
    }

    /** 便捷构造：强制某种失败，用于测试降级路径。 */
    public static MockLlmClient failing(ObjectMapper objectMapper, LlmFailureKind kind) {
        return new MockLlmClient(objectMapper, false, false, kind);
    }

    /** 便捷构造：始终返回非法 JSON。 */
    public static MockLlmClient malformed(ObjectMapper objectMapper) {
        return new MockLlmClient(objectMapper, true, false, null);
    }

    /** 便捷构造：始终返回空内容。 */
    public static MockLlmClient emptyLlm(ObjectMapper objectMapper) {
        return new MockLlmClient(objectMapper, false, true, null);
    }

    /** 已处理调用次数（用于断言「重试只发生一次」这类行为）。 */
    public int callCount() {
        return callCount;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        callCount++;
        if (failWith != null) {
            throw switch (failWith) {
                case CONNECTION -> LlmException.connection("mock：模型服务未启动", null);
                case TIMEOUT -> LlmException.timeout("mock：模型响应超时", null);
                case BAD_RESPONSE -> LlmException.badResponse("mock：模型返回 HTTP 500", null);
                case INVALID_STRUCTURE -> LlmException.invalidStructure("mock：结构化输出非法");
            };
        }
        if (alwaysMalformedJson) {
            return new LlmResponse("我觉得这个回答还不错。{不是 JSON}", modelName(), 1L);
        }
        if (alwaysEmpty) {
            return new LlmResponse("", modelName(), 1L);
        }
        String content = request.expectsJson()
                ? structured(request)
                : plainText(request);
        return new LlmResponse(content, modelName(), 1L);
    }

    // ---------------------------------------------------------------- 结构化输出

    private String structured(LlmRequest request) {
        String schema = request.schemaName() == null ? "" : request.schemaName().toLowerCase(Locale.ROOT);
        ObjectNode node = switch (schema) {
            case "evaluation" -> evaluation(request);
            case "question" -> question(request);
            case "followup" -> followUp(request);
            case "report" -> report(request);
            default -> generic(request);
        };
        try {
            return objectMapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private ObjectNode evaluation(LlmRequest request) {
        String answer = firstNonBlank(firstGroup(ANSWER_MARKER, request.userPrompt()));
        String question = firstNonBlank(firstGroup(QUESTION_MARKER, request.userPrompt()));
        int seed = stableHash(answer.isEmpty() ? "EMPTY" : answer);

        boolean empty = answer.isBlank();
        boolean tooShort = !empty && answer.strip().length() < 20;
        boolean irrelevant = containsAny(answer, "不知道", "不会", "没做过", "随便", "不清楚");
        boolean hasDetail = containsAny(answer, "因为", "所以", "具体", "实现", "使用", "通过", "结果", "首先");
        boolean hasResult = containsAny(answer, "%", "ms", "秒", "QPS", "提升", "下降", "上线");
        boolean hasStructure = containsAny(answer, "背景", "职责", "难点", "结果", "方案", "首先", "其次", "最后");

        int technical = empty ? 0 : irrelevant ? 1 : clamp(2 + (hasDetail ? 2 : 0) + (seed % 2), 0, 5);
        int completeness = empty ? 0 : tooShort ? 1 : clamp(2 + (hasDetail ? 1 : 0) + (hasResult ? 1 : 0), 0, 5);
        int match = empty ? 0 : tooShort ? 2 : clamp(3 + (hasResult ? 1 : 0), 0, 5);
        int structure = empty ? 0 : hasStructure ? 5 : tooShort ? 1 : 3;

        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", empty ? "没有收到有效回答，无法评估内容。"
                : tooShort ? "回答过于简短，缺少技术细节，建议按「背景-动作-机制-结果」展开。"
                : irrelevant ? "回答未正面回应问题，需要重新组织内容。"
                : "回答覆盖了主要方向，补充机制细节与量化结果会更有说服力。");

        ObjectNode dims = root.putObject("dimensionScores");
        dims.set("technical_correctness", dimension("技术正确性", technical,
                empty ? "未作答。" : "回答中的技术表述与" + (hasDetail ? "问题考察方向一致。" : "问题要求的机制仍有差距。")));
        dims.set("completeness", dimension("内容完整性", completeness,
                hasResult ? "包含结果信息，但关键点覆盖仍可补充。" : "缺少问题要求的部分关键点。"));
        dims.set("experience_match", dimension("经历匹配度", match,
                "仅依据简历事实判断，未发现无依据的强主张。"));
        dims.set("structure", dimension("表达结构", structure,
                hasStructure ? "按背景/动作/机制/结果组织。" : "建议使用结构化顺序组织回答。"));

        ArrayNode strengths = root.putArray("strengths");
        if (!empty && hasDetail) {
            strengths.add("给出了具体实现手段");
        }
        if (hasResult) {
            strengths.add("提到了可验证的结果");
        }

        ArrayNode missing = root.putArray("missingPoints");
        if (empty) {
            missing.add("完全没有回答内容");
        } else {
            if (!hasDetail) {
                missing.add("缺少技术实现机制");
            }
            if (!hasResult) {
                missing.add("缺少量化结果或验证方式");
            }
            if (!hasStructure) {
                missing.add("缺少结构化的表达顺序");
            }
        }

        ArrayNode corrections = root.putArray("corrections");
        if (irrelevant) {
            corrections.add("回答与问题不匹配，需要先回答被问到的核心点");
        }
        if (technical <= 1 && !empty) {
            corrections.add("技术表述不准确，需要核实概念与边界");
        }

        ArrayNode additions = root.putArray("suggestedAdditions");
        additions.add("补充该技术方案的适用边界与替代方案对比");
        additions.add("补充个人在其中的具体职责与产出");

        root.put("referenceAnswerStructure", "背景（项目/场景）→ 个人职责 → 技术机制 → 难点与取舍 → 结果与验证");
        root.putArray("evidenceWarnings");
        root.put("followUpRecommended", !empty && (missing.size() > 0 || technical <= 3));
        root.put("followUpFocus", empty ? "先说明你的整体思路" : "深入追问技术机制与边界条件");
        root.put("overallComment", empty ? "请先作答再评估。" : "总体可用，需补充机制与结果。");
        return root;
    }

    private ObjectNode dimension(String label, int score, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("label", label);
        node.put("score", score);
        node.put("reason", reason);
        return node;
    }

    private ObjectNode question(LlmRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        // 优先用「【当前聚焦项目】」这一行（最可靠），再退回从上下文里抓项目名
        String focused = firstGroup(FOCUS_PROJECT_MARKER, prompt);
        String contextProject = firstNonBlank(firstGroup(PROJECT_MARKER, prompt), "");
        String project = firstNonBlank(sanitizeProject(focused), contextProject, "该项目");
        String knowledge = firstGroup(KNOWLEDGE_MARKER, prompt);
        boolean knowledgeMode = knowledge != null && !knowledge.isBlank();
        String sourceHint = prompt;

        ObjectNode root = objectMapper.createObjectNode();
        String fallbackType = knowledgeMode ? "PRINCIPLE" : "PROJECT_TECH";
        String detectedType = detectType(sourceHint, knowledgeMode, fallbackType);
        root.put("difficulty", detectDifficulty(sourceHint));
        if (knowledgeMode) {
            root.put("type", detectedType);
            String text = "请说明「" + shorten(knowledge, 40) + "」的实现原理、适用边界以及常见误区。";
            root.put("question", text);
            root.put("intent", "考察基础知识的原理理解与边界意识");
            root.putArray("focus").add("原理机制").add("设计取舍").add("边界条件");
            root.put("followUpPlan", "若只回答定义，继续追问边界条件与反例");
        } else {
            // 类型必须跟轮换到的模板一致：否则会出现「标着项目边界题、实际在问量化结果」
            String rotatedType = PROJECT_TEMPLATE_TYPES[askedCount(prompt) % PROJECT_TEMPLATE_TYPES.length];
            root.put("type", rotatedType);
            root.put("question", projectQuestion(project, askedCount(prompt)));
            root.put("intent", "考察项目事实、技术选型动机与取舍");
            root.putArray("focus").add("个人职责").add("设计取舍").add("边界条件");
            root.put("followUpPlan", "若只回答做了什么，追问技术机制与量化结果");
        }
        root.putArray("sourceIds");
        return root;
    }

    /**
     * 项目题模板按「已问过几题」轮换，类型表与 {@link #projectQuestion} 的分支一一对应。
     *
     * <p>模板引擎没有真实模型那种改写能力，如果固定一句话，一轮面试里会出现 4 道
     * 一模一样的问题，看起来像坏了。这里按题序换考察角度，让离线演示和 UI 点击
     * 走查能看到一条正常的面试推进曲线。
     */
    private static final String[] PROJECT_TEMPLATE_TYPES = {
            "PROJECT_BOUNDARY", "PROJECT_TRADEOFF", "PROJECT_RESULT",
            "PROJECT_TRADEOFF", "BEHAVIOR", "PROJECT_TRADEOFF"
    };

    private String projectQuestion(String project, int asked) {
        String quoted = "「" + shorten(project, 30) + "」";
        return switch (asked % PROJECT_TEMPLATE_TYPES.length) {
            case 0 -> "在你的" + quoted + "中，你具体负责了哪一部分？为什么选择当前的技术方案，"
                    + "它解决了什么问题，又带来了哪些新的限制？";
            case 1 -> quoted + "里最难的技术点是什么？你当时如何定位问题、如何验证解决效果？";
            case 2 -> "在" + quoted + "中，有哪些指标可以证明你的工作产生了效果？"
                    + "请给出具体数字，并说明这个数字是怎么测出来的。";
            case 3 -> "如果" + quoted + "的流量或数据量再涨十倍，现有的哪个设计会最先出问题？你会怎么改？";
            case 4 -> "在" + quoted + "中，你和团队其他成员的分工边界是什么？"
                    + "有没有出现过方案分歧，最后是怎么定的？";
            default -> "回头看" + quoted + "，如果让你重做一次，你会改掉哪一个技术决策？为什么？";
        };
    }

    /**
     * 统计提示词里「已经问过的问题」条数，用来决定下一个模板。
     *
     * <p>没有该段落说明是首题，返回 0。
     */
    private int askedCount(String prompt) {
        int start = prompt.indexOf(ASKED_HEADER);
        if (start < 0) {
            return 0;
        }
        int count = 0;
        for (String line : prompt.substring(start + ASKED_HEADER.length()).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                count++;
            } else if (!trimmed.isEmpty() && count > 0) {
                break;
            }
        }
        return count;
    }

    private ObjectNode followUp(LlmRequest request) {
        String focus = firstNonBlank(firstGroup(Pattern.compile("【待追问要点】\\s*(.*?)(?:\\n\\s*【|$)", Pattern.DOTALL), request.userPrompt()));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FOLLOW_UP");
        root.put("difficulty", "HARD");
        root.put("question", "刚才你提到的内容还停留在结论层面，请具体说明："
                + (focus.isBlank() ? "这个方案在并发或异常情况下会发生什么，你是如何验证的？" : shorten(focus, 60) + "，请给出具体实现与验证方式。"));
        root.put("intent", "验证回答深度与真实性");
        root.putArray("focus").add("实现细节").add("异常边界");
        root.put("followUpPlan", "不再追问，进入下一题");
        root.putArray("sourceIds");
        return root;
    }

    private ObjectNode report(LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", "本场面试覆盖了项目与基础知识两类问题，整体表达清晰，但技术机制与量化结果仍需补充。");
        root.putArray("projectRisks").add("项目成果缺少可验证的量化指标").add("技术选型动机需要准备替代方案对比");
        root.putArray("actionItems").add("重答得分最低的 2 道题，按「背景-动作-机制-结果」组织").add("复习薄弱知识点并准备 1 个反例");
        return root;
    }

    private ObjectNode generic(LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        for (String field : request.schemaFields()) {
            root.put(field, "mock-" + field);
        }
        return root;
    }

    // ---------------------------------------------------------------- 纯文本输出

    private String plainText(LlmRequest request) {
        String prompt = request.systemPrompt() == null ? "" : request.systemPrompt();
        if (prompt.contains("总结") || prompt.contains("复盘")) {
            return "本场面试已完成，整体表现稳定；建议补齐项目量化结果并复习薄弱知识点。";
        }
        return "已根据当前上下文生成内容。";
    }

    // ---------------------------------------------------------------- 工具方法

    private String detectType(String prompt, boolean knowledgeMode, String fallback) {
        String lower = prompt.toUpperCase(Locale.ROOT);
        for (String candidate : List.of("PROJECT_BOUNDARY", "PROJECT_TRADEOFF", "PROJECT_RESULT", "PROJECT_TECH",
                "PROJECT", "PRINCIPLE", "APPLICATION", "BOUNDARY", "COMPARISON", "CONCEPT", "BEHAVIOR")) {
            if (lower.contains(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private String detectDifficulty(String prompt) {
        String upper = prompt.toUpperCase(Locale.ROOT);
        if (upper.contains("HARD") || upper.contains("困难")) {
            return "HARD";
        }
        if (upper.contains("EASY") || upper.contains("简单")) {
            return "EASY";
        }
        return "MEDIUM";
    }

    /**
     * 取正则的第一个非空捕获组。
     *
     * <p>兼容「一个正则里两种写法」的场景：PROJECT_MARKER / KNOWLEDGE_MARKER 各有
     * 两个分支，命中哪个分支对应的组就取哪个，另一个为 null。
     */
    private String firstGroup(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /**
     * 项目名清洗：如果「【当前聚焦项目】」那一行后面还跟着「（请…）」这类提示语，
     * 把它去掉，避免提示语漏进问题正文里被用户看到。
     *
     * <p>{@code Prompts.questionUser} 已把约束拆到单独一行，这里只是兜底，
     * 保证即使提示词格式再变，模板出题也不会输出「星轨推荐引擎（请只针对…）」。
     */
    private String sanitizeProject(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        int hint = trimmed.indexOf("（请");
        if (hint > 0) {
            trimmed = trimmed.substring(0, hint).strip();
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int stableHash(String value) {
        int hash = 7;
        for (int i = 0; i < value.length(); i++) {
            hash = hash * 31 + value.charAt(i);
        }
        return Math.abs(hash);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String shorten(String value, int max) {
        String flattened = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }

    @Override
    public String modelName() {
        return "mock-model";
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public boolean available() {
        return true;
    }
}
