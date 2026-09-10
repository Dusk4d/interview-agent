package com.dusk4d.interview.agent;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.error.LlmException;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 面试报告生成（对应方案书 6.6）。
 *
 * <p>报告 = 确定性统计（{@link ReportAnalyzer}） + 模型定性表达（总结/风险/建议） + Markdown 渲染。
 * 模型失败时定性部分退化为基于统计的模板化建议，报告的分数与雷达图不受影响。
 */
@Component
public class ReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportBuilder.class);
    private static final List<String> SCHEMA_FIELDS = List.of("summary", "projectRisks", "actionItems");

    private final LlmClient llmClient;
    private final StructuredOutputParser parser;
    private final AppProperties properties;
    private final Clock clock;

    public ReportBuilder(LlmClient llmClient, StructuredOutputParser parser,
                         AppProperties properties, Clock clock) {
        this.llmClient = llmClient;
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 生成报告。
     *
     * @param session     会话
     * @param questions   问题列表
     * @param answers     回答列表
     * @param evaluations 评估列表
     */
    public InterviewReport build(InterviewSession session, List<InterviewQuestion> questions,
                                 List<InterviewAnswer> answers, List<AnswerEvaluation> evaluations) {
        ReportAnalyzer.Analysis analysis = ReportAnalyzer.analyze(questions, answers, evaluations);
        List<String> projectRisks = new ArrayList<>(ReportAnalyzer.projectRisks(questions, evaluations));
        List<String> actionItems = new ArrayList<>(analysis.deterministicActionItems());
        String summary = defaultSummary(session, analysis, evaluations);
        boolean degraded = false;

        if (!evaluations.isEmpty()) {
            String transcript = renderTranscript(questions, answers, evaluations);
            String userPrompt = Prompts.reportUser(session, transcript, analysis.weakTopicsSummary(),
                    analysis.overallScore(), analysis.abilitySummary());
            try {
                var response = llmClient.chat(LlmRequest.structured(Prompts.REPORT_SYSTEM, userPrompt,
                        "report", SCHEMA_FIELDS, properties.llm().temperature(), properties.llm().maxTokens()));
                Map<String, Object> parsed = parser.parseObject(response.content(), SCHEMA_FIELDS);
                summary = StructuredOutputParser.string(parsed, "summary", summary);
                List<String> modelRisks = StructuredOutputParser.stringList(parsed, "projectRisks");
                List<String> modelActions = StructuredOutputParser.stringList(parsed, "actionItems");
                if (!modelRisks.isEmpty()) {
                    projectRisks = modelRisks;
                }
                if (!modelActions.isEmpty()) {
                    actionItems = modelActions;
                }
            } catch (LlmException e) {
                degraded = true;
                log.warn("报告定性部分生成失败（{}），已使用统计化建议：{}", e.kind(), e.getMessage());
                summary = summary + "（说明：模型不可用，本报告的总结与建议由统计规则生成。）";
            }
        }

        Instant now = clock.instant();
        Instant startedAt = session.startedAt() == null ? session.createdAt() : session.startedAt();
        Instant endedAt = session.endedAt() == null ? now : session.endedAt();
        long duration = Math.max(0, Duration.between(startedAt, endedAt).getSeconds());

        InterviewReport report = new InterviewReport(
                UUID.randomUUID().toString(),
                session.id(),
                session.mode(),
                startedAt,
                endedAt,
                duration,
                questions.size(),
                answers.size(),
                analysis.overallScore(),
                analysis.abilityRadar(),
                analysis.weakestQuestions(),
                analysis.knowledgeGaps(),
                projectRisks,
                actionItems,
                summary,
                "",
                now);
        return report.withMarkdown(renderMarkdown(report, session, questions, answers, evaluations, degraded));
    }

    // ---------------------------------------------------------------- 渲染

    /** 生成问答记录（供报告提示词使用，控制长度避免上下文爆炸）。 */
    String renderTranscript(List<InterviewQuestion> questions, List<InterviewAnswer> answers,
                            List<AnswerEvaluation> evaluations) {
        Map<String, InterviewAnswer> answerByQuestion = new java.util.LinkedHashMap<>();
        answers.forEach(a -> answerByQuestion.put(a.questionId(), a));
        Map<String, AnswerEvaluation> evaluationByQuestion = new java.util.LinkedHashMap<>();
        evaluations.forEach(e -> evaluationByQuestion.put(e.questionId(), e));

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (InterviewQuestion question : questions) {
            InterviewAnswer answer = answerByQuestion.get(question.id());
            AnswerEvaluation evaluation = evaluationByQuestion.get(question.id());
            sb.append(index++).append(". [").append(question.type()).append("] ")
                    .append(shorten(question.content(), 120)).append('\n');
            sb.append("   回答：").append(answer == null || answer.empty()
                    ? "（未作答）" : shorten(answer.content(), 200)).append('\n');
            if (evaluation != null) {
                sb.append("   得分：").append(String.format(Locale.ROOT, "%.1f", evaluation.totalScore()))
                        .append("/5；遗漏：").append(evaluation.missingPoints().isEmpty()
                                ? "无" : shorten(String.join("；", evaluation.missingPoints()), 120))
                        .append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** 渲染 Markdown 报告（可直接下载/贴进笔记）。 */
    String renderMarkdown(InterviewReport report, InterviewSession session,
                          List<InterviewQuestion> questions, List<InterviewAnswer> answers,
                          List<AnswerEvaluation> evaluations, boolean degraded) {
        StringBuilder md = new StringBuilder();
        md.append("# 面试复盘报告\n\n");
        md.append("| 项目 | 内容 |\n|---|---|\n");
        md.append("| 面试模式 | ").append(modeLabel(report.mode())).append(" |\n");
        md.append("| 开始时间 | ").append(report.startedAt()).append(" |\n");
        md.append("| 结束时间 | ").append(report.endedAt()).append(" |\n");
        md.append("| 时长 | ").append(report.durationSeconds()).append(" 秒 |\n");
        md.append("| 题目数量 | ").append(report.questionCount()).append(" |\n");
        md.append("| 回答数量 | ").append(report.answerCount()).append(" |\n");
        md.append("| 平均得分 | ").append(String.format(Locale.ROOT, "%.2f", report.overallScore())).append(" / 5 |\n");
        if (degraded) {
            md.append("| 说明 | 模型不可用，总结与建议由统计规则生成 |\n");
        }
        md.append('\n');

        md.append("## 一、总体评价\n\n").append(report.summary()).append("\n\n");

        md.append("## 二、能力雷达\n\n");
        md.append("| 能力维度 | 得分（0-5） | 样本量 |\n|---|---|---|\n");
        for (var dimension : report.abilityRadar()) {
            md.append("| ").append(dimension.label()).append(" | ")
                    .append(dimension.sample() == 0 ? "暂无数据"
                            : String.format(Locale.ROOT, "%.2f", dimension.score()))
                    .append(" | ").append(dimension.sample()).append(" |\n");
        }
        md.append('\n');

        md.append("## 三、最需要重新回答的题目\n\n");
        if (report.weakestQuestions().isEmpty()) {
            md.append("本场没有可评估的题目。\n\n");
        } else {
            report.weakestQuestions().forEach(q -> md.append("- ").append(q).append('\n'));
            md.append('\n');
        }

        md.append("## 四、知识缺口\n\n");
        if (report.knowledgeGaps().isEmpty()) {
            md.append("未发现明显知识缺口。\n\n");
        } else {
            for (var gap : report.knowledgeGaps()) {
                md.append("- **").append(gap.topic()).append("**：").append(gap.reason());
                if (!gap.evidence().isEmpty()) {
                    md.append("（依据：").append(String.join("；", gap.evidence())).append("）");
                }
                md.append('\n');
            }
            md.append('\n');
        }

        md.append("## 五、项目追问风险\n\n");
        if (report.projectRisks().isEmpty()) {
            md.append("暂无识别的项目追问风险。\n\n");
        } else {
            report.projectRisks().forEach(risk -> md.append("- ").append(risk).append('\n'));
            md.append('\n');
        }

        md.append("## 六、下一步行动建议\n\n");
        report.actionItems().forEach(item -> md.append("- ").append(item).append('\n'));
        md.append('\n');

        md.append("## 七、逐题明细\n\n");
        Map<String, InterviewAnswer> answerByQuestion = new java.util.LinkedHashMap<>();
        answers.forEach(a -> answerByQuestion.put(a.questionId(), a));
        Map<String, AnswerEvaluation> evaluationByQuestion = new java.util.LinkedHashMap<>();
        evaluations.forEach(e -> evaluationByQuestion.put(e.questionId(), e));
        int index = 1;
        for (InterviewQuestion question : questions) {
            InterviewAnswer answer = answerByQuestion.get(question.id());
            AnswerEvaluation evaluation = evaluationByQuestion.get(question.id());
            md.append("### ").append(index++).append(". ").append(question.content()).append("\n\n");
            md.append("- 题型：").append(question.type()).append("；难度：").append(question.difficulty());
            if (!question.sourceIds().isEmpty()) {
                md.append("；来源：").append(String.join(", ", question.sourceIds()));
            }
            md.append('\n');
            md.append("- 我的回答：").append(answer == null || answer.empty()
                    ? "（未作答）" : answer.content().replaceAll("\\s+", " ")).append('\n');
            if (evaluation != null) {
                md.append("- 得分：").append(String.format(Locale.ROOT, "%.2f", evaluation.totalScore()))
                        .append(" / 5");
                if (evaluation.degraded()) {
                    md.append("（降级评估）");
                }
                md.append('\n');
                for (var dimension : evaluation.dimensionScores().values()) {
                    md.append("  - ").append(dimension.label()).append("：")
                            .append(String.format(Locale.ROOT, "%.1f", dimension.score()))
                            .append(" — ").append(dimension.reason()).append('\n');
                }
                if (!evaluation.missingPoints().isEmpty()) {
                    md.append("- 遗漏点：").append(String.join("；", evaluation.missingPoints())).append('\n');
                }
                if (!evaluation.corrections().isEmpty()) {
                    md.append("- 需要纠正：").append(String.join("；", evaluation.corrections())).append('\n');
                }
                if (!evaluation.suggestedAdditions().isEmpty()) {
                    md.append("- 建议补充：").append(String.join("；", evaluation.suggestedAdditions())).append('\n');
                }
                md.append("- 参考结构：").append(evaluation.referenceAnswerStructure()).append('\n');
            }
            md.append('\n');
        }
        md.append("---\n\n> 本报告由 AI 面试陪练系统生成，评分仅作为训练辅助，不作为客观考试成绩。\n");
        return md.toString();
    }

    private String defaultSummary(InterviewSession session, ReportAnalyzer.Analysis analysis,
                                  List<AnswerEvaluation> evaluations) {
        if (analysis.answeredCount() == 0) {
            return "本场面试没有可评估的回答，因此没有得分与薄弱点结论。建议先完整作答至少一道题，再生成复盘报告。";
        }
        String weakest = ReportAnalyzer.weakestDimension(evaluations);
        StringBuilder sb = new StringBuilder();
        sb.append("本场").append(modeLabel(session.mode())).append("共评估 ")
                .append(analysis.answeredCount()).append(" 道题，平均得分 ")
                .append(String.format(Locale.ROOT, "%.2f", analysis.overallScore())).append(" / 5。");
        if (!analysis.knowledgeGaps().isEmpty()) {
            sb.append("识别到 ").append(analysis.knowledgeGaps().size()).append(" 个需要补强的主题，优先复习：")
                    .append(analysis.knowledgeGaps().get(0).topic()).append("。");
        }
        if (weakest != null) {
            sb.append("表达层面最薄弱的是「").append(weakest).append("」。");
        }
        return sb.toString();
    }

    private String modeLabel(InterviewMode mode) {
        return Prompts.modeLabel(mode);
    }

    private String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }
}
