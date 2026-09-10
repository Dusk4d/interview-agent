package com.dusk4d.interview.api;

import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.InterviewReport;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.error.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 领域对象 → API 视图的转换。 */
public final class ApiMapper {

    private ApiMapper() {
    }

    public static Dtos.SessionView toView(InterviewSession session, String stateDescription) {
        return new Dtos.SessionView(
                session.id(),
                session.resumeId(),
                session.mode().name(),
                modeLabel(session.mode().name()),
                session.stage().name(),
                session.status().name(),
                stateDescription,
                session.currentQuestionId(),
                session.questionCount(),
                session.answerCount(),
                session.followUpCount(),
                session.maxQuestions(),
                session.coveredTopics(),
                session.recentFeedback(),
                session.endReason(),
                session.createdAt(),
                session.startedAt(),
                session.endedAt());
    }

    public static Dtos.QuestionView toView(InterviewQuestion question, boolean degraded, String degradationReason) {
        return new Dtos.QuestionView(
                question.id(),
                question.sessionId(),
                question.sequence(),
                question.type().name(),
                question.difficulty().name(),
                question.content(),
                question.intent(),
                question.focus(),
                question.sourceIds(),
                question.sourceSnippets(),
                question.followUpPlan(),
                question.parentQuestionId(),
                question.followUp(),
                question.stage(),
                degraded,
                degradationReason);
    }

    public static Dtos.EvaluationView toView(AnswerEvaluation evaluation) {
        List<Dtos.DimensionView> dimensions = new ArrayList<>();
        evaluation.dimensionScores().values().forEach(d -> dimensions.add(
                new Dtos.DimensionView(d.key(), d.label(), d.score(), d.weight(), d.reason())));
        return new Dtos.EvaluationView(
                evaluation.id(),
                evaluation.answerId(),
                evaluation.questionId(),
                evaluation.totalScore(),
                dimensions,
                evaluation.strengths(),
                evaluation.missingPoints(),
                evaluation.corrections(),
                evaluation.suggestedAdditions(),
                evaluation.referenceAnswerStructure(),
                evaluation.evidenceWarnings(),
                evaluation.followUpRecommended(),
                evaluation.followUpFocus(),
                evaluation.summary(),
                evaluation.degraded(),
                evaluation.createdAt());
    }

    public static Dtos.ResumeImportResponse toImportView(com.dusk4d.interview.service.ResumeImportService.ImportResult result) {
        Resume resume = result.resume();
        return new Dtos.ResumeImportResponse(
                resume.id(),
                resume.fileName(),
                resume.fileType(),
                resume.status().name(),
                resume.facts().size(),
                Math.round(result.confidence() * 100) / 100.0,
                result.warnings(),
                preview(resume.maskedText()));
    }

    public static Dtos.ResumeView toView(Resume resume) {
        List<Dtos.FactView> facts = resume.facts().stream().map(ApiMapper::toView).toList();
        List<Dtos.ProjectView> projects = resume.factsOf(FactType.PROJECT).stream()
                .map(fact -> new Dtos.ProjectView(fact.id(), fact.label(), fact.content(),
                        techStackOf(fact), periodOf(fact)))
                .toList();
        List<String> techStack = resume.factsOf(FactType.SKILL).stream()
                .filter(f -> f.label() != null && f.label().contains("总览"))
                .findFirst()
                .map(ResumeFact::metadata)
                .orElseGet(() -> resume.facts().stream()
                        .flatMap(f -> f.metadata().stream())
                        .filter(m -> !m.startsWith("时间："))
                        .distinct()
                        .limit(30)
                        .toList());
        return new Dtos.ResumeView(
                resume.id(),
                resume.fileName(),
                resume.fileType(),
                resume.fileSize(),
                resume.status().name(),
                resume.errorMessage(),
                resume.warnings(),
                facts,
                projects,
                techStack,
                resume.maskedText(),
                resume.createdAt());
    }

    public static Dtos.FactView toView(ResumeFact fact) {
        return new Dtos.FactView(fact.id(), fact.type().name(), typeLabel(fact.type()), fact.label(),
                fact.content(), fact.sourceOrder(), Math.round(fact.confidence() * 100) / 100.0, fact.metadata());
    }

    public static Dtos.ReportView toView(InterviewReport report) {
        return new Dtos.ReportView(
                report.id(),
                report.sessionId(),
                report.mode().name(),
                modeLabel(report.mode().name()),
                report.startedAt(),
                report.endedAt(),
                report.durationSeconds(),
                report.questionCount(),
                report.answerCount(),
                report.overallScore(),
                report.abilityRadar().stream()
                        .map(d -> new Dtos.AbilityView(d.key(), d.label(), d.score(), d.sample()))
                        .toList(),
                report.weakestQuestions(),
                report.knowledgeGaps().stream()
                        .map(g -> new Dtos.WeakTopicView(g.topic(), g.reason(), g.evidence()))
                        .toList(),
                report.projectRisks(),
                report.actionItems(),
                report.summary());
    }

    /** 回答视图（仅用于逐题明细）。 */
    public static Dtos.AnswerRequest toRequest(InterviewAnswer answer) {
        return new Dtos.AnswerRequest(answer.questionId(), answer.content());
    }

    // ---------------------------------------------------------------- 工具

    public static String typeLabel(FactType type) {
        return switch (type) {
            case EDUCATION -> "教育经历";
            case INTERNSHIP -> "实习/工作经历";
            case PROJECT -> "项目经历";
            case SKILL -> "技能";
            case AWARD -> "奖项";
            case SUMMARY -> "个人概况";
            case KNOWLEDGE -> "知识点";
            case OTHER -> "其它";
        };
    }

    public static String modeLabel(String mode) {
        if (mode == null) {
            return "";
        }
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "PROJECT" -> "项目面";
            case "KNOWLEDGE" -> "八股面";
            case "FULL" -> "完整模拟面试";
            default -> mode;
        };
    }

    /** 解析模式字符串，非法值给出明确提示。 */
    public static com.dusk4d.interview.domain.InterviewMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new ValidationException("面试模式不能为空，可选 PROJECT / KNOWLEDGE / FULL。");
        }
        try {
            return com.dusk4d.interview.domain.InterviewMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("未知的面试模式：" + mode + "，可选 PROJECT / KNOWLEDGE / FULL。");
        }
    }

    public static FactType parseFactType(String type) {
        if (type == null || type.isBlank()) {
            throw new ValidationException("事实类型不能为空。");
        }
        try {
            return FactType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("未知的事实类型：" + type);
        }
    }

    private static List<String> techStackOf(ResumeFact fact) {
        return fact.metadata().stream().filter(m -> !m.startsWith("时间：")).toList();
    }

    private static String periodOf(ResumeFact fact) {
        return fact.metadata().stream().filter(m -> m.startsWith("时间：")).findFirst().orElse(null);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").strip();
        return flattened.length() <= 400 ? flattened : flattened.substring(0, 400) + "…";
    }
}
