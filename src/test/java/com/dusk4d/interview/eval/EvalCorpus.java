package com.dusk4d.interview.eval;

import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.privacy.PrivacyMasker;
import com.dusk4d.interview.support.Fixtures;

import java.util.List;
import java.util.Map;

/**
 * 评测样本（对应方案书第十一节建议的测试集）。
 *
 * <p>离线评测集的价值在于把「看起来能用」变成「可批量断言」：
 * <ul>
 *   <li>{@code expectedFactLabels}：人工确认过的必须抽到的项目/公司/学校名，用于测解析召回；</li>
 *   <li>{@code techStack}：人工确认必现的技术栈关键词；</li>
 *   <li>{@code approvedQuestionKeywords}：人工认可的、针对该简历可以问的方向关键词；</li>
 *   <li>{@code answerTiers}：同一道题的三档人工撰写回答，用于测评分的**排序单调性**
 *       （优秀 &gt; 部分正确 &gt; 答非所问），这比测绝对分数更有意义。</li>
 * </ul>
 *
 * <p>扩充方式：往 {@code src/test/resources/fixtures/} 加简历，并在这里登记一条样本即可。
 * 方案书建议的规模是 10 份不同结构简历，当前覆盖 4 份，属于「待补」项而非已完成项。
 */
public final class EvalCorpus {

    private EvalCorpus() {
    }

    /** 一档人工撰写的回答。 */
    public record AnswerTier(String label, String answer, int minScore, int maxScore) {
    }

    /** 一条评测样本。 */
    public record Sample(
            String fixture,
            String displayName,
            List<String> expectedFactLabels,
            List<String> expectedFactTypes,
            List<String> techStack,
            List<String> approvedQuestionKeywords,
            List<AnswerTier> answerTiers
    ) {
        public Sample {
            expectedFactLabels = expectedFactLabels == null ? List.of() : List.copyOf(expectedFactLabels);
            expectedFactTypes = expectedFactTypes == null ? List.of() : List.copyOf(expectedFactTypes);
            techStack = techStack == null ? List.of() : List.copyOf(techStack);
            approvedQuestionKeywords = approvedQuestionKeywords == null ? List.of() : List.copyOf(approvedQuestionKeywords);
            answerTiers = answerTiers == null ? List.of() : List.copyOf(answerTiers);
        }
    }

    /** 全部样本（4 份不同结构的中文简历）。 */
    public static List<Sample> samples() {
        return List.of(
                new Sample("resume-standard.txt", "标准分区简历",
                        List.of("FinanceAgent", "云中摄影", "北京邮电大学", "杭州云启科技"),
                        List.of("PROJECT", "EDUCATION", "INTERNSHIP", "SKILL", "AWARD"),
                        List.of("Java", "Spring Boot", "PostgreSQL", "pgvector", "Redis"),
                        List.of("项目", "职责", "技术", "实现", "难点", "结果"),
                        List.of(
                                new AnswerTier("优秀", """
                                        背景：订单中心要保证同一用户重复提交只生效一次。
                                        我负责优惠券核销链路，用 Redis SET NX PX 加锁并配合数据库唯一索引兜底；
                                        难点是锁超时与业务耗时不一致，所以实现了看门狗续期；
                                        结果压测下单成功率 99.5%，重复下单为 0。""", 2, 5),
                                new AnswerTier("要点不全", "用 Redis 加锁保证幂等，另外用数据库唯一索引兜底，具体实现记不太清了。", 1, 4),
                                new AnswerTier("答非所问", "这个我不太清楚，没做过这方面的内容。", 0, 3))),

                new Sample("resume-tight-headers.txt", "紧凑标题简历",
                        List.of("智学助手", "健康打卡", "华中科技大学", "武汉光谷"),
                        List.of("PROJECT", "SKILL", "EDUCATION", "INTERNSHIP", "AWARD"),
                        List.of("Python", "FastAPI", "MySQL", "Redis", "Vue3"),
                        List.of("项目", "埋点", "推荐", "部署", "统计"),
                        List.of(
                                new AnswerTier("优秀", """
                                        背景：这个平台要给课程做推荐，我负责埋点数据模型和日报表。
                                        埋点量大，所以先批量写入再按天分区，单表查询走覆盖索引；
                                        结果：写入压力下降 60%，报表延迟从小时级降到分钟级。""", 2, 5),
                                new AnswerTier("要点不全", "我做过课程推荐和埋点，用 MySQL 存数据，做了些优化。", 1, 4),
                                new AnswerTier("答非所问", "这个项目我没参与过，不太了解。", 0, 3))),

                new Sample("resume-numbered-projects.txt", "编号式项目简历",
                        List.of("电商秒杀系统", "图书管理系统", "电子科技大学", "成都天软"),
                        List.of("PROJECT", "EDUCATION", "INTERNSHIP", "SKILL", "AWARD"),
                        List.of("Java", "Spring Boot", "MySQL", "Redis", "RocketMQ"),
                        List.of("秒杀", "库存", "限流", "幂等", "压测"),
                        List.of(
                                new AnswerTier("优秀", """
                                        背景：秒杀的关键是库存不能超卖。我用 Redis 预扣库存配合 Lua 脚本保证原子性，
                                        再用数据库唯一约束和消息幂等消费做双重保障；
                                        为防热点商品打垮数据库，加了令牌桶限流；结果：压测下单成功率 99.5%。""", 2, 5),
                                new AnswerTier("要点不全", "用 Redis 扣库存，然后用消息队列异步处理订单。", 1, 4),
                                new AnswerTier("答非所问", "秒杀我没做过，只听过这个概念。", 0, 3))),

                new Sample("resume-minimal.txt", "极简简历（不编造场景）",
                        List.of(),
                        List.of("SKILL"),
                        List.of("Java"),
                        List.of(),
                        // 极简简历没有项目，问题只能围绕「掌握的基础知识」提问；
                        // 三档回答仍然齐备，这样「评分排序」评测不会因为样本不完整而漏测。
                        List.of(
                                new AnswerTier("优秀", """
                                        背景：并发编程里最容易被追问的是可见性。
                                        以 JVM 内存模型为例，它规定了原子性、可见性与有序性三组规则；
                                        volatile 通过内存屏障保证可见性并禁止重排，但不保证复合操作原子性，
                                        典型用法是双重检查锁的状态标记位，边界是不能用它替代 synchronized 保证原子性。
                                        结果：能在面试里把三要素、volatile 的两个语义与一个限制讲清楚。""", 2, 5),
                                new AnswerTier("要点不全", "volatile 能保证线程可见性，具体原理我记不太清了。", 1, 4),
                                new AnswerTier("答非所问", "我不太确定这个问题想问什么，没系统学过。", 0, 3))));
    }

    /**
     * 读取并解析一份样本简历（走真实的清洗 + 抽取链路，不含隐私脱敏之外的任何模拟）。
     *
     * @param sample        样本
     * @param cleaner       文本清洗器
     * @param extractor     事实抽取器
     * @param masker        脱敏器
     */
    public static ParsedSample parse(Sample sample,
                                     com.dusk4d.interview.parse.TextCleaner cleaner,
                                     com.dusk4d.interview.parse.ResumeFactExtractor extractor,
                                     PrivacyMasker masker) {
        com.dusk4d.interview.parse.CleanResult cleaned = cleaner.clean(Fixtures.text(sample.fixture()));
        com.dusk4d.interview.parse.ResumeFactExtractor.Result result = extractor.extract(cleaned.text());
        String resumeId = "eval-" + sample.fixture();
        List<ResumeFact> facts = result.facts().stream()
                .map(fact -> new ResumeFact(fact.id(), resumeId, fact.type(), fact.label(), fact.content(),
                        fact.sourceOrder(), fact.confidence(), fact.metadata()))
                .toList();
        Resume resume = new Resume(resumeId, sample.fixture(), "txt",
                Fixtures.text(sample.fixture()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                masker.mask(cleaned.text()), masker.mask(cleaned.text()),
                com.dusk4d.interview.domain.ResumeStatus.PARSED, null, result.warnings(), facts,
                java.time.Instant.now(), java.time.Instant.now());
        return new ParsedSample(sample, resume, result);
    }

    /** 解析结果。 */
    public record ParsedSample(Sample sample, Resume resume,
                               com.dusk4d.interview.parse.ResumeFactExtractor.Result raw) {

        /** 事实是否覆盖样本要求的所有标签。 */
        public boolean coversLabels() {
            String haystack = resume.facts().stream()
                    .map(fact -> fact.label() + " " + fact.content())
                    .reduce("", (a, b) -> a + "\n" + b);
            return sample.expectedFactLabels().stream().allMatch(haystack::contains);
        }

        /** 事实类型是否覆盖样本要求的类型。 */
        public boolean coversTypes() {
            List<FactType> actual = resume.facts().stream().map(ResumeFact::type).distinct().toList();
            return sample.expectedFactTypes().stream()
                    .map(FactType::valueOf)
                    .allMatch(actual::contains);
        }

        /** 技术栈是否覆盖样本要求。 */
        public boolean coversTechStack() {
            return sample.techStack().stream().allMatch(raw.techStack()::contains);
        }

        /** 事实内容不得含未脱敏联系方式。 */
        public boolean isMasked() {
            String all = resume.facts().stream().map(ResumeFact::content).reduce("", (a, b) -> a + "\n" + b);
            return !all.matches(".*1[3-9]\\d{9}.*")
                    && !all.contains("@example.com")
                    && !all.contains("test-mail.com");
        }

        public Map<String, Object> summary() {
            return Map.of(
                    "fixture", sample.fixture(),
                    "facts", resume.facts().size(),
                    "projects", resume.factsOf(FactType.PROJECT).size(),
                    "techStack", raw.techStack().size(),
                    "confidence", Math.round(raw.confidence() * 100) / 100.0,
                    "coversLabels", coversLabels(),
                    "coversTypes", coversTypes(),
                    "coversTechStack", coversTechStack(),
                    "masked", isMasked());
        }
    }
}
