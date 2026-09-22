package com.dusk4d.interview.rag;

import com.dusk4d.interview.domain.Difficulty;
import com.dusk4d.interview.domain.KnowledgeItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基础知识库（八股面的事实边界）。
 *
 * <p>加载顺序：先读 classpath 种子数据 {@code knowledge/knowledge-base.json}，
 * 再读可选的 {@code INTERVIEW_KNOWLEDGE_PATH} 外部文件（同 ID 覆盖、新 ID 追加），
 * 这样既能开箱即用，也方便扩展成自己的知识库。
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final String SEED_PATH = "knowledge/knowledge-base.json";

    private final Map<String, KnowledgeItem> items = new LinkedHashMap<>();
    private final List<String> topics = new ArrayList<>();
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;

    public KnowledgeBase(ObjectMapper objectMapper, VectorStore vectorStore) {
        this.objectMapper = objectMapper;
        this.vectorStore = vectorStore;
        int seeded = loadFromClasspath();
        int external = loadFromExternalPath(System.getenv("INTERVIEW_KNOWLEDGE_PATH"));
        index();
        log.info("知识库加载完成：种子 {} 条，外部 {} 条，主题 {}", seeded, external, topics);
    }

    /** 写入向量库：知识点按 topic+subtopic 作为元数据，便于八股面按主题过滤。 */
    private void index() {
        List<TextChunk> chunks = items.values().stream()
                .map(item -> TextChunk.knowledge(item.id(), item.topic(),
                        item.subtopic() == null ? item.topic() : item.subtopic(),
                        item.difficulty(), item.title(),
                        buildIndexText(item), keyPointsOf(item)))
                .toList();
        vectorStore.upsert(chunks);
    }

    private String buildIndexText(KnowledgeItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.title()).append('\n');
        if (item.subtopic() != null) {
            sb.append(item.subtopic()).append('\n');
        }
        sb.append(item.content() == null ? "" : item.content());
        if (item.referenceAnswer() != null) {
            sb.append('\n').append(item.referenceAnswer());
        }
        return sb.toString();
    }

    private List<String> keyPointsOf(KnowledgeItem item) {
        List<String> points = new ArrayList<>(item.keyPoints());
        points.addAll(item.commonMistakes());
        points.add(item.topic());
        if (item.subtopic() != null) {
            points.add(item.subtopic());
        }
        return points;
    }

    // ---------------------------------------------------------------- 查询接口

    public List<KnowledgeItem> all() {
        return List.copyOf(items.values());
    }

    public Optional<KnowledgeItem> byId(String id) {
        return Optional.ofNullable(items.get(id));
    }

    /** 全部主题（按出现顺序）。 */
    public List<String> topics() {
        return List.copyOf(topics);
    }

    public List<KnowledgeItem> byTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return all();
        }
        return items.values().stream()
                .filter(i -> i.topic().equalsIgnoreCase(topic))
                .toList();
    }

    public List<KnowledgeItem> byDifficulty(Difficulty difficulty) {
        if (difficulty == null) {
            return all();
        }
        return items.values().stream().filter(i -> i.difficulty() == difficulty).toList();
    }

    public int size() {
        return items.size();
    }

    /** 主题 -> 条目数统计，用于前端展示知识库覆盖情况。 */
    public Map<String, Integer> topicStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (KnowledgeItem item : items.values()) {
            stats.merge(item.topic(), 1, Integer::sum);
        }
        return stats;
    }

    // ---------------------------------------------------------------- 加载

    private int loadFromClasspath() {
        try (InputStream in = new ClassPathResource(SEED_PATH).getInputStream()) {
            return parse(in, "classpath:" + SEED_PATH);
        } catch (IOException e) {
            log.warn("知识库种子数据读取失败：{}", e.getMessage());
            return 0;
        }
    }

    private int loadFromExternalPath(String path) {
        if (path == null || path.isBlank()) {
            return 0;
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            log.warn("外部知识库文件不存在，已忽略：{}", path);
            return 0;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, path);
        } catch (IOException e) {
            log.warn("外部知识库读取失败：{}", e.getMessage());
            return 0;
        }
    }

    private int parse(InputStream in, String source) throws IOException {
        JsonNode root = objectMapper.readTree(in);
        JsonNode array = root.isArray() ? root : root.path("items");
        if (!array.isArray()) {
            log.warn("知识库格式不正确（缺少 items 数组）：{}", source);
            return 0;
        }
        List<Map<String, Object>> raw = objectMapper.convertValue(array, new TypeReference<>() {
        });
        int loaded = 0;
        for (Map<String, Object> entry : raw) {
            KnowledgeItem item = toItem(entry, loaded);
            if (item == null) {
                continue;
            }
            items.put(item.id(), item);
            String topic = item.topic();
            if (!topics.contains(topic)) {
                topics.add(topic);
            }
            loaded++;
        }
        return loaded;
    }

    private KnowledgeItem toItem(Map<String, Object> entry, int index) {
        String id = text(entry.get("id"));
        String title = text(entry.get("title"));
        String content = text(entry.get("content"));
        if (title == null || content == null) {
            log.warn("跳过缺少 title/content 的知识条目：{}", entry);
            return null;
        }
        if (id == null) {
            id = "knowledge-custom-" + index;
        }
        return new KnowledgeItem(
                id,
                text(entry.getOrDefault("topic", "Other")),
                text(entry.get("subtopic")),
                Difficulty.parse(text(entry.get("difficulty"))),
                title,
                content,
                text(entry.get("referenceAnswer")),
                stringList(entry.get("keyPoints")),
                stringList(entry.get("commonMistakes")));
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element != null) {
                    result.add(String.valueOf(element));
                }
            }
            return result;
        }
        return List.of();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
