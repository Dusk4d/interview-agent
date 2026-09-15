package com.dusk4d.interview.api;

import java.time.Instant;
import java.util.List;

/**
 * API 数据契约。
 *
 * <p>统一放在一个文件里，便于对照方案书第八节的接口草案，也避免 DTO 分散在多个小文件中难以检索。
 * 约定：
 * <ul>
 *   <li>所有时间使用 ISO-8601 字符串；</li>
 *   <li>响应中不包含手机号、邮箱等隐私字段（简历原文在入库前已脱敏）；</li>
 *   <li>错误响应固定为 {@link ErrorResponse} 结构，携带稳定错误码。</li>
 * </ul>
 */
public final class Dtos {

    private Dtos() {
    }

    // ---------------------------------------------------------------- 通用

    public record ErrorResponse(String code, String message, String path, Instant timestamp, List<String> details) {
        public ErrorResponse {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record OkResponse(boolean ok, String message) {
        public static OkResponse of(String message) {
            return new OkResponse(true, message);
        }
    }

    // ---------------------------------------------------------------- 简历

    /** 上传简历的响应（表单上传与粘贴文本共用）。 */
    public record ResumeImportResponse(
            String resumeId,
            String fileName,
            String fileType,
            String status,
            int factCount,
            double confidence,
            List<String> warnings,
            String preview
    ) {
    }

    /** 事实条目。 */
    public record FactView(String id, String type, String typeLabel, String label, String content,
                           int sourceOrder, double confidence, List<String> metadata) {
    }

    /** 简历详情。 */
    public record ResumeView(
            String id,
            String fileName,
            String fileType,
            long fileSize,
            String status,
            String errorMessage,
            List<String> warnings,
            List<FactView> facts,
            List<ProjectView> projects,
            List<String> techStack,
            String text,
            Instant createdAt
    ) {
    }

    public record ProjectView(String id, String name, String content, List<String> techStack, String period) {
    }

    public record FactUpdateRequest(List<FactInput> facts) {
    }

    public record FactInput(String id, String type, String label, String content) {
    }

    public record TextImportRequest(String text, String fileName) {
    }

    // ---------------------------------------------------------------- 面试会话

    public record CreateSessionRequest(String resumeId, String mode, Integer maxQuestions) {
    }

    public record SessionView(
            String id,
            String resumeId,
            String mode,
            String modeLabel,
            String stage,
            String status,
            String stateDescription,
            String currentQuestionId,
            int questionCount,
            int answerCount,
            int followUpCount,
            int maxQuestions,
            List<String> coveredTopics,
            List<String> recentFeedback,
            String endReason,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt
    ) {
    }

    /** 问题视图。 */
    public record QuestionView(
            String id,
            String sessionId,
            int sequence,
            String type,
            String difficulty,
            String content,
            String intent,
            List<String> focus,
            List<String> sourceIds,
            List<String> citations,
            String followUpPlan,
            String parentQuestionId,
            boolean followUp,
            String stage,
            boolean degraded,
            String degradationReason
    ) {
    }

    public record AnswerRequest(String questionId, String content) {
    }

    /** 评分维度视图。 */
    public record DimensionView(String key, String label, double score, double weight, String reason) {
    }

    public record EvaluationView(
            String id,
            String answerId,
            String questionId,
            double totalScore,
            List<DimensionView> dimensions,
            List<String> strengths,
            List<String> missingPoints,
            List<String> corrections,
            List<String> suggestedAdditions,
            String referenceAnswerStructure,
            List<String> evidenceWarnings,
            boolean followUpRecommended,
            String followUpFocus,
            String summary,
            boolean degraded,
            Instant createdAt
    ) {
    }

    public record AnswerResultView(
            String answerId,
            String questionId,
            EvaluationView evaluation,
            SessionView session,
            String nextAction,
            String nextActionHint
    ) {
    }

    public record EvaluationResponse(EvaluationView evaluation, SessionView session, String nextAction,
                                     String nextActionHint) {
    }

    public record FinishRequest(String reason) {
    }

    // ---------------------------------------------------------------- 报告

    public record AbilityView(String key, String label, double score, int sample) {
    }

    public record WeakTopicView(String topic, String reason, List<String> evidence) {
    }

    public record ReportView(
            String id,
            String sessionId,
            String mode,
            String modeLabel,
            Instant startedAt,
            Instant endedAt,
            long durationSeconds,
            int questionCount,
            int answerCount,
            double overallScore,
            List<AbilityView> abilityRadar,
            List<String> weakestQuestions,
            List<WeakTopicView> knowledgeGaps,
            List<String> projectRisks,
            List<String> actionItems,
            String summary
    ) {
    }

    public record ReportResponse(ReportView report, String markdown) {
    }

    // ---------------------------------------------------------------- 系统状态

    public record HealthView(
            String status,
            String version,
            String llmProvider,
            String llmModel,
            boolean llmAvailable,
            /** 实际生效的模型服务地址（用于排查配置未生效的问题）。 */
            String llmBaseUrl,
            /** 推理型模型是否已自动关闭思考链。 */
            boolean thinkingDisabled,
            String embeddingProvider,
            int embeddingDimension,
            int knownChunks,
            int knowledgeItems,
            int resumeCount,
            int sessionCount,
            String storageMode,
            List<String> topics
    ) {
    }
}
