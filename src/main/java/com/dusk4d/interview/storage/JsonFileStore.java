package com.dusk4d.interview.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 本地 JSON 文件仓储（默认模式）。
 *
 * <p>写策略：每次变更把整个集合原子写回（临时文件 + move），保证进程被强杀时不会留下半截 JSON。
 * 数据量级是「个人练习记录」（几十份简历、几百场会话），全量写回完全够用，
 * 换来的是零依赖、可备份、可直接阅读的数据文件。
 *
 * <p>隐私：文件只写清洗并脱敏后的文本，原始简历文件与联系方式不落盘。
 */
public class JsonFileStore<T> extends InMemoryStore<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final Path file;
    private final ObjectMapper objectMapper;
    private final TypeReference<List<T>> typeRef;

    public JsonFileStore(Path file, Class<T> type, ObjectMapper objectMapper,
                         java.util.function.Function<T, String> idExtractor, Comparator<T> order) {
        super(idExtractor, order);
        this.file = file;
        this.objectMapper = objectMapper;
        this.typeRef = new TypeReference<>() {
        };
        load();
    }

    @Override
    public synchronized T save(T entity) {
        T saved = super.save(entity);
        flush();
        return saved;
    }

    @Override
    public synchronized boolean deleteById(String id) {
        boolean removed = super.deleteById(id);
        if (removed) {
            flush();
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        super.clear();
        flush();
    }

    /** 数据文件路径（用于诊断与备份提示）。 */
    public Path file() {
        return file;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            List<T> entities = objectMapper.readValue(json, typeRef);
            loadAll(entities);
            log.info("已从 {} 载入 {} 条记录", file.getFileName(), count());
        } catch (IOException | RuntimeException e) {
            // 数据文件损坏不应导致应用无法启动：保留现场并改名，从空集合继续
            log.warn("数据文件读取失败（将忽略并重建）：{} - {}", file, e.getMessage());
            quarantine();
        }
    }

    private void flush() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), snapshot().values());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入数据文件失败：" + file, e);
        }
    }

    private void quarantine() {
        try {
            Path broken = file.resolveSibling(file.getFileName() + ".corrupt");
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已将损坏的数据文件移动到 {}", broken);
        } catch (IOException ignored) {
            // 无法移动时直接放弃，不影响服务启动
        }
    }

    /** 供监控/健康检查使用的统计信息。 */
    public Map<String, Object> stats() {
        return Map.of(
                "type", "json-file",
                "file", file.toString(),
                "records", count());
    }
}
