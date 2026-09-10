package com.dusk4d.interview.config;

import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.rag.TextChunk;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.storage.InterviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时重建检索索引。
 *
 * <p>向量库位于内存（{@link com.dusk4d.interview.rag.InMemoryVectorStore}），
 * 但简历事实已经持久化。如果不重建索引，重启后项目面检索会全部为空，
 * 只能走「回退到事实原文」的降级路径，检索质量与来源引用都会退化。
 * 因此这里在启动阶段把已持久化的简历事实重新写回向量库。
 *
 * <p>幂等：写入按 ID 覆盖，重复启动不会产生重复片段。
 */
@Component
public class VectorIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexInitializer.class);

    private final InterviewRepository repository;
    private final VectorStore vectorStore;

    public VectorIndexInitializer(InterviewRepository repository, VectorStore vectorStore) {
        this.repository = repository;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Resume> resumes = repository.listResumes();
        if (resumes.isEmpty()) {
            log.info("没有已持久化的简历，跳过检索索引重建。");
            return;
        }
        int indexed = 0;
        for (Resume resume : resumes) {
            List<TextChunk> chunks = new ArrayList<>();
            for (ResumeFact fact : resume.facts()) {
                if (fact.content() == null || fact.content().isBlank()) {
                    continue;
                }
                chunks.add(TextChunk.resumeFact(fact.id(), resume.id(), fact.type(), fact.label(),
                        fact.type() == FactType.PROJECT ? fact.label() : null,
                        fact.content(), fact.sourceOrder(), fact.metadata()));
            }
            if (!chunks.isEmpty()) {
                vectorStore.upsert(chunks);
                indexed += chunks.size();
            }
        }
        log.info("检索索引重建完成：{} 份简历，{} 个片段（向量库共 {} 个片段）",
                resumes.size(), indexed, vectorStore.size());
    }
}
