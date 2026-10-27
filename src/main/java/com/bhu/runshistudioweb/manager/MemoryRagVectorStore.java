package com.bhu.runshistudioweb.manager;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/**
 * 内存向量库实现（开发/测试默认）
 *
 * <p>它就是改造前 KnowledgeBaseManager 里的那个 InMemoryEmbeddingStore，
 * 行为完全一致：进程内、重启即丢。包一层的原因只有一个——让上层依赖
 * {@link RagVectorStore} 而不是具体实现，生产切 Redis 时不用改业务代码。
 *
 * <p>为什么测试固定用它：测试上下文每次启动都是空库，
 * 不能依赖外部 Redis 的 Query Engine/JSON；同时避免测试之间共享索引互相污染。
 *
 * @author shaoshing
 */
@Slf4j
public class MemoryRagVectorStore implements RagVectorStore {

    /** 真正干活的 LangChain4j 内存实现；本类只补 count/reset/needsRebuild */
    private final InMemoryEmbeddingStore<TextSegment> delegate = new InMemoryEmbeddingStore<>();

    @Override
    public String add(Embedding embedding) {
        return delegate.add(embedding);
    }

    @Override
    public void add(String id, Embedding embedding) {
        delegate.add(id, embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        return delegate.add(embedding, embedded);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return delegate.addAll(embeddings);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        delegate.addAll(ids, embeddings, embedded);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        delegate.removeAll(ids);
    }

    @Override
    public void removeAll(Filter filter) {
        delegate.removeAll(filter);
    }

    @Override
    public void removeAll() {
        delegate.removeAll();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return delegate.search(request);
    }

    @Override
    public int count() {
        return delegate.size();
    }

    @Override
    public void reset() {
        delegate.removeAll();
        log.info("内存向量库已清空（等待重建）");
    }

    @Override
    public boolean needsRebuild() {
        // 内存库没有「持久化模型元数据」可比对，每次启动为空属于预期状态
        return false;
    }
}
