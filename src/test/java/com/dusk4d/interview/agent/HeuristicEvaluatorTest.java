package com.dusk4d.interview.agent;

import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.DimensionScore;
import com.dusk4d.interview.domain.InterviewAnswer;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.QuestionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("启发式评分与报告统计")
class HeuristicEvaluatorTest {

    @Test
    @DisplayName("空回答四个维度都是 0 分，且建议追问")
    void emptyAnswerScoresZero() {
        HeuristicEvaluator.HeuristicResult result = HeuristicEvaluator.evaluate("介绍一下项目", "");
        assertThat(result.dimensions().values()).allSatisfy(d -> assertThat(d.score()).isZero());
        assertThat(HeuristicEvaluator.totalScore(result.dimensions())).isZero();
        assertThat(result.missingPoints()).contains("完全没有回答内容");
        assertThat(result.followUpRecommended()).isFalse();
    }

    @Test
    @DisplayName("答非所问（自称不会）得分被压低")
    void evasiveAnswerScoresLow() {
        HeuristicEvaluator.HeuristicResult result = HeuristicEvaluator.evaluate("讲讲 Redis 分布式锁",
                "这个我不太清楚，没做过这方面的内容。");
        assertThat(HeuristicEvaluator.totalScore(result.dimensions())).isLessThan(1.5);
        assertThat(result.corrections()).anySatisfy(c -> assertThat(c).contains("未正面回应"));
    }

    @Test
    @DisplayName("结构完整且有机制与结果的回答得分更高（排序正确）")
    void detailedAnswerScoresHigher() {
        String detailed = """
                背景：订单中心需要保证同一用户重复提交只生效一次。
                我负责优惠券核销链路，首先用 Redis SET NX PX 加锁并配合唯一索引兜底。
                难点是锁超时与业务耗时不一致，所以实现了看门狗续期。
                结果压测下单成功率 99.5%，重复下单为 0。
                """;
        HeuristicEvaluator.HeuristicResult high = HeuristicEvaluator.evaluate("如何保证幂等", detailed);
        HeuristicEvaluator.HeuristicResult low = HeuristicEvaluator.evaluate("如何保证幂等", "用锁就行。");
        assertThat(HeuristicEvaluator.totalScore(high.dimensions()))
                .isGreaterThan(HeuristicEvaluator.totalScore(low.dimensions()));
        assertThat(high.strengths()).isNotEmpty();
        assertThat(high.dimensions().get("structure").score()).isGreaterThan(low.dimensions().get("structure").score());
    }

    @Test
    @DisplayName("降级评分有上限，不会给出假高分")
    void degradedScoreIsCapped() {
        String veryGood = "背景-职责-机制-难点-结果 全部齐全，使用 Redis 与 MySQL，压测提升 50%，上线后 QPS 3000。"
                + "首先说明设计动机，其次说明实现细节，最后给出验证方式与替代方案对比。";
        HeuristicEvaluator.HeuristicResult result = HeuristicEvaluator.evaluate("q", veryGood);
        assertThat(HeuristicEvaluator.totalScore(result.dimensions())).isLessThanOrEqualTo(3.5);
    }

    @Test
    @DisplayName("提到效果但没有数字时给出证据告警")
    void evidenceWarning() {
        HeuristicEvaluator.HeuristicResult result = HeuristicEvaluator.evaluate("q",
                "我做了缓存优化，性能提升很明显，用户体验变好了。");
        assertThat(result.evidenceWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("加权总分按权重计算并保留两位小数")
    void totalScoreIsWeighted() {
        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        dimensions.put("a", new DimensionScore("a", "A", 4, 0.5, "r"));
        dimensions.put("b", new DimensionScore("b", "B", 2, 0.5, "r"));
        assertThat(HeuristicEvaluator.totalScore(dimensions)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("报告统计：平均分、雷达维度、最弱题目、知识缺口")
    void reportAnalyzerAggregates() {
        List<InterviewQuestion> questions = List.of(
                question("q1", 1, QuestionType.PROJECT_TECH, "Redis 分布式锁", List.of("分布式锁")),
                question("q2", 2, QuestionType.PROJECT, "项目职责", List.of("职责")),
                question("q3", 3, QuestionType.PRINCIPLE, "MySQL 索引", List.of("索引")),
                question("q4", 4, QuestionType.CONCEPT, "JVM 内存", List.of("JVM")));
        List<InterviewAnswer> answers = List.of(
                answer("a1", "q1"), answer("a2", "q2"), answer("a3", "q3"), answer("a4", "q4"));
        List<AnswerEvaluation> evaluations = List.of(
                evaluation("q1", 4.5, Map.of("technical_correctness", 5.0, "completeness", 4.0), List.of(), List.of()),
                evaluation("q2", 2.0, Map.of("structure", 2.0), List.of("缺少量化结果"), List.of()),
                evaluation("q3", 1.0, Map.of("technical_correctness", 1.0), List.of("索引原理不清楚"),
                        List.of("把 B+ 树说成了二叉树")),
                evaluation("q4", 3.0, Map.of("technical_correctness", 3.0), List.of(), List.of()));

        ReportAnalyzer.Analysis analysis = ReportAnalyzer.analyze(questions, answers, evaluations);

        assertThat(analysis.answeredCount()).isEqualTo(4);
        assertThat(analysis.overallScore()).isEqualTo(2.63);
        assertThat(analysis.abilityRadar()).hasSizeGreaterThan(3);
        assertThat(analysis.abilityRadar().stream().filter(d -> d.label().equals("数据库")).findFirst())
                .get().satisfies(d -> {
                    assertThat(d.sample()).isEqualTo(1);
                    assertThat(d.score()).isEqualTo(1.0);
                });
        assertThat(analysis.abilityRadar().stream().filter(d -> d.label().equals("中间件")).findFirst())
                .get().satisfies(d -> {
                    assertThat(d.sample()).isEqualTo(1);
                    assertThat(d.score()).isEqualTo(4.5);
                });
        // 没有命中的维度样本量为 0，前端应显示「暂无数据」而不是 0 分
        assertThat(analysis.abilityRadar().stream().filter(d -> d.label().equals("网络与操作系统")).findFirst())
                .get().satisfies(d -> assertThat(d.sample()).isZero());
        assertThat(analysis.weakestQuestions()).hasSize(4);
        assertThat(analysis.weakestQuestions().get(0)).startsWith("[1.0 分]");
        assertThat(analysis.knowledgeGaps()).isNotEmpty();
        assertThat(analysis.deterministicActionItems()).anySatisfy(item -> assertThat(item).contains("重答题目"));

        List<String> risks = ReportAnalyzer.projectRisks(questions, evaluations);
        assertThat(risks).isNotEmpty();
        assertThat(ReportAnalyzer.weakestDimension(evaluations)).isNotBlank();
    }

    @Test
    @DisplayName("没有任何回答时统计不崩溃并给出引导性建议")
    void reportAnalyzerHandlesEmptySession() {
        ReportAnalyzer.Analysis analysis = ReportAnalyzer.analyze(List.of(), List.of(), List.of());
        assertThat(analysis.answeredCount()).isZero();
        assertThat(analysis.overallScore()).isZero();
        assertThat(analysis.abilityRadar()).allSatisfy(d -> assertThat(d.sample()).isZero());
        assertThat(analysis.deterministicActionItems()).anySatisfy(a -> assertThat(a).contains("先完成"));
    }

    private InterviewQuestion question(String id, int seq, QuestionType type, String content, List<String> focus) {
        return new InterviewQuestion(id, "session-1", seq, type, com.dusk4d.interview.domain.Difficulty.MEDIUM,
                content, "intent", focus, List.of(), List.of(), "plan", null, false, "SINGLE_QUESTION",
                Instant.now());
    }

    private InterviewAnswer answer(String id, String questionId) {
        return new InterviewAnswer(id, questionId, "session-1", "回答内容", 4, false, Instant.now());
    }

    private AnswerEvaluation evaluation(String questionId, double total, Map<String, Double> scores,
                                        List<String> missing, List<String> corrections) {
        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        scores.forEach((key, value) -> dimensions.put(key, new DimensionScore(key, key, value, 0.25, "reason")));
        if (dimensions.isEmpty()) {
            dimensions.put("technical_correctness",
                    new DimensionScore("technical_correctness", "技术正确性", total, 0.25, "reason"));
        }
        return new AnswerEvaluation("eval-" + questionId, "answer-" + questionId, questionId, "session-1",
                total, dimensions, List.of(), missing, corrections, List.of(), "结构", List.of(), false,
                "focus", "summary", false, null, Instant.now());
    }
}
