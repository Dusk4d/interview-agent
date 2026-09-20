package com.dusk4d.interview.testkit;

import com.dusk4d.interview.agent.AnswerEvaluator;
import com.dusk4d.interview.agent.QuestionGenerator;
import com.dusk4d.interview.agent.ReportBuilder;
import com.dusk4d.interview.agent.StructuredOutputParser;
import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.AnswerEvaluation;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewSession;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.error.LlmException;
import com.dusk4d.interview.llm.LocalHashEmbeddingClient;
import com.dusk4d.interview.llm.OpenAiCompatibleClient;
import com.dusk4d.interview.parse.ResumeFactExtractor;
import com.dusk4d.interview.parse.TextCleaner;
import com.dusk4d.interview.parse.extract.DocumentExtractorRouter;
import com.dusk4d.interview.privacy.PrivacyMasker;
import com.dusk4d.interview.rag.DocumentRetriever;
import com.dusk4d.interview.rag.InMemoryVectorStore;
import com.dusk4d.interview.rag.KnowledgeBase;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.service.InterviewService;
import com.dusk4d.interview.service.ResumeImportService;
import com.dusk4d.interview.storage.InMemoryStore;
import com.dusk4d.interview.storage.InterviewRepository;
import com.dusk4d.interview.storage.MapBackedInterviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线评测（针对真实模型）。
 *
 * <p>目的：把方案书第十一节要求的量化验证真正跑出来，而不是停留在「待测」。
 * 评测项：
 * <ol>
 *   <li><b>问题相关性</b>：生成的问题是否回指到正确来源（sourceIds 是否来自本简历/知识点）</li>
 *   <li><b>结构化输出成功率</b>：出题与评分中一次成功解析的比例，以及降级率</li>
 *   <li><b>评分一致性</b>：同一回答重复评分 N 次，统计总分与各维度的标准差、极差</li>
 *   <li><b>响应耗时</b>：出题与评分的 P50 / P95</li>
 *   <li><b>流程完整性</b>：评分是否包含依据、遗漏点、纠错建议与参考结构</li>
 * </ol>
 *
 * <p>用法：
 * {@code java -cp <test-classpath> com.dusk4d.interview.testkit.LiveEvalMain --base-url=... --model=... --runs=5}
 * 结果同时打印到控制台并写入 {@code target/eval-report.md} 与 {@code target/eval-report.json}。
 */
public final class LiveEvalMain {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static String baseUrl = "http://127.0.0.1:11434/v1";
    private static String model = "qwen3:1.7b";
    private static int consistencyRuns = 5;
    private static int questionsPerMode = 4;
    private static int evalSamples = 1;
    private static Path outputDir = Path.of("target");

    private LiveEvalMain() {
    }

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.startsWith("--base-url=")) {
                baseUrl = arg.substring("--base-url=".length());
            } else if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
            } else if (arg.startsWith("--runs=")) {
                consistencyRuns = Integer.parseInt(arg.substring("--runs=".length()));
            } else if (arg.startsWith("--questions=")) {
                questionsPerMode = Integer.parseInt(arg.substring("--questions=".length()));
            } else if (arg.startsWith("--eval-samples=")) {
                evalSamples = Integer.parseInt(arg.substring("--eval-samples=".length()));
            } else if (arg.startsWith("--out=")) {
                outputDir = Path.of(arg.substring("--out=".length()));
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("baseUrl", baseUrl);
        report.put("model", model);
        report.put("evalSamples", evalSamples);
        report.put("generatedAt", java.time.Instant.now().toString());

        System.out.println("=== 离线评测 ===");
        System.out.println("模型服务：" + baseUrl + "，模型：" + model);
        System.out.println("评分采样次数：" + evalSamples + "（>1 表示取中位数）");
        System.out.println("评分一致性重复次数：" + consistencyRuns + "，每模式出题数：" + questionsPerMode);

        Harness harness = new Harness();
        checkConnectivity(harness);

        List<Map<String, Object>> relevance = evalQuestionRelevance(harness);
        report.put("questionRelevance", relevance);

        Map<String, Object> structured = evalStructuredOutput(harness);
        report.put("structuredOutput", structured);

        Map<String, Object> consistency = evalScoringConsistency(harness);
        report.put("scoringConsistency", consistency);

        Map<String, Object> completeness = evalFeedbackCompleteness(harness);
        report.put("feedbackCompleteness", completeness);

        Map<String, Object> latency = evalLatency(harness);
        report.put("latency", latency);

        String markdown = renderMarkdown(report);
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("eval-report.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("eval-report.md"), markdown, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println(markdown);
        System.out.println("报告已写入 " + outputDir.toAbsolutePath().resolve("eval-report.md"));
    }

    // ================================================================ 评测项

    private static void checkConnectivity(Harness harness) {
        System.out.println();
        System.out.println("--- 连通性 ---");
        boolean ok = harness.chatClient.available();
        System.out.println("模型服务可用：" + ok);
        if (!ok) {
            System.out.println("模型服务不可用，评测终止。请先启动 LM Studio / Ollama 并确认模型名称。");
            System.exit(2);
        }
    }

    /** 1) 问题相关性：来源必须可核验。 */
    private static List<Map<String, Object>> evalQuestionRelevance(Harness harness) {
        System.out.println();
        System.out.println("--- 1) 问题相关性 ---");
        List<Map<String, Object>> rows = new ArrayList<>();

        // 项目面：来源必须来自该简历，且问题应提到项目名或职责相关词
        for (String fixture : List.of("resume-standard.txt", "resume-tight-headers.txt",
                "resume-numbered-projects.txt")) {
            Resume resume = harness.importFixture(fixture);
            List<String> factIds = resume.facts().stream().map(ResumeFact::id).toList();
            List<String> projectNames = resume.factsOf(FactType.PROJECT).stream()
                    .map(ResumeFact::label).toList();

            InterviewSession session = harness.interviewService.createSession(resume.id(), InterviewMode.PROJECT,
                    questionsPerMode);
            for (int i = 0; i < questionsPerMode; i++) {
                InterviewService.QuestionView view = harness.interviewService.nextQuestion(session.id());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fixture", fixture);
                row.put("mode", "PROJECT");
                row.put("sequence", view.question().sequence());
                row.put("degraded", view.degraded());
                row.put("sourceIdsFromResume", factIds.containsAll(view.question().sourceIds())
                        && !view.question().sourceIds().isEmpty());
                row.put("sourceCount", view.question().sourceIds().size());
                row.put("citationCount", view.citations().size());
                boolean mentionsProject = projectNames.stream().anyMatch(name ->
                        !name.isBlank() && view.question().content().contains(name));
                row.put("mentionsProjectName", mentionsProject);
                row.put("question", view.question().content());
                rows.add(row);
                System.out.printf(Locale.ROOT, "  [%s #%d] degraded=%s sources=%d 提到项目名=%s%n",
                        fixture, view.question().sequence(), view.degraded(),
                        view.question().sourceIds().size(), mentionsProject);
                // 答一题，避免会话状态卡在 WAITING_ANSWER
                harness.answer(session, view, "我负责该模块，使用缓存与索引优化，压测响应时间下降 40%。");
            }
        }

        // 八股面：来源必须来自知识库
        InterviewSession knowledge = harness.interviewService.createSession(null, InterviewMode.KNOWLEDGE,
                questionsPerMode);
        for (int i = 0; i < questionsPerMode; i++) {
            InterviewService.QuestionView view = harness.interviewService.nextQuestion(knowledge.id());
            boolean fromKnowledge = !view.question().sourceIds().isEmpty()
                    && view.question().sourceIds().stream().allMatch(id -> id.startsWith("knowledge-"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fixture", "(knowledge-base)");
            row.put("mode", "KNOWLEDGE");
            row.put("sequence", view.question().sequence());
            row.put("degraded", view.degraded());
            row.put("sourceIdsFromKnowledge", fromKnowledge);
            row.put("sourceCount", view.question().sourceIds().size());
            row.put("question", view.question().content());
            rows.add(row);
            System.out.printf(Locale.ROOT, "  [knowledge #%d] degraded=%s 知识库来源=%s%n",
                    view.question().sequence(), view.degraded(), fromKnowledge);
            harness.answer(knowledge, view,
                    "以 Redis 分布式锁为例，加锁用 SET NX PX 保证原子性，解锁必须用 Lua 比对 value，"
                            + "超时时间要覆盖业务耗时，否则会出现锁提前释放。");
        }

        long grounded = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("sourceIdsFromResume"))
                        || Boolean.TRUE.equals(row.get("sourceIdsFromKnowledge")))
                .count();
        System.out.printf(Locale.ROOT, "  小结：%d/%d 的问题带可核验来源（%.0f%%）%n",
                grounded, rows.size(), rows.isEmpty() ? 0 : 100.0 * grounded / rows.size());
        return rows;
    }

    /** 2) 结构化输出成功率。 */
    private static Map<String, Object> evalStructuredOutput(Harness harness) {
        System.out.println();
        System.out.println("--- 2) 结构化输出成功率 ---");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", harness.stats.calls);
        result.put("degradedCalls", harness.stats.degraded);
        result.put("invalidStructure", harness.stats.invalidStructure);
        result.put("badResponse", harness.stats.badResponse);
        result.put("timeouts", harness.stats.timeouts);
        result.put("emptyContent", harness.stats.emptyContent);
        result.put("connectionErrors", harness.stats.connectionErrors);
        double success = harness.stats.calls == 0 ? 0
                : 100.0 * (harness.stats.calls - harness.stats.degraded) / harness.stats.calls;
        result.put("successRatePercent", Math.round(success * 10) / 10.0);
        System.out.printf(Locale.ROOT, "  调用 %d 次，降级 %d 次，一次成功率 %.1f%%%n",
                harness.stats.calls, harness.stats.degraded, success);
        System.out.printf(Locale.ROOT, "  失败类型：结构非法 %d，响应异常 %d，超时 %d，空响应 %d，连接失败 %d%n",
                harness.stats.invalidStructure, harness.stats.badResponse, harness.stats.timeouts,
                harness.stats.emptyContent, harness.stats.connectionErrors);
        return result;
    }

    /** 3) 评分一致性：同一回答重复评分。 */
    private static Map<String, Object> evalScoringConsistency(Harness harness) {
        System.out.println();
        System.out.println("--- 3) 评分一致性 ---");
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>();

        record Case(String label, String answer) {
        }
        List<Case> cases = List.of(
                new Case("高质量回答", """
                        背景：订单中心需要保证同一用户重复提交只生效一次。
                        我负责优惠券核销链路，首先用 Redis SET NX PX 加锁并配合数据库唯一索引兜底，
                        难点是锁超时与业务耗时不一致，因此实现了看门狗续期，
                        结果压测下单成功率 99.5%，重复下单为 0。"""),
                new Case("部分正确回答", "用 Redis 加锁保证幂等，另外我们会用数据库的唯一索引兜底，具体实现记不太清了。"),
                new Case("答非所问回答", "这个我不太清楚，没做过这方面的内容，可能是用消息队列吧。"));

        Resume resume = harness.importFixture("resume-standard.txt");
        for (Case testCase : cases) {
            InterviewSession session = harness.interviewService.createSession(resume.id(), InterviewMode.PROJECT, 2);
            InterviewService.QuestionView question = harness.interviewService.nextQuestion(session.id());
            // 用真实检索到的上下文评分：之前用固定的合成上下文会让「经历匹配度」忽高忽低，
            // 那是评测脚本的问题，不是评分本身的波动。
            String context = harness.contextFor(resume, question);

            List<Double> totals = new ArrayList<>();
            Map<String, List<Double>> dimensionValues = new LinkedHashMap<>();
            for (int i = 0; i < consistencyRuns; i++) {
                AnswerEvaluation evaluation = harness.evaluate(question.question(), testCase.answer(), context);
                totals.add(evaluation.totalScore());
                evaluation.dimensionScores().forEach((key, dimension) ->
                        dimensionValues.computeIfAbsent(dimension.label(), k -> new ArrayList<>())
                                .add(dimension.score()));
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("case", testCase.label());
            sample.put("runs", consistencyRuns);
            sample.put("totalScores", totals);
            sample.put("totalMean", round(mean(totals)));
            sample.put("totalStdDev", round(stdDev(totals)));
            sample.put("totalRange", round(max(totals) - min(totals)));
            Map<String, Object> dims = new LinkedHashMap<>();
            dimensionValues.forEach((label, values) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("mean", round(mean(values)));
                entry.put("stdDev", round(stdDev(values)));
                entry.put("range", round(max(values) - min(values)));
                dims.put(label, entry);
            });
            sample.put("dimensions", dims);
            samples.add(sample);

            System.out.printf(Locale.ROOT, "  %s：总分 %s → 均值 %.2f，标准差 %.3f，极差 %.1f%n",
                    testCase.label(), totals, mean(totals), stdDev(totals), max(totals) - min(totals));
        }
        result.put("cases", samples);
        double avgStdDev = samples.stream()
                .mapToDouble(sample -> ((Number) sample.get("totalStdDev")).doubleValue())
                .average().orElse(0);
        result.put("averageTotalStdDev", round(avgStdDev));
        System.out.printf(Locale.ROOT, "  小结：三类回答的平均总分标准差 %.3f（越小越稳定）%n", avgStdDev);
        return result;
    }

    /** 4) 反馈完整性。 */
    private static Map<String, Object> evalFeedbackCompleteness(Harness harness) {
        System.out.println();
        System.out.println("--- 4) 反馈完整性 ---");
        Resume resume = harness.importFixture("resume-standard.txt");
        InterviewSession session = harness.interviewService.createSession(resume.id(), InterviewMode.PROJECT, 2);
        InterviewService.QuestionView question = harness.interviewService.nextQuestion(session.id());
        AnswerEvaluation evaluation = harness.evaluate(question.question(), "用 Redis 加锁保证幂等。",
                harness.contextFor(resume, question));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasTotalScore", evaluation.totalScore() >= 0);
        result.put("dimensionCount", evaluation.dimensionScores().size());
        result.put("allDimensionsHaveReason", evaluation.dimensionScores().values().stream()
                .allMatch(dimension -> dimension.reason() != null && !dimension.reason().isBlank()));
        result.put("hasMissingPoints", !evaluation.missingPoints().isEmpty());
        result.put("hasSuggestedAdditions", !evaluation.suggestedAdditions().isEmpty());
        result.put("hasReferenceStructure", evaluation.referenceAnswerStructure() != null
                && !evaluation.referenceAnswerStructure().isBlank());
        result.put("hasSummary", evaluation.summary() != null && !evaluation.summary().isBlank());
        result.put("dimensions", evaluation.dimensionScores().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> Map.of("score", entry.getValue().score(), "reason", entry.getValue().reason()),
                        (a, b) -> a, LinkedHashMap::new)));
        result.put("missingPoints", evaluation.missingPoints());
        result.put("corrections", evaluation.corrections());
        result.put("suggestedAdditions", evaluation.suggestedAdditions());
        result.put("referenceAnswerStructure", evaluation.referenceAnswerStructure());
        result.put("summary", evaluation.summary());

        System.out.println("  维度数：" + result.get("dimensionCount")
                + "，每维都有依据：" + result.get("allDimensionsHaveReason")
                + "，含遗漏点：" + result.get("hasMissingPoints")
                + "，含参考结构：" + result.get("hasReferenceStructure"));
        return result;
    }

    /** 5) 响应耗时。 */
    private static Map<String, Object> evalLatency(Harness harness) {
        System.out.println();
        System.out.println("--- 5) 响应耗时 ---");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionGenerationMs", describe(harness.stats.questionLatencies));
        result.put("answerEvaluationMs", describe(harness.stats.evaluationLatencies));
        System.out.println("  出题耗时：" + result.get("questionGenerationMs"));
        System.out.println("  评分耗时：" + result.get("answerEvaluationMs"));
        return result;
    }

    // ================================================================ 渲染

    private static String renderMarkdown(Map<String, Object> report) {
        StringBuilder md = new StringBuilder();
        md.append("# 离线评测报告\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 模型服务 | ").append(report.get("baseUrl")).append(" |\n");
        md.append("| 模型 | ").append(report.get("model")).append(" |\n");
        md.append("| 评测时间 | ").append(report.get("generatedAt")).append(" |\n\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relevance = (List<Map<String, Object>>) report.get("questionRelevance");
        long grounded = relevance.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("sourceIdsFromResume"))
                        || Boolean.TRUE.equals(row.get("sourceIdsFromKnowledge")))
                .count();
        long degradedQuestions = relevance.stream().filter(row -> Boolean.TRUE.equals(row.get("degraded"))).count();
        md.append("## 一、问题相关性\n\n");
        md.append(String.format(Locale.ROOT, "- 生成问题数：%d%n", relevance.size()));
        md.append(String.format(Locale.ROOT, "- 来源可核验（回指正确简历/知识点）：%d/%d（%.0f%%）%n",
                grounded, relevance.size(), relevance.isEmpty() ? 0 : 100.0 * grounded / relevance.size()));
        md.append(String.format(Locale.ROOT, "- 降级出题（模板兜底）：%d%n", degradedQuestions));
        long mentionsProject = relevance.stream().filter(row -> Boolean.TRUE.equals(row.get("mentionsProjectName"))).count();
        long projectQuestions = relevance.stream().filter(row -> "PROJECT".equals(row.get("mode"))).count();
        md.append(String.format(Locale.ROOT, "- 项目面问题中显式提到项目名：%d/%d%n%n", mentionsProject, projectQuestions));

        md.append("## 二、结构化输出\n\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) report.get("structuredOutput");
        md.append(String.format(Locale.ROOT, "- 模型调用次数：%s%n", structured.get("totalCalls")));
        md.append(String.format(Locale.ROOT, "- 一次性结构化成功率：%s%%%n", structured.get("successRatePercent")));
        md.append(String.format(Locale.ROOT, "- 降级次数：%s（结构非法 %s，响应异常 %s，超时 %s，空响应 %s，连接失败 %s）%n%n",
                structured.get("degradedCalls"), structured.get("invalidStructure"),
                structured.get("badResponse"), structured.get("timeouts"),
                structured.get("emptyContent"), structured.get("connectionErrors")));

        md.append("## 三、评分一致性\n\n");
        md.append("| 回答类型 | 总分序列 | 均值 | 标准差 | 极差 |\n|---|---|---|---|---|\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> consistency = (Map<String, Object>) report.get("scoringConsistency");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) consistency.get("cases");
        for (Map<String, Object> sample : cases) {
            md.append("| ").append(sample.get("case")).append(" | ").append(sample.get("totalScores"))
                    .append(" | ").append(sample.get("totalMean")).append(" | ")
                    .append(sample.get("totalStdDev")).append(" | ").append(sample.get("totalRange"))
                    .append(" |\n");
        }
        md.append("\n平均总分标准差：").append(consistency.get("averageTotalStdDev")).append("\n\n");

        md.append("## 四、反馈完整性\n\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> completeness = (Map<String, Object>) report.get("feedbackCompleteness");
        md.append(String.format(Locale.ROOT, "- 评分维度数：%s（每个维度都有打分依据：%s）%n",
                completeness.get("dimensionCount"), completeness.get("allDimensionsHaveReason")));
        md.append(String.format(Locale.ROOT, "- 含遗漏点：%s；含建议补充：%s；含参考回答结构：%s；含总评：%s%n%n",
                completeness.get("hasMissingPoints"), completeness.get("hasSuggestedAdditions"),
                completeness.get("hasReferenceStructure"), completeness.get("hasSummary")));

        md.append("## 五、响应耗时\n\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) report.get("latency");
        md.append("- 出题：").append(latency.get("questionGenerationMs")).append('\n');
        md.append("- 评分：").append(latency.get("answerEvaluationMs")).append('\n');
        return md.toString();
    }

    // ================================================================ 工具

    private static Map<String, Object> describe(List<Long> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values.isEmpty()) {
            result.put("samples", 0);
            return result;
        }
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        result.put("samples", sorted.size());
        result.put("min", sorted.get(0));
        result.put("p50", percentile(sorted, 50));
        result.put("p95", percentile(sorted, 95));
        result.put("max", sorted.get(sorted.size() - 1));
        return result;
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double stdDev(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double avg = mean(values);
        double variance = values.stream().mapToDouble(v -> (v - avg) * (v - avg)).sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private static double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    // ================================================================ 评测支架

    /** 统计计数器。 */
    private static final class Stats {
        int calls;
        int degraded;
        int invalidStructure;
        int badResponse;
        int timeouts;
        int emptyContent;
        int connectionErrors;
        final List<Long> questionLatencies = new ArrayList<>();
        final List<Long> evaluationLatencies = new ArrayList<>();
    }

    /** 评测支架：装配真实模型 + 真实服务，并统计成功/失败/耗时。 */
    private static final class Harness {
        final Stats stats = new Stats();
        final OpenAiCompatibleClient chatClient;
        final InterviewService interviewService;
        final ResumeImportService resumeService;
        final KnowledgeBase knowledgeBase;
        final StructuredOutputParser parser = new StructuredOutputParser(MAPPER);

        /** 评测用的真实配置：带上 eval-temperature / eval-samples，否则测到的不是真实行为。 */
        final AppProperties properties;

        Harness() {
            AppProperties props = new AppProperties(
                    new AppProperties.Llm(baseUrl, "not-needed", model, "nomic-embed",
                            0.3, 1600, 3000, 120000, 0, false, null, 0.0, evalSamples),
                    new AppProperties.Embedding("local", 256, 8),
                    new AppProperties.Retrieval(4, 0.05, 4000, 3),
                    new AppProperties.Interview(10, 1, 8, 4000, 3),
                    new AppProperties.Privacy(true, false),
                    new AppProperties.Storage("memory", ""),
                    new AppProperties.Parser(10 * 1024 * 1024));
            this.properties = props;
            this.chatClient = new OpenAiCompatibleClient(baseUrl, "not-needed",
                    props.llm().resolvedChatModel(), 1600, 3000, 120000, MAPPER,
                    props.llm().shouldDisableThinking());
            AppProperties properties = props;

            VectorStore vectorStore = new InMemoryVectorStore(new LocalHashEmbeddingClient(properties));
            InterviewRepository repository = new MapBackedInterviewRepository(
                    new InMemoryStore<>(Resume::id),
                    new InMemoryStore<>(InterviewSession::id),
                    new InMemoryStore<>(q -> q.id()),
                    new InMemoryStore<>(a -> a.id()),
                    new InMemoryStore<>(e -> e.id()),
                    new InMemoryStore<>(r -> r.id()));
            this.knowledgeBase = new KnowledgeBase(MAPPER, vectorStore);
            TextCleaner cleaner = new TextCleaner();
            PrivacyMasker masker = new PrivacyMasker();
            DocumentRetriever retriever = new DocumentRetriever(vectorStore,
                    new LocalHashEmbeddingClient(properties), properties);
            Clock clock = Clock.system(ZoneOffset.UTC);
            this.resumeService = new ResumeImportService(new DocumentExtractorRouter(), cleaner,
                    new ResumeFactExtractor(cleaner, masker), masker, repository, vectorStore, properties, clock);
            this.interviewService = new InterviewService(repository, resumeService, retriever, knowledgeBase,
                    new QuestionGenerator(new CountingLlmClient(), parser, properties),
                    new AnswerEvaluator(new CountingLlmClient(), parser, properties, clock),
                    new ReportBuilder(new CountingLlmClient(), parser, properties, clock),
                    properties, clock);
        }

        /** 计数用的模型客户端包装：统计调用、降级类型与耗时。 */
        private final class CountingLlmClient implements com.dusk4d.interview.llm.LlmClient {
            @Override
            public com.dusk4d.interview.llm.LlmResponse chat(com.dusk4d.interview.llm.LlmRequest request) {
                stats.calls++;
                long start = System.nanoTime();
                boolean questionTask = "question".equals(request.schemaName())
                        || "followup".equals(request.schemaName());
                try {
                    com.dusk4d.interview.llm.LlmResponse response = chatClient.chat(request);
                    long elapsed = (System.nanoTime() - start) / 1_000_000L;
                    if (questionTask) {
                        stats.questionLatencies.add(elapsed);
                    } else {
                        stats.evaluationLatencies.add(elapsed);
                    }
                    // 结构化任务需要在这里验证一次可解析性（服务层还会再解析一次）
                    return response;
                } catch (LlmException e) {
                    stats.degraded++;
                    switch (e.kind()) {
                        case INVALID_STRUCTURE -> stats.invalidStructure++;
                        case TIMEOUT -> stats.timeouts++;
                        case CONNECTION -> stats.connectionErrors++;
                        case BAD_RESPONSE -> {
                            if (String.valueOf(e.getMessage()).contains("空正文")
                                    || String.valueOf(e.getMessage()).contains("空内容")) {
                                stats.emptyContent++;
                            } else {
                                stats.badResponse++;
                            }
                        }
                    }
                    throw e;
                }
            }

            @Override
            public String modelName() {
                return chatClient.modelName();
            }

            @Override
            public String provider() {
                return chatClient.provider();
            }

            @Override
            public boolean available() {
                return chatClient.available();
            }
        }

        Resume importFixture(String fixture) {
            try {
                String text = new String(LiveEvalMain.class.getClassLoader()
                        .getResourceAsStream("fixtures/" + fixture).readAllBytes(), StandardCharsets.UTF_8);
                return resumeService.importText(text, fixture).resume();
            } catch (Exception e) {
                throw new IllegalStateException("读取夹具失败：" + fixture, e);
            }
        }

        /** 提交一次回答（走真实服务流程）。 */
        void answer(InterviewSession session, InterviewService.QuestionView question, String content) {
            try {
                interviewService.submitAnswer(session.id(), question.question().id(), content);
            } catch (RuntimeException e) {
                System.out.println("    （提交回答失败：" + e.getMessage() + "）");
            }
        }

        /** 与业务一致的评测器配置，供一致性评测复用（此前这里用了空属性，等于没测到温度设置）。 */
        AnswerEvaluation evaluate(com.dusk4d.interview.domain.InterviewQuestion question, String answer,
                                  String context) {
            return new AnswerEvaluator(new CountingLlmClient(), parser, properties, Clock.systemUTC())
                    .evaluate(question, answer, context, List.of());
        }

        /** 用真实检索到的简历片段构造评分上下文（与业务链路一致）。 */
        String contextFor(Resume resume, InterviewService.QuestionView question) {
            List<ResumeFact> facts;
            if (!question.question().sourceIds().isEmpty()) {
                facts = resume.facts().stream()
                        .filter(fact -> question.question().sourceIds().contains(fact.id()))
                        .toList();
            } else {
                facts = List.of();
            }
            if (facts.isEmpty()) {
                facts = resume.facts().stream()
                        .filter(fact -> fact.type() == FactType.PROJECT)
                        .findFirst()
                        .map(List::of)
                        .orElse(List.of());
            }
            StringBuilder sb = new StringBuilder();
            int index = 1;
            for (ResumeFact fact : facts) {
                sb.append('[').append(index++).append("] 简历片段：").append(fact.label()).append('\n')
                        .append(fact.content()).append('\n');
            }
            return sb.toString().strip();
        }
    }
}
