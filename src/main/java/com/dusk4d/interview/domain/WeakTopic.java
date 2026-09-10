package com.dusk4d.interview.domain;

import java.util.List;

/**
 * 知识缺口 / 能力薄弱点。
 *
 * @param topic    主题
 * @param reason   为什么判定为薄弱（概念错误 / 边界遗漏 / 无证据强主张）
 * @param evidence 证据（用户原话片段或评分依据）
 */
public record WeakTopic(
        String topic,
        String reason,
        List<String> evidence
) {
    public WeakTopic {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
