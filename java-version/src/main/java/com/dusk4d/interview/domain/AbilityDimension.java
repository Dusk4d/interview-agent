package com.dusk4d.interview.domain;

import java.util.List;

/**
 * 面试报告中的能力维度（雷达图数据）。
 *
 * @param key    维度标识
 * @param label  维度名称（项目表达 / Java 基础 / 数据库 / 中间件 / AI 应用 ...）
 * @param score  0~5 加权得分
 * @param sample 该维度的样本量（回答数），样本为 0 时前端应标注「暂无数据」
 */
public record AbilityDimension(
        String key,
        String label,
        double score,
        int sample
) {
}
