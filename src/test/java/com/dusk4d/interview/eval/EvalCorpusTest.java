package com.dusk4d.interview.eval;

import com.dusk4d.interview.agent.AnswerEvaluator;
import com.dusk4d.interview.agent.StructuredOutputParser;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.InterviewQuestion;
import com.dusk4d.interview.domain.QuestionType;
import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.LlmRequest;
import com.dusk4d.interview.llm.LlmResponse;
import com.dusk4d.interview.parse.ResumeFactExtractor;
import com.dusk4d.interview.parse.TextCleaner;
import com.dusk4d.interview.privacy.PrivacyMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线评测集回归（对应方案书第十一节建议的测试集）。
 *
 * <p>把「人工确认的基准」变成可批量断言，回答两个问题：
 * <ol>
 *   <li><b>解析召回</b>：4 份不同结构的简历，必须抽到人工确认过的项目/公司/学校名、
 *       区块类型与技术栈关键词，且不得编造简历里没有的内容；</li>
 *   <li><b>评分排序</b>：同一道题的三档人工回答（优秀 / 要点不全 / 答非所问），
 *       分数必须单调递减。这比测「绝对分数准不准」更有意义——
 *       绝对分数受模型主观性影响，而排序正确是评分可用性的底线。</li>
 * </ol>
 *
 * <p>评测集规模：当前 4 份简历（方案书建议 10 份，属于待补项，见 docs/VERIFICATION.md）。
 * 加简历只需往 fixtures 放文件并在 {@link EvalCorpus} 登记一条样本。
 */
@DisplayName("离线评测集 EvalCorpus")
class EvalCorpusTest {

    private final TextCleaner cleaner = new TextCleaner();
    private final PrivacyMasker masker = new PrivacyMasker();
    private final ResumeFactExtractor extractor = new ResumeFactExtractor(cleaner, masker);

    @Test
    @DisplayName("解析召回：4 份简历的事实、类型与技术栈都不丢字段且不编造")
    void parsingRecall() {
        List<String> failures = new ArrayList<>();
        for (EvalCorpus.Sample sample : EvalCorpus.samples()) {
            EvalCorpus.ParsedSample parsed = EvalCorpus.parse(sample, cleaner, extractor, masker);

            if (!parsed.coversLabels()) {
                failures.add(sample.fixture() + " 缺少人工确认的事实标签：" + sample.expectedFactLabels());
            }
            if (!parsed.coversTypes()) {
                failures.add(sample.fixture() + " 缺少人工确认的区块类型：" + sample.expectedFactTypes());
            }
            if (!parsed.coversTechStack()) {
                failures.add(sample.fixture() + " 缺少人工确认的技术栈：" + sample.techStack());
            }
            if (!parsed.isMasked()) {
                failures.add(sample.fixture() + " 存在未脱敏的联系方式");
            }
            assertThat(parsed.resume().usable())
                    .as("%s 应产生可用简历", sample.fixture())
                    .isTrue();
        }
        assertThat(failures).as("解析召回未达标项").isEmpty();
    }

    @Test
    @DisplayName("不编造：极简简历不得抽出项目，也不得出现简历里没有的技术")
    void minimalResumeDoesNotFabricate() {
        EvalCorpus.Sample minimal = EvalCorpus.samples().stream()
                .filter(sample -> sample.fixture().equals("resume-minimal.txt"))
                .findFirst()
                .orElseThrow();
        EvalCorpus.ParsedSample parsed = EvalCorpus.parse(minimal, cleaner, extractor, masker);

        assertThat(parsed.resume().factsOf(com.dusk4d.interview.domain.FactType.PROJECT))
                .as("极简简历没有项目经历，不应编造项目")
                .isEmpty();
        String all = parsed.resume().facts().stream()
                .map(fact -> fact.content() + " " + fact.label())
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).doesNotContain("Redis").doesNotContain("Spring Boot").doesNotContain("分布式");
        // 抽取出的技术栈也不能凭空出现
        assertThat(parsed.raw().techStack()).allSatisfy(term -> assertThat(all).contains(term));
    }

    @Test
    @DisplayName("评分排序：优秀 > 部分正确 > 答非所问（使用确定性判分替身）")
    void scoringOrderIsMonotonic() {
        // 用确定性替身而不是真实模型：这里验证的是「评分链路的排序能力」，
        // 必须离线可跑且不受模型波动影响。真实模型的排序与方差由 LiveEvalMain 单独测量。
        AnswerEvaluator evaluator = deterministicEvaluator();
        InterviewQuestion question = question();

        for (EvalCorpus.Sample sample : EvalCorpus.samples()) {
            if (sample.answerTiers().size() < 3) {
                continue;
            }
            List<Double> scores = new ArrayList<>();
            for (EvalCorpus.AnswerTier tier : sample.answerTiers()) {
                AnswerEvaluation evaluation = evaluator.evaluate(question, tier.answer(),
                        "简历片段：" + sample.displayName(), List.of());
                scores.add(evaluation.totalScore());
                assertThat(evaluation.totalScore())
                        .as("%s / %s 的分数应落在 %d~%d", sample.fixture(), tier.label(),
                                tier.minScore(), tier.maxScore())
                        .isBetween((double) tier.minScore(), (double) tier.maxScore());
                assertThat(evaluation.dimensionScores()).hasSize(4);
            }
            assertThat(scores.get(0))
                    .as("%s：优秀回答必须高于要点不全回答（实际 %s）", sample.fixture(), scores)
                    .isGreaterThan(scores.get(1));
            assertThat(scores.get(1))
                    .as("%s：要点不全回答必须高于答非所问回答（实际 %s）", sample.fixture(), scores)
                    .isGreaterThan(scores.get(2));
        }
    }

    @Test
    @DisplayName("评测集本身必须自洽：样本非空、三档回答齐备、字段无重复")
    void corpusIsWellFormed() {
        List<EvalCorpus.Sample> samples = EvalCorpus.samples();
        assertThat(samples).hasSizeGreaterThanOrEqualTo(4);
        assertThat(samples.stream().map(EvalCorpus.Sample::fixture).distinct().count())
                .as("样本文件名不得重复")
                .isEqualTo(samples.size());
        for (EvalCorpus.Sample sample : samples) {
            assertThat(sample.answerTiers())
                    .as("%s 应提供三档人工回答用于排序评测", sample.fixture())
                    .hasSize(3);
            assertThat(sample.answerTiers().stream().map(EvalCorpus.AnswerTier::label))
                    .containsExactly("优秀", "要点不全", "答非所问");
            // 三档回答必须彼此不同，否则排序断言没有意义
            assertThat(sample.answerTiers().stream().map(EvalCorpus.AnswerTier::answer).distinct().count())
                    .as("%s 的三档回答不能重复", sample.fixture())
                    .isEqualTo(3);
        }
    }

    // ---------------------------------------------------------------- 测试替身

    private static InterviewQuestion question() {
        return new InterviewQuestion("q-eval", "s-eval", 1, QuestionType.PROJECT_TECH, Difficulty.MEDIUM,
                "请说明这个项目里你负责的部分，以及关键技术如何实现、结果如何验证。",
                "考察项目事实与机制", List.of("职责", "机制", "验证"),
                List.of("resume-project-001"), List.of("简历片段"), "追问边界", null, false,
                "SINGLE_QUESTION", Instant.now());
    }

    private static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Llm("http://127.0.0.1:1/v1", "k", "stub", "e", 0.3, 900, 500, 1000,
                        0, false, null, 0.0, 1),
                null, null, null, null, null, null);
    }

    /**
     * 确定性判分替身：按回答中的可观测特征给分（不是判断语义）。
     *
     * <p>规则按优先级从「明确答不上来」到「结构完整」判断，必须按顺序，
     * 否则会出现「优秀回答因为提到 Redis 而被判成中间档」这类自相矛盾：
     * <ol>
     *   <li>出现「不清楚 / 没做过 / 记不清 / 不确定」→ 0~1 分档（答非所问或含糊）；</li>
     *   <li>出现「背景」且出现「结果」→ 高分档（结构完整）；</li>
     *   <li>其余 → 中间档。</li>
     * </ol>
     * 这样排序断言失败时能立刻看出是哪一档没被识别，而不是去猜模型行为。
     */
    private static AnswerEvaluator deterministicEvaluator() {
        LlmClient stub = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                String prompt = request.userPrompt() == null ? "" : request.userPrompt();
                int base;
                if (prompt.contains("不清楚") || prompt.contains("没做过") || prompt.contains("不太确定")
                        || prompt.contains("没系统学过") || prompt.contains("没参与过")) {
                    base = 0;
                } else if (prompt.contains("背景") && prompt.contains("结果")) {
                    base = 4;
                } else {
                    base = 2;
                }
                String reason = base == 0 ? "回答未正面回应问题。" : "按可观测特征给出的档位判定。";
                String json = """
                        {
                          "dimensionScores": {
                            "technical_correctness": { "score": %d, "reason": "%s" },
                            "completeness": { "score": %d, "reason": "%s" },
                            "experience_match": { "score": %d, "reason": "%s" },
                            "structure": { "score": %d, "reason": "%s" }
                          },
                          "missingPoints": ["边界条件"],
                          "referenceAnswerStructure": "背景 → 职责 → 机制 → 结果",
                          "summary": "档位判定完成。"
                        }
                        """.formatted(base, reason, base, reason, base, reason, base, reason);
                return new LlmResponse(json, "stub", 1L);
            }

            @Override
            public String modelName() {
                return "stub";
            }

            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        return new AnswerEvaluator(stub, new StructuredOutputParser(new ObjectMapper()), properties(),
                Clock.fixed(Instant.parse("2024-06-01T10:00:00Z"), ZoneOffset.UTC));
    }
}
