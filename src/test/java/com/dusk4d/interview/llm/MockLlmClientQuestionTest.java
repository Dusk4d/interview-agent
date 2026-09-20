package com.dusk4d.interview.llm;

import com.dusk4d.interview.agent.Prompts;
import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.InterviewMode;
import com.dusk4d.interview.domain.InterviewStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板出题（MockLlmClient）必须把「当前聚焦项目」的真实项目名写进问题里。
 *
 * <p>回归背景：提示词里项目名后面曾紧跟一句「（请只针对这个项目提问…）」，
 * 项目名解析把括号里的提示语一起吃掉，问题正文变成
 * 「在你的「星轨推荐引擎（请只针对这个项目提问，不要涉及其它项目）」中…」；
 * 更早的版本甚至退化成「在你的「该项目」中…」，用户看不出问的是哪个项目。
 */
@DisplayName("模板出题：项目名解析")
class MockLlmClientQuestionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECT = "星轨推荐引擎";
    private static final String CONTEXT_LINE = "[1] 简历片段：星轨推荐引擎\n"
            + "技术栈：Java、Spring Boot、Redis；负责召回与排序链路，QPS 从 800 提升到 3200。";

    private static JsonNode nodeOf(String userPrompt) {
        MockLlmClient client = new MockLlmClient(MAPPER);
        LlmResponse response = client.chat(LlmRequest.structured(
                Prompts.QUESTION_SYSTEM, userPrompt, "question",
                List.of("type", "difficulty", "question", "intent", "focus", "sourceIds"),
                0.0, 512));
        try {
            return MAPPER.readTree(response.content());
        } catch (Exception e) {
            throw new IllegalStateException("模板出题返回的不是合法 JSON：" + response.content(), e);
        }
    }

    private static String questionOf(String userPrompt) {
        return nodeOf(userPrompt).path("question").asText();
    }

    @Test
    @DisplayName("真实提示词：问题点名项目，不带提示语")
    void namesFocusedProject() {
        String prompt = Prompts.questionUser(InterviewMode.PROJECT, InterviewStage.SINGLE_QUESTION,
                Difficulty.HARD, CONTEXT_LINE, List.of(), PROJECT);

        String question = questionOf(prompt);

        assertThat(question).contains(PROJECT);
        assertThat(question).doesNotContain("该项目");
        assertThat(question).doesNotContain("请只针对");
        assertThat(question).doesNotContain("（请");
    }

    @Test
    @DisplayName("旧格式兜底：项目名与提示语同一行也能清洗干净")
    void stripsInlineHintOnSameLine() {
        String prompt = Prompts.questionUser(InterviewMode.PROJECT, InterviewStage.SINGLE_QUESTION,
                Difficulty.HARD, CONTEXT_LINE, List.of(), PROJECT)
                // 模拟历史提示词格式：约束和项目名挤在同一行
                .replace("【当前聚焦项目】" + PROJECT + "\n", "【当前聚焦项目】" + PROJECT + "（请只针对这个项目提问，不要涉及其它项目）\n");

        String question = questionOf(prompt);

        assertThat(question).contains(PROJECT);
        assertThat(question).doesNotContain("请只针对");
    }

    @Test
    @DisplayName("没有聚焦行时退回检索上下文里的项目名")
    void fallsBackToContextProject() {
        String prompt = "【面试模式】项目面\n【当前阶段】单题作答\n【目标难度】HARD\n\n"
                + "【事实片段（唯一事实来源）】\n" + CONTEXT_LINE + "\n";

        String question = questionOf(prompt);

        assertThat(question).contains(PROJECT);
        assertThat(question).doesNotContain("该项目");
    }

    @Test
    @DisplayName("完全没有项目信息时使用中性占位，不崩不空")
    void usesNeutralPlaceholderWithoutAnyProject() {
        String question = questionOf("【面试模式】项目面\n【当前阶段】单题作答\n");

        assertThat(question).isNotBlank();
        assertThat(question).contains("该项目");
    }

    @Test
    @DisplayName("知识点上下文走知识题模板，不混入项目名")
    void knowledgeContextUsesKnowledgeTemplate() {
        String prompt = "【面试模式】八股面\n【当前阶段】单题作答\n【目标难度】MEDIUM\n\n"
                + "【事实片段（唯一事实来源）】\n[1] 知识点：Redis 分布式锁\nSETNX + 过期时间，注意锁续期。\n";

        String question = questionOf(prompt);

        assertThat(question).contains("Redis 分布式锁");
        assertThat(question).doesNotContain("在你的「");
    }

    @Test
    @DisplayName("同一场面试的题目按题序轮换，不会反复问同一句")
    void rotatesTemplatesAcrossQuestions() {
        List<String> questions = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String prompt = Prompts.questionUser(InterviewMode.PROJECT, InterviewStage.SINGLE_QUESTION,
                    Difficulty.HARD, CONTEXT_LINE, List.copyOf(asked), PROJECT);
            String question = questionOf(prompt);
            questions.add(question);
            asked.add(question);
        }

        assertThat(questions).doesNotHaveDuplicates();
        assertThat(questions).allSatisfy(q -> assertThat(q).contains(PROJECT));
    }

    @Test
    @DisplayName("题型标签跟着模板变，不出现「标边界题却在问量化结果」")
    void typeMatchesRotatedTemplate() {
        List<String> types = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String prompt = Prompts.questionUser(InterviewMode.PROJECT, InterviewStage.SINGLE_QUESTION,
                    Difficulty.HARD, CONTEXT_LINE, List.copyOf(asked), PROJECT);
            JsonNode node = nodeOf(prompt);
            String type = node.path("type").asText();
            String question = node.path("question").asText();

            assertThat(type).isIn("PROJECT_BOUNDARY", "PROJECT_TRADEOFF", "PROJECT_RESULT", "BEHAVIOR");
            // 量化结果题必须标成 PROJECT_RESULT，否则前端题型标签会误导用户
            if (question.contains("指标") || question.contains("具体数字")) {
                assertThat(type).isEqualTo("PROJECT_RESULT");
            }
            types.add(type);
            asked.add(question);
        }

        assertThat(types).contains("PROJECT_RESULT");
        assertThat(types.stream().distinct().count()).isGreaterThan(1L);
    }

    @Test
    @DisplayName("首题（没有已问列表）与有历史时给出不同题目")
    void firstQuestionDiffersFromFollowUpHistory() {
        String first = questionOf(Prompts.questionUser(InterviewMode.PROJECT, InterviewStage.SINGLE_QUESTION,
                Difficulty.HARD, CONTEXT_LINE, List.of(), PROJECT));
        String second = questionOf(Prompts.questionUser(InterviewMode.PROJECT, InterviewStage.SINGLE_QUESTION,
                Difficulty.HARD, CONTEXT_LINE, List.of(first), PROJECT));

        assertThat(first).isNotEqualTo(second);
    }
}
