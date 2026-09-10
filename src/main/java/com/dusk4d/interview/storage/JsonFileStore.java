package com.dusk4d.interview.storage;

import com.fasterxml.jackson.databind.JavaType;
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
 * <p><b>实现注意（曾经踩过的坑）</b>：这里必须使用调用方传入的具体 {@link JavaType}，
 * 不能用匿名 {@code TypeReference<List<T>>}。后者在构造器里创建时 {@code T} 仍是类型变量，
 * 运行时被解析成 {@code List<Object>}，读回来是一堆 {@code LinkedHashMap}，
 * 随后在强转时抛 ClassCastException，表现为「每次重启数据都被判定为损坏」。
 *
 * <p>隐私：文件只写清洗并脱敏后的文本，原始简历文件与联系方式不落盘。
 */
public class JsonFileStore<T> extends InMemoryStore<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final Path file;
    private final ObjectMapper objectMapper;
    private final JavaType listType;

    public JsonFileStore(Path file, JavaType listType, ObjectMapper objectMapper,
                         java.util.function.Function<T, String> idExtractor, Comparator<T> order) {
        super(idExtractor, order);
        this.file = file;
        this.listType = listType;
        this.objectMapper = objectMapper;
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
            List<T> entities = objectMapper.readValue(json, listType);
            loadAll(entities);
            log.info("已从 {} 载入 {} 条记录", file.getFileName(), count());
        } catch (IOException | RuntimeException e) {
            // 数据文件确实损坏时不应阻止启动：保留现场并改名，从空集合继续
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
