package com.dusk4d.interview.parse;

import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多份不同结构简历的解析回归（对应验收标准「检查解析字段是否丢失」）。
 *
 * <p>这里把不同排版风格（标准标题、无空格标题、编号项目、极简内容）放在同一个用例里批量跑，
 * 任何一份出现字段丢失或误判都会直接失败。测试集规模目前是 4 份，
 * 扩到方案书建议的 10 份只需继续往 fixtures 里加文件并在此处登记。
 */
@DisplayName("多简历解析回归")
class MultiResumeRegressionTest {

    private final TextCleaner cleaner = new TextCleaner();
    private final ResumeFactExtractor extractor = new ResumeFactExtractor(cleaner,
            new com.dusk4d.interview.privacy.PrivacyMasker());

    private record Expectation(String fixture, String mustContainFact, FactType requiredType,
                               String mustContainTech, boolean projectRequired) {
    }

    private static final List<Expectation> CASES = List.of(
            new Expectation("resume-standard.txt", "FinanceAgent", FactType.PROJECT, "pgvector", true),
            new Expectation("resume-tight-headers.txt", "智学助手", FactType.PROJECT, "FastAPI", true),
            new Expectation("resume-numbered-projects.txt", "电商秒杀系统", FactType.PROJECT, "RocketMQ", true),
            new Expectation("resume-minimal.txt", "Java", FactType.SKILL, "Java", false));

    @Test
    @DisplayName("四份不同结构的简历都能解析出预期字段，且不误判区块类型")
    void allFixturesParseWithoutFieldLoss() {
        for (Expectation expectation : CASES) {
            CleanResult cleaned = cleaner.clean(Fixtures.text(expectation.fixture()));
            assertThat(cleaned.usable())
                    .as("%s 应可解析", expectation.fixture())
                    .isTrue();

            ResumeFactExtractor.Result result = extractor.extract(cleaned.text());
            assertThat(result.facts())
                    .as("%s 应抽取到事实", expectation.fixture())
                    .isNotEmpty();

            String allContent = result.facts().stream()
                    .map(fact -> fact.label() + " " + fact.content())
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(allContent)
                    .as("%s 应包含关键内容：%s", expectation.fixture(), expectation.mustContainFact())
                    .contains(expectation.mustContainFact());

            assertThat(result.facts().stream().map(ResumeFact::type))
                    .as("%s 应识别出 %s 类型", expectation.fixture(), expectation.requiredType())
                    .contains(expectation.requiredType());

            assertThat(result.techStack())
                    .as("%s 应识别出技术栈：%s", expectation.fixture(), expectation.mustContainTech())
                    .contains(expectation.mustContainTech());

            if (expectation.projectRequired()) {
                assertThat(result.facts().stream().filter(f -> f.type() == FactType.PROJECT).count())
                        .as("%s 应识别到项目", expectation.fixture())
                        .isPositive();
            }

            // 事实 ID 必须唯一，否则向量库会互相覆盖
            assertThat(result.facts().stream().map(ResumeFact::id).distinct().count())
                    .as("%s 的事实 ID 应唯一", expectation.fixture())
                    .isEqualTo(result.facts().size());

            // 抽取结果不得包含未脱敏的联系方式
            assertThat(allContent)
                    .as("%s 不应包含手机号", expectation.fixture())
                    .doesNotContain("13812345678").doesNotContain("139-0000-1111")
                    .doesNotContain("13600001111").doesNotContain("lina2024@test-mail.com");
        }
    }

    @Test
    @DisplayName("同一份简历重复解析结果完全一致（可复现，便于做差异对比）")
    void parsingIsDeterministic() {
        for (Expectation expectation : CASES) {
            CleanResult cleaned = cleaner.clean(Fixtures.text(expectation.fixture()));
            ResumeFactExtractor.Result first = extractor.extract(cleaned.text());
            ResumeFactExtractor.Result second = extractor.extract(cleaned.text());
            assertThat(second.facts()).as("%s 解析应可复现", expectation.fixture()).isEqualTo(first.facts());
            assertThat(second.techStack()).isEqualTo(first.techStack());
            assertThat(second.confidence()).isEqualTo(first.confidence());
        }
    }

    @Test
    @DisplayName("极简简历不编造项目，且置信度低到足以提示人工核对")
    void minimalResumeIsFlaggedNotFabricated() {
        CleanResult cleaned = cleaner.clean(Fixtures.text("resume-minimal.txt"));
        ResumeFactExtractor.Result result = extractor.extract(cleaned.text());

        assertThat(result.facts().stream().filter(f -> f.type() == FactType.PROJECT).count()).isZero();
        assertThat(result.confidence()).isLessThan(0.8);
        // 事实里不能出现简历中不存在的内容
        String allContent = result.facts().stream().map(ResumeFact::content).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allContent).doesNotContain("Spring Boot").doesNotContain("Redis").doesNotContain("分布式");
    }

    @Test
    @DisplayName("简历事实重建（模拟落库后再取出）字段不丢失")
    void factsSurviveResumeReconstruction() {
        CleanResult cleaned = cleaner.clean(Fixtures.text("resume-standard.txt"));
        ResumeFactExtractor.Result result = extractor.extract(cleaned.text());
        String resumeId = "resume-xyz";

        List<ResumeFact> persisted = result.facts().stream()
                .map(fact -> new ResumeFact(fact.id(), resumeId, fact.type(), fact.label(), fact.content(),
                        fact.sourceOrder(), fact.confidence(), fact.metadata()))
                .toList();
        Resume resume = new Resume(resumeId, "张伟-简历.txt", "txt", 2089, cleaned.text(), cleaned.text(),
                com.dusk4d.interview.domain.ResumeStatus.PARSED, null, List.of(), persisted,
                java.time.Instant.now(), java.time.Instant.now());

        assertThat(resume.usable()).isTrue();
        assertThat(resume.factsOf(FactType.PROJECT)).hasSize(2);
        assertThat(resume.factsOf(FactType.EDUCATION)).isNotEmpty();
        assertThat(resume.factsOf(FactType.INTERNSHIP)).isNotEmpty();
        assertThat(resume.factsOf(FactType.AWARD)).isNotEmpty();
        assertThat(resume.factsOf(FactType.SKILL)).isNotEmpty();
    }
}
