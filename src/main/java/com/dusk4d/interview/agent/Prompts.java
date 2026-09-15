package com.dusk4d.interview.agent;

import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.InterviewStage;
import com.dusk4d.interview.domain.ResumeFact;

import java.util.List;
import java.util.StringJoiner;

/**
 * 提示词模板集中管理。
 *
 * <p>把提示词放在一个文件里，是为了让「事实边界」和「不得编造」这类约束可以被评审、被测试、
 * 也便于后续替换为更严格或更宽松的版本。所有模板都遵循三条硬规则：
 * <ol>
 *   <li>只能围绕给定的检索片段提问，不得虚构简历中没有的经历；</li>
 *   <li>不得替用户编造指标、职责范围与上线结果；</li>
 *   <li>输出必须是约定字段的 JSON，不允许附加解释文本。</li>
 * </ol>
 */
public final class Prompts {

    /** 评分维度定义（对应方案书 6.5，四项等权）。 */
    public static final List<String> DIMENSION_KEYS = List.of(
            "technical_correctness", "completeness", "experience_match", "structure");

    private Prompts() {
    }

    public static final String QUESTION_SYSTEM = """
            你是一位严谨的技术面试官，正在为候选人做面试训练。你的任务是根据给定的事实片段提出一道问题。

            硬性要求：
            1. 只能围绕【检索到的简历片段】或【知识点】提问，禁止提及片段中没有出现的项目、技术或经历。
            2. 如果片段不足以支撑一个具体问题，就基于片段中确实出现的内容做保守提问，不要补充假设细节。
            3. 问题必须可被候选人在 1-3 分钟内用文字回答，不要一次问多个互不相关的问题。
            4. 只输出 JSON，不要输出任何解释、前后缀或 markdown 代码块。
            """;

    /**
     * 构造出题提示。
     *
     * @param mode       面试模式
     * @param stage      当前阶段
     * @param difficulty 目标难度
     * @param context    检索到的上下文（带来源编号）
     * @param askedQuestions 已问过的问题（用于去重）
     * @param project    当前聚焦的项目名（可为 null）
     */
    public static String questionUser(InterviewMode mode, InterviewStage stage, Difficulty difficulty,
                                      String context, List<String> askedQuestions, String project) {
        StringBuilder sb = new StringBuilder();
        sb.append("【面试模式】").append(modeLabel(mode)).append('\n');
        sb.append("【当前阶段】").append(stageLabel(stage)).append('\n');
        sb.append("【目标难度】").append(difficulty.name()).append('\n');
        if (project != null && !project.isBlank()) {
            sb.append("【当前聚焦项目】").append(project).append('\n');
        }
        sb.append('\n').append("【事实片段（唯一事实来源）】\n").append(context).append('\n');
        if (askedQuestions != null && !askedQuestions.isEmpty()) {
            sb.append("\n【已经问过的问题，必须避免重复】\n");
            askedQuestions.forEach(q -> sb.append("- ").append(q).append('\n'));
        }
        sb.append("""

                【本次要求的追问方向】
                """).append(directionHint(mode, stage)).append("""

                【输出 JSON 字段】
                type: 题型，取值范围 [%s]
                difficulty: 难度，取值范围 [EASY, MEDIUM, HARD]
                question: 问题正文（中文，一句话到两句话）
                intent: 考察意图（一句话）
                focus: 考察点数组（2-3 项）
                followUpPlan: 如果候选人只回答了表层内容，下一步追问什么
                """.formatted(questionTypes(mode)));
        return sb.toString();
    }

    public static final String EVALUATION_SYSTEM = """
            你是一位严格但建设性的面试教练，正在评估候选人对某道面试题的文字回答。

            评分维度（每项 0-5 分，必须给出打分依据）：
            - technical_correctness（技术正确性）：概念、机制、因果关系是否正确。
            - completeness（内容完整性）：是否覆盖问题要求的关键点。
            - experience_match（经历匹配度）：是否与简历事实、个人职责一致；不得把团队成果算作个人成果。
            - structure（表达结构）：是否按背景、动作、机制、结果组织。

            【统一分档标准】必须严格按同一把尺子打分，不要凭整体印象给分：
            5 分 = 完全正确且覆盖关键点，机制与边界都讲清楚，无可补充；
            4 分 = 方向与机制正确，覆盖主要关键点，仅缺次要细节或量化结果；
            3 分 = 基本正确但明显不完整，只讲到结论、缺少机制或边界；
            2 分 = 部分正确，存在概念混淆或关键前提缺失；
            1 分 = 基本不正确，只有零散关键词，或答非所问；
            0 分 = 未作答，或内容与问题完全无关。

            打分步骤（请按顺序执行，以保证同一回答每次得到相近分数）：
            1. 先列出「问题要求回答的关键点」，再逐一检查回答是否覆盖；
            2. 每个维度先判断落在上面哪一档，再写该档对应的分数，不要凭感觉取整；
            3. 同一份回答重复评分必须给出相同的档位判断；只有确实模棱两可时才允许相邻档位。

            硬性要求：
            1. 没有证据的指标（百分比、QPS、耗时等）不得替候选人补写；如果回答里没有，就写进 missingPoints。
            2. 不得虚构候选人的经历。回复中只能引用给定的事实片段。
            3. 只能输出 JSON，字段必须齐全，不要输出解释或代码块。
            4. 分数要与依据一致：如果回答为空或答非所问，四个维度都应为 0-1 分。
            """;

    /**
     * 构造评估提示。
     *
     * @param question    当前问题
     * @param answer      用户回答（原文，未脱敏处理由上层负责）
     * @param context     检索到的事实片段（用于事实一致性校验）
     * @param resumeFacts 简历事实摘要（用于经历匹配度）
     */
    public static String evaluationUser(InterviewQuestion question, String answer, String context,
                                        List<ResumeFact> resumeFacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前问题】\n").append(question.content()).append('\n');
        sb.append("\n【本题考察点】").append(String.join("、", question.focus())).append('\n');
        sb.append("\n【用户回答】\n").append(answer == null || answer.isBlank() ? "（用户没有作答）" : answer).append('\n');
        if (context != null && !context.isBlank()) {
            sb.append("\n【事实片段（唯一事实来源）】\n").append(context).append('\n');
        }
        if (resumeFacts != null && !resumeFacts.isEmpty()) {
            sb.append("\n【简历事实摘要】\n");
            for (ResumeFact fact : resumeFacts) {
                sb.append("- [").append(fact.type()).append("] ").append(fact.label()).append("：")
                        .append(shorten(fact.content(), 160)).append('\n');
            }
        }
        sb.append("""

                【输出 JSON 字段】必须严格按下面的结构返回（字段名不可改动）：
                {
                  "dimensionScores": {
                    "technical_correctness": { "score": 0-5 的数字, "reason": "打分依据" },
                    "completeness":          { "score": 0-5 的数字, "reason": "打分依据" },
                    "experience_match":      { "score": 0-5 的数字, "reason": "打分依据" },
                    "structure":             { "score": 0-5 的数字, "reason": "打分依据" }
                  },
                  "strengths": ["可保留的内容"],
                  "missingPoints": ["遗漏的关键点"],
                  "corrections": ["需要纠正的表述"],
                  "suggestedAdditions": ["建议补充的信息"],
                  "referenceAnswerStructure": "参考回答的结构",
                  "evidenceWarnings": ["缺少证据的强主张"],
                  "followUpRecommended": true 或 false,
                  "followUpFocus": "若要追问，追问什么",
                  "summary": "两句话以内的总评"
                }

                注意：
                - dimensionScores 必须是「对象」而不是数组，四个键一个都不能少；
                - score 使用 0-5 分制（不要用 10 分制或百分制）；
                - 每个维度的 reason 必须写清楚打分依据，不能只给分数。
                """);
        return sb.toString();
    }

    public static final String FOLLOW_UP_SYSTEM = """
            你是一位技术面试官，正在对候选人的上一次回答做一次深入追问。

            硬性要求：
            1. 追问必须针对上一题的遗漏点或含糊表述，不要另起一个新话题。
            2. 不能引入简历中不存在的项目或技术。
            3. 只输出 JSON，不要解释文本。
            """;

    public static String followUpUser(InterviewQuestion parent, String answer, String missingPoints,
                                      String context, String followUpFocus) {
        return new StringBuilder()
                .append("【上一题】\n").append(parent.content()).append('\n')
                .append("\n【候选人的回答】\n").append(shorten(answer, 800)).append('\n')
                .append("\n【待追问要点】\n").append(followUpFocus == null || followUpFocus.isBlank()
                        ? missingPoints : followUpFocus).append('\n')
                .append("\n【遗漏点】\n").append(missingPoints == null || missingPoints.isBlank()
                        ? "（未识别到明确遗漏点，请追问实现细节与边界）" : missingPoints).append('\n')
                .append("\n【事实片段（唯一事实来源）】\n").append(context == null ? "" : context).append('\n')
                .append("""

                        【输出 JSON 字段】
                        type: 固定为 FOLLOW_UP
                        difficulty: 难度，取值范围 [EASY, MEDIUM, HARD]
                        question: 追问正文（一句话）
                        intent: 追问意图
                        focus: 考察点数组（1-2 项）
                        followUpPlan: 固定填写「不再追问，进入下一题」
                        """)
                .toString();
    }

    public static final String REPORT_SYSTEM = """
            你是一位面试复盘教练。请根据整场面试的记录，输出复盘中的定性部分。

            硬性要求：
            1. 建议必须具体可执行，不要写「多加练习」这类空话。
            2. 项目风险要指出面试官可能继续追问的技术点，且只能来自简历事实。
            3. 不要编造数据或经历。
            4. 只输出 JSON。
            """;

    public static String reportUser(InterviewSession session, String transcript, String weakTopics,
                                    double overallScore, List<String> abilitySummary) {
        return new StringBuilder()
                .append("【面试模式】").append(modeLabel(session.mode())).append('\n')
                .append("【题目数量】").append(session.questionCount()).append('\n')
                .append("【平均得分】").append(String.format(java.util.Locale.ROOT, "%.2f", overallScore)).append(" / 5\n")
                .append("\n【能力维度表现】\n").append(String.join("\n", abilitySummary)).append('\n')
                .append("\n【薄弱知识点】\n").append(weakTopics == null || weakTopics.isBlank() ? "（暂无）" : weakTopics).append('\n')
                .append("\n【问答记录】\n").append(transcript).append('\n')
                .append("""

                        【输出 JSON 字段】
                        summary: 字符串，整场表现总结（3 句以内）
                        projectRisks: 字符串数组，面试官可能继续追问的项目风险点（2-4 条）
                        actionItems: 字符串数组，下一次练习的具体行动建议（2-4 条）
                        """)
                .toString();
    }

    // ---------------------------------------------------------------- 内部工具

    private static String directionHint(InterviewMode mode, InterviewStage stage) {
        if (mode == InterviewMode.PROJECT) {
            return """
                    - 按「事实确认 → 技术机制 → 设计取舍 → 异常边界 → 验证结果」逐步深入；
                    - 必须结合候选人的个人职责提问，不要问与个人无关的团队层面问题。""";
        }
        if (mode == InterviewMode.KNOWLEDGE) {
            return """
                    - 按「概念 → 原理 → 应用 → 边界 → 对比」逐步深入；
                    - 不要假装这些技术是候选人项目里用过的。""";
        }
        return switch (stage) {
            case SELF_INTRO -> "- 让候选人做 1 分钟自我介绍，重点看结构（背景、方向、亮点、匹配度）。";
            case PROJECT -> "- 让候选人讲清一个项目的背景、个人职责与技术栈。";
            case PROJECT_DEEP_DIVE -> "- 就项目中的技术机制、取舍或异常边界深入追问。";
            case FUNDAMENTALS -> "- 考察与项目相关的基础知识，按「概念 → 原理 → 边界」提问。";
            case CANDIDATE_QUESTIONS -> "- 请候选人提出他/她想反问面试官的问题，并说明关注点。";
            default -> "- 综合性问题，覆盖项目与基础知识的结合点。";
        };
    }

    private static String questionTypes(InterviewMode mode) {
        return switch (mode) {
            case PROJECT -> "PROJECT, PROJECT_TECH, PROJECT_TRADEOFF, PROJECT_BOUNDARY, PROJECT_RESULT";
            case KNOWLEDGE -> "CONCEPT, PRINCIPLE, APPLICATION, BOUNDARY, COMPARISON";
            case FULL -> "BEHAVIOR, PROJECT, PROJECT_TECH, PROJECT_TRADEOFF, PROJECT_BOUNDARY, "
                    + "PROJECT_RESULT, CONCEPT, PRINCIPLE, APPLICATION, BOUNDARY, COMPARISON";
        };
    }

    public static String modeLabel(InterviewMode mode) {
        return switch (mode) {
            case PROJECT -> "项目面（围绕简历项目）";
            case KNOWLEDGE -> "八股面（计算机基础知识）";
            case FULL -> "完整文本模拟面试";
        };
    }

    public static String stageLabel(InterviewStage stage) {
        return switch (stage) {
            case SELF_INTRO -> "开场自我介绍";
            case PROJECT -> "项目经历陈述";
            case PROJECT_DEEP_DIVE -> "项目深挖追问";
            case FUNDAMENTALS -> "计算机基础知识";
            case CANDIDATE_QUESTIONS -> "反问环节";
            case WRAP_UP -> "总结复盘";
            case SINGLE_QUESTION -> "单题练习";
        };
    }

    /** 拼接历史问题摘要，用于去重提示。 */
    public static String digest(List<InterviewQuestion> questions, int maxChars) {
        if (questions == null || questions.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (InterviewQuestion question : questions) {
            joiner.add("- " + shorten(question.content(), maxChars));
        }
        return joiner.toString();
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }
}
