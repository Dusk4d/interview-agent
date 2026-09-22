package com.dusk4d.interview.storage;

import java.util.List;
import java.util.Optional;

/**
 * 通用仓储接口。
 *
 * <p>第一版有内存与本地文件（JSON）两种实现：本地单用户演示不应该被数据库安装成本劝退，
 * 但同时保留可替换的抽象，后续接 PostgreSQL/pgvector 时上层代码无需改动。
 */
public interface Store<T> {

    /** 保存或覆盖。 */
    T save(T entity);

    Optional<T> findById(String id);

    List<T> findAll();

    boolean deleteById(String id);

    int count();

    void clear();
}
