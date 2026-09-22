package com.dusk4d.interview.agent;

import com.dusk4d.interview.domain.AbilityDimension;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.DimensionScore;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.domain.WeakTopic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 报告统计与能力维度计算（确定性部分）。
 *
 * <p>把「可计算的」和「需要模型表达的」分开：
 * 分数、雷达维度、最薄弱题目、知识缺口都由这里按固定规则算出，可复现、可测试；
 * 模型只负责写总结、项目风险与行动建议（见 {@code ReportBuilder}）。
 * 这样即使模型返回胡话，报告的骨架与分数依然可信。
 */
public final class ReportAnalyzer {

    /**
     * 能力维度定义：标签 + 匹配规则。
     *
     * <p>带题型的规则只按题型匹配，不带题型的规则按主题关键词匹配，两类不混用——
     * 否则一个技术名词会同时计入多个维度，让雷达图虚高。
     */
    private static final List<AbilityRule> ABILITY_RULES = List.of(
            new AbilityRule("project_expression", "项目表达",
                    List.of(QuestionType.PROJECT, QuestionType.PROJECT_TECH, QuestionType.PROJECT_TRADEOFF,
                            QuestionType.PROJECT_BOUNDARY, QuestionType.PROJECT_RESULT),
                    List.of()),
            new AbilityRule("java_basic", "Java 基础", List.of(),
                    List.of("java", "jvm", "jmm", "集合", "hashmap", "并发", "线程", "线程池", "gc", "类加载", "string")),
            // 注意：这里刻意不放「锁」这类泛化词——否则 Redis 分布式锁会被同时计入数据库与中间件两个维度
            new AbilityRule("database", "数据库", List.of(),
                    List.of("mysql", "索引", "事务", "mvcc", "间隙锁", "sql", "postgresql", "数据库")),
            new AbilityRule("middleware", "中间件", List.of(), List.of("redis", "kafka", "rabbitmq", "rocketmq", "mq", "zookeeper", "缓存")),
            new AbilityRule("network_os", "网络与操作系统", List.of(), List.of("tcp", "http", "网络", "io", "epoll", "进程", "操作系统", "虚拟内存")),
            new AbilityRule("ai_application", "AI 应用", List.of(), List.of("rag", "embedding", "向量", "prompt", "agent", "大模型", "llm", "检索")),
            new AbilityRule("system_design", "系统设计", List.of(), List.of("架构", "微服务", "幂等", "分布式", "限流", "熔断", "设计模式"))
    );

    private record AbilityRule(String key, String label, List<QuestionType> types, List<String> keywords) {
    }

    private ReportAnalyzer() {
    }

    /** 分析结果。 */
    public record Analysis(
            double overallScore,
            List<AbilityDimension> abilityRadar,
            List<String> weakestQuestions,
            List<WeakTopic> knowledgeGaps,
            List<String> deterministicActionItems,
            int answeredCount
    ) {
        public Analysis {
            abilityRadar = abilityRadar == null ? List.of() : List.copyOf(abilityRadar);
            weakestQuestions = weakestQuestions == null ? List.of() : List.copyOf(weakestQuestions);
            knowledgeGaps = knowledgeGaps == null ? List.of() : List.copyOf(knowledgeGaps);
            deterministicActionItems = deterministicActionItems == null ? List.of() : List.copyOf(deterministicActionItems);
        }

        /** 供报告提示词使用的能力摘要。 */
        public List<String> abilitySummary() {
            List<String> lines = new ArrayList<>();
            for (AbilityDimension dimension : abilityRadar) {
                lines.add("- " + dimension.label() + "：" + (dimension.sample() == 0
                        ? "暂无数据" : String.format(Locale.ROOT, "%.2f/5（%d 题）", dimension.score(), dimension.sample())));
            }
            return lines;
        }

        /** 供报告提示词使用的薄弱点摘要。 */
        public String weakTopicsSummary() {
            if (knowledgeGaps.isEmpty()) {
                return "（暂无）";
            }
            StringBuilder sb = new StringBuilder();
            for (WeakTopic topic : knowledgeGaps) {
                sb.append("- ").append(topic.topic()).append("：").append(topic.reason()).append('\n');
            }
            return sb.toString().strip();
        }
    }

    /**
     * 计算报告统计。
     *
     * @param questions   本场全部问题
     * @param answers     本场全部回答
     * @param evaluations 本场全部评估
     */
    public static Analysis analyze(List<InterviewQuestion> questions, List<InterviewAnswer> answers,
                                   List<AnswerEvaluation> evaluations) {
        Map<String, InterviewAnswer> answerByQuestion = new LinkedHashMap<>();
        for (InterviewAnswer answer : answers) {
            answerByQuestion.put(answer.questionId(), answer);
        }
        Map<String, AnswerEvaluation> evaluationByQuestion = new LinkedHashMap<>();
        for (AnswerEvaluation evaluation : evaluations) {
            evaluationByQuestion.put(evaluation.questionId(), evaluation);
        }

        List<Double> allScores = new ArrayList<>();
        // 每题得分与文本，用于挑出最需要重答的题目
        List<QuestionScore> scored = new ArrayList<>();
        // 能力维度累计
        Map<String, double[]> abilityAccumulator = new LinkedHashMap<>();
        for (AbilityRule rule : ABILITY_RULES) {
            abilityAccumulator.put(rule.key(), new double[2]);
        }

        List<WeakTopic> gaps = new ArrayList<>();
        List<String> actionItems = new ArrayList<>();
        Set<String> gapTopics = new LinkedHashSet<>();

        for (InterviewQuestion question : questions) {
            AnswerEvaluation evaluation = evaluationByQuestion.get(question.id());
            if (evaluation == null) {
                continue;
            }
            double score = evaluation.totalScore();
            allScores.add(score);
            scored.add(new QuestionScore(question, evaluation));

            String haystack = (question.content() + " " + String.join(" ", question.focus())).toLowerCase(Locale.ROOT);
            for (AbilityRule rule : ABILITY_RULES) {
                if (matches(rule, question, haystack)) {
                    double[] accumulator = abilityAccumulator.get(rule.key());
                    accumulator[0] += score;
                    accumulator[1] += 1;
                }
            }

            // 知识缺口：分数偏低或存在证据/纠正提示
            if (score < 3.0 || !evaluation.corrections().isEmpty() || !evaluation.evidenceWarnings().isEmpty()) {
                String topic = topicOf(question);
                if (gapTopics.add(topic)) {
                    List<String> evidence = new ArrayList<>();
                    evidence.addAll(evaluation.corrections().stream().limit(2).toList());
                    evidence.addAll(evaluation.evidenceWarnings().stream().limit(2).toList());
                    String reason = score < 2.0 ? "回答明显不足（低于 2 分）"
                            : score < 3.0 ? "回答不完整（低于 3 分）"
                            : evaluation.evidenceWarnings().isEmpty() ? "存在技术表述需要纠正" : "存在没有证据的强主张";
                    gaps.add(new WeakTopic(topic, reason, evidence));
                }
            }

            if (score < 3.0 && question.type() != QuestionType.FOLLOW_UP) {
                actionItems.add("重答题目「" + shorten(question.content(), 40) + "」，按参考结构组织后再提交一次");
            }
        }

        double overall = allScores.isEmpty() ? 0
                : Math.round(allScores.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100.0) / 100.0;

        List<AbilityDimension> radar = new ArrayList<>();
        for (AbilityRule rule : ABILITY_RULES) {
            double[] accumulator = abilityAccumulator.get(rule.key());
            int sample = (int) accumulator[1];
            radar.add(new AbilityDimension(rule.key(), rule.label(),
                    sample == 0 ? 0 : Math.round(accumulator[0] / sample * 100.0) / 100.0, sample));
        }

        // 最需要重新回答的 3~5 题：按总分升序，排除追问
        List<QuestionScore> weakest = scored.stream()
                .filter(qs -> qs.question().type() != QuestionType.FOLLOW_UP)
                .sorted(java.util.Comparator.comparingDouble(QuestionScore::score))
                .limit(5)
                .toList();
        List<String> weakestQuestions = new ArrayList<>();
        for (QuestionScore questionScore : weakest) {
            weakestQuestions.add(String.format(Locale.ROOT, "[%.1f 分] %s", questionScore.score(),
                    shorten(questionScore.question().content(), 80)));
        }

        if (actionItems.isEmpty() && !scored.isEmpty()) {
            actionItems.add("挑一道最没把握的题，按「背景 → 职责 → 机制 → 难点 → 结果」重写一遍");
        }
        if (actionItems.isEmpty()) {
            actionItems.add("先完成至少一道题的作答，才能得到有针对性的复盘建议");
        }

        return new Analysis(overall, radar, weakestQuestions, gaps, actionItems.stream().distinct().limit(5).toList(),
                allScores.size());
    }

    /** 依据评估结果生成「面试官可能继续追问」的项目风险点。 */
    public static List<String> projectRisks(List<InterviewQuestion> questions, List<AnswerEvaluation> evaluations) {
        Map<String, InterviewQuestion> questionById = new LinkedHashMap<>();
        questions.forEach(q -> questionById.put(q.id(), q));
        List<String> risks = new ArrayList<>();
        for (AnswerEvaluation evaluation : evaluations) {
            InterviewQuestion question = questionById.get(evaluation.questionId());
            if (question == null) {
                continue;
            }
            boolean projectScoped = question.type() == QuestionType.PROJECT
                    || question.type() == QuestionType.PROJECT_TECH
                    || question.type() == QuestionType.PROJECT_TRADEOFF
                    || question.type() == QuestionType.PROJECT_BOUNDARY
                    || question.type() == QuestionType.PROJECT_RESULT;
            if (!projectScoped || evaluation.totalScore() >= 4.0) {
                continue;
            }
            if (!evaluation.followUpFocus().isBlank()) {
                risks.add(shorten(evaluation.followUpFocus(), 60));
            }
            for (String missing : evaluation.missingPoints()) {
                risks.add(shorten(missing, 60));
            }
        }
        if (risks.isEmpty()) {
            return List.of();
        }
        return risks.stream().distinct().limit(4).toList();
    }

    /** 找出得分最低的维度名称，用于行动建议。 */
    public static String weakestDimension(List<AnswerEvaluation> evaluations) {
        Map<String, double[]> accumulator = new LinkedHashMap<>();
        for (AnswerEvaluation evaluation : evaluations) {
            for (DimensionScore dimension : evaluation.dimensionScores().values()) {
                double[] value = accumulator.computeIfAbsent(dimension.label(), k -> new double[2]);
                value[0] += dimension.score();
                value[1] += 1;
            }
        }
        String weakest = null;
        double lowest = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> entry : accumulator.entrySet()) {
            if (entry.getValue()[1] <= 0) {
                continue;
            }
            double average = entry.getValue()[0] / entry.getValue()[1];
            if (average < lowest) {
                lowest = average;
                weakest = entry.getKey();
            }
        }
        return weakest;
    }

    private record QuestionScore(InterviewQuestion question, AnswerEvaluation evaluation) {
        double score() {
            return evaluation.totalScore();
        }
    }

    /**
     * 维度匹配规则。
     *
     * <p>带题型的规则只按题型匹配（项目题天然属于「项目表达」）；
     * 不带题型的规则按主题关键词匹配。两类不混用，避免「一个技术名词同时算进多个维度」导致雷达图虚高。
     */
    private static boolean matches(AbilityRule rule, InterviewQuestion question, String haystack) {
        if (!rule.types().isEmpty()) {
            return rule.types().contains(question.type());
        }
        for (String keyword : rule.keywords()) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String topicOf(InterviewQuestion question) {
        if (!question.focus().isEmpty()) {
            return shorten(question.focus().get(0), 24);
        }
        return shorten(question.content(), 24);
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "…";
    }
}
