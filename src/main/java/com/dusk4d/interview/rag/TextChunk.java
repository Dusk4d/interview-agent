package com.dusk4d.interview.rag;

import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.FactType;

import java.util.List;

/**
 * 可检索片段：简历事实或基础知识条目在向量库中的统一表示。
 *
 * <p>元数据最少包含：来源类型（简历/知识库）、简历 ID、区块类型、项目名称、来源顺序。
 * 检索时按元数据过滤，反馈时按元数据溯源。
 *
 * @param id            片段 ID（简历事实用 factId；知识点用 knowledgeId）
 * @param kind          来源：RESUME_FACT / KNOWLEDGE
 * @param resumeId      简历 ID（知识库条目为 null）
 * @param factType      区块类型
 * @param topic         知识库主题或项目名（用于元数据过滤与展示）
 * @param label         展示标签
 * @param projectName   所属项目名（简历事实可能为 null）
 * @param difficulty    难度（知识库条目）
 * @param text          片段正文
 * @param sourceOrder   来源顺序
 * @param extra         其它可检索文本（技术栈、参考要点等），参与向量化但不直接展示
 */
public record TextChunk(
        String id,
        ChunkKind kind,
        String resumeId,
        FactType factType,
        String topic,
        String label,
        String projectName,
        Difficulty difficulty,
        String text,
        int sourceOrder,
        List<String> extra
) {
    public TextChunk {
        extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public static TextChunk resumeFact(String id, String resumeId, FactType factType, String label,
                                       String projectName, String text, int sourceOrder, List<String> extra) {
        return new TextChunk(id, ChunkKind.RESUME_FACT, resumeId, factType, projectName,
                label, projectName, null, text, sourceOrder, extra);
    }

    public static TextChunk knowledge(String id, String topic, String subtopic, Difficulty difficulty,
                                      String title, String text, List<String> keyPoints) {
        return new TextChunk(id, ChunkKind.KNOWLEDGE, null, FactType.KNOWLEDGE, subtopic,
                title, null, difficulty, text, 0, keyPoints);
    }

    /** 参与向量化的完整文本：正文 + 附加关键词（技术栈/参考要点）。 */
    public String embeddingText() {
        if (extra == null || extra.isEmpty()) {
            return text == null ? "" : text;
        }
        return (text == null ? "" : text) + "\n" + String.join(" ", extra);
    }

    /** 检索来源标识，形如 "简历片段：FinanceAgent" 或 "知识点：Redis 分布式锁"。 */
    public String citation() {
        if (kind == ChunkKind.KNOWLEDGE) {
            return "知识点：" + label;
        }
        String name = projectName != null && !projectName.isBlank() ? projectName : label;
        return "简历片段：" + (name == null || name.isBlank() ? "未命名区块" : name);
    }
}
