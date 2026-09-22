package com.dusk4d.interview.agent;

import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.QuestionType;

import java.util.List;

/**
 * 出题结果（模型输出经校验后的领域对象）。
 *
 * @param type          题型
 * @param difficulty    难度
 * @param content       问题正文
 * @param intent        考察意图
 * @param focus         考察点
 * @param followUpPlan  追问预案
 * @param sourceIds     问题所依据的事实来源（简历片段 / 知识点 ID）
 */
public record QuestionPlan(
        QuestionType type,
        Difficulty difficulty,
        String content,
        String intent,
        List<String> focus,
        String followUpPlan,
        List<String> sourceIds
) {
    public QuestionPlan {
        focus = focus == null ? List.of() : List.copyOf(focus);
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
