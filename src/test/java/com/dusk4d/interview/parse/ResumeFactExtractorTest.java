package com.dusk4d.interview.parse;

import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.privacy.PrivacyMasker;
import com.dusk4d.interview.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("简历事实结构化 ResumeFactExtractor")
class ResumeFactExtractorTest {

    private final TextCleaner cleaner = new TextCleaner();
    private final PrivacyMasker masker = new PrivacyMasker();
    private final ResumeFactExtractor extractor = new ResumeFactExtractor(cleaner, masker);

    private ResumeFactExtractor.Result extractFixture(String name) {
        CleanResult cleaned = cleaner.clean(Fixtures.text(name));
        return extractor.extract(cleaned.text());
    }

    @Test
    @DisplayName("标准简历：识别教育/实习/项目/技能/奖项")
    void extractsStandardResume() {
        ResumeFactExtractor.Result result = extractFixture("resume-standard.txt");

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts().stream().map(ResumeFact::type))
                .contains(FactType.EDUCATION, FactType.INTERNSHIP, FactType.PROJECT,
                        FactType.SKILL, FactType.AWARD);
        assertThat(result.techStack()).contains("Java", "Spring Boot", "PostgreSQL", "pgvector", "Redis");
        assertThat(result.name()).isEqualTo("张伟");
    }

    @Test
    @DisplayName("项目按标题分块，项目名与技术栈进入元数据")
    void splitsProjects() {
        ResumeFactExtractor.Result result = extractFixture("resume-standard.txt");
        List<ResumeFact> projects = result.facts().stream()
                .filter(f -> f.type() == FactType.PROJECT)
                .toList();

        assertThat(projects).hasSize(2);
        assertThat(projects.get(0).label()).contains("FinanceAgent");
        assertThat(projects.get(1).label()).contains("云中摄影");
        assertThat(projects.get(0).metadata()).contains("时间：2023.09-2024.05", "Java", "pgvector");
        assertThat(projects.get(0).content()).contains("个人职责", "结果：");
        assertThat(projects.get(0).confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    @DisplayName("事实 ID 唯一且可溯源顺序稳定")
    void factIdsAreUniqueAndOrdered() {
        ResumeFactExtractor.Result result = extractFixture("resume-standard.txt");
        List<String> ids = result.facts().stream().map(ResumeFact::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith("resume-"));
    }

    @Test
    @DisplayName("无空格标题（技 术 栈）仍可识别")
    void handlesSpacedHeaders() {
        ResumeFactExtractor.Result result = extractFixture("resume-tight-headers.txt");
        assertThat(result.facts().stream().map(ResumeFact::type)).contains(FactType.SKILL);
        assertThat(result.techStack()).contains("Python", "FastAPI", "MySQL");
        assertThat(result.facts().stream().filter(f -> f.type() == FactType.PROJECT).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("编号项目（1、2、）可正确切分")
    void handlesNumberedProjects() {
        ResumeFactExtractor.Result result = extractFixture("resume-numbered-projects.txt");
        List<ResumeFact> projects = result.facts().stream()
                .filter(f -> f.type() == FactType.PROJECT)
                .toList();
        assertThat(projects).hasSize(2);
        assertThat(projects.get(0).label()).contains("电商秒杀系统");
        assertThat(projects.get(1).label()).contains("图书管理系统");
        assertThat(projects.get(0).content()).contains("超卖问题");
    }

    @Test
    @DisplayName("信息极少的简历：不编造事实，给出低置信度")
    void minimalResumeDoesNotHallucinate() {
        ResumeFactExtractor.Result result = extractFixture("resume-minimal.txt");
        assertThat(result.confidence()).isLessThan(0.8);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("项目"));
        assertThat(result.facts().stream().filter(f -> f.type() == FactType.PROJECT).count()).isZero();
    }

    @Test
    @DisplayName("空文本返回空结果而不是异常")
    void handlesEmptyText() {
        ResumeFactExtractor.Result result = extractor.extract("");
        assertThat(result.facts()).isEmpty();
        assertThat(result.confidence()).isZero();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("抽取结果中的联系方式已脱敏")
    void masksContactInfoInFacts() {
        ResumeFactExtractor.Result result = extractFixture("resume-standard.txt");
        String allContent = result.facts().stream().map(ResumeFact::content).reduce("", (a, b) -> a + "\n" + b);
        assertThat(masker.containsSensitive(allContent)).isFalse();
        assertThat(allContent).doesNotContain("13812345678");
    }

    @Test
    @DisplayName("技术栈识别：JavaScript 存在时单独的 Java 仍应被识别")
    void techStackShadowing() {
        List<String> stack = extractor.detectTechStack("使用 JavaScript 与 TypeScript 开发前端，后端使用 Java 21 与 Spring Boot");
        assertThat(stack).contains("JavaScript", "TypeScript", "Java", "Spring Boot");
        // 只有 JavaScript 时不应把 Java 也识别出来
        List<String> onlyJs = extractor.detectTechStack("纯前端项目，使用 JavaScript 与 TypeScript");
        assertThat(onlyJs).doesNotContain("Java");
        // Spring Boot 是更长更精确的术语，必须被识别
        List<String> springOnly = extractor.detectTechStack("使用 Spring Boot 搭建服务");
        assertThat(springOnly).contains("Spring Boot");
    }

    @Test
    @DisplayName("正文句子不会被误判为区块标题")
    void doesNotTreatSentenceAsHeader() {
        assertThat(extractor.matchHeader("我负责技能模块的开发工作。")).isNull();
        assertThat(extractor.matchHeader("项目经历")).isNotNull();
        assertThat(extractor.matchHeader("二、项目经验")).isNotNull();
        assertThat(extractor.matchHeader("【专业技能】")).isNotNull();
    }
}
