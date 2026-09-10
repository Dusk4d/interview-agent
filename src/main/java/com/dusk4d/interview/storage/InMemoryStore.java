package com.dusk4d.interview.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内仓储实现。
 *
 * <p>特点：无外部依赖、线程安全、读写 O(1)。用于 {@code app.storage.mode=memory}
 * 与单元测试；进程重启后数据丢失，因此不作为默认模式。
 *
 * @param <T> 实体类型
 */
public class InMemoryStore<T> implements Store<T> {

    private final Map<String, T> data = new ConcurrentHashMap<>();
    private final java.util.function.Function<T, String> idExtractor;
    private final Comparator<T> order;

    public InMemoryStore(java.util.function.Function<T, String> idExtractor) {
        this(idExtractor, null);
    }

    public InMemoryStore(java.util.function.Function<T, String> idExtractor, Comparator<T> order) {
        this.idExtractor = idExtractor;
        this.order = order;
    }

    @Override
    public T save(T entity) {
        String id = idExtractor.apply(entity);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("实体缺少 ID，无法保存：" + entity.getClass().getSimpleName());
        }
        data.put(id, entity);
        return entity;
    }

    @Override
    public Optional<T> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(data.get(id));
    }

    @Override
    public List<T> findAll() {
        List<T> all = new ArrayList<>(data.values());
        if (order != null) {
            all.sort(order);
        }
        return all;
    }

    @Override
    public boolean deleteById(String id) {
        return id != null && data.remove(id) != null;
    }

    @Override
    public int count() {
        return data.size();
    }

    @Override
    public void clear() {
        data.clear();
    }

    /** 供子类（文件存储）复用：按写入顺序导出快照。 */
    protected Map<String, T> snapshot() {
        return new LinkedHashMap<>(data);
    }

    /** 供子类（文件存储）复用：批量装载。 */
    protected void loadAll(List<T> entities) {
        data.clear();
        for (T entity : entities) {
            String id = idExtractor.apply(entity);
            if (id != null && !id.isBlank()) {
                data.put(id, entity);
            }
        }
    }
}
