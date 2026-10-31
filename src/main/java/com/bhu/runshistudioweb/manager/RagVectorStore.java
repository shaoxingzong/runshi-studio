package com.bhu.runshistudioweb.manager;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * RAG 向量库抽象
 *
 * @author shaoshing
 */
public interface RagVectorStore extends EmbeddingStore<TextSegment> {

    /**
     * 当前索引中的向量条数
     *
     * @return 向量条数；空库返回 0，不会返回负数
     */
    int count();

    /** 清空向量索引 */
    void reset();

    /**
     * 索引是否需要重建
     *
     * @return true 表示调用方应执行 reset + reindex
     */
    boolean needsRebuild();
}
