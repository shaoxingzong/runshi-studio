package com.bhu.runshistudioweb.manager;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * RAG 向量库抽象
 *
 * <p>为什么要有这一层：业务代码只需要「存向量、删向量、按向量检索」三件事，
 * 不应该知道底层是进程内内存还是 Redis。生产环境必须能持久化并与多实例共享，
 * 开发/测试则希望不依赖 Redis 的 Query Engine/JSON 模块——两个实现切换时，
 * 上层 KnowledgeBaseManager 与 KnowledgeDocService 一行都不用改。
 *
 * <p>继承 LangChain4j 的 {@link EmbeddingStore} 而不是另起一套接口：
 * 切分、批量向量化、检索请求都已经是 LangChain4j 的类型（Embedding / TextSegment /
 * EmbeddingSearchRequest），自己再包一层只会增加转换成本。这里只补三个
 * EmbeddingStore 没有、但运维必须知道的能力：数量、清空、是否需要重建。
 *
 * @author shaoshing
 */
public interface RagVectorStore extends EmbeddingStore<TextSegment> {

    /**
     * 当前索引中的向量条数
     *
     * <p>用于启动健康检查：与 studio_knowledge_chunk 的存活行数比对，
     * 数量不一致说明索引缺失/残留，需要重建。它只回答「有多少条」，
     * 不保证每条都能反查到块——那是检索层用「回查为空就跳过」兜底的事。
     *
     * @return 向量条数；空库返回 0，不会返回负数
     */
    int count();

    /**
     * 清空向量索引
     *
     * <p>只在「索引与数据库不一致」「向量模型变更」时调用。清空后必须重建，
     * 否则检索会一直为空。实现方应保证清空是幂等的：索引不存在时也不报错。
     */
    void reset();

    /**
     * 索引是否需要重建
     *
     * <p>典型场景：启动时发现持久化索引里的向量模型与当前配置不一致。
     * 这个标记只影响启动健康检查，不参与每次检索判断。
     *
     * @return true 表示调用方应执行 reset + reindex
     */
    boolean needsRebuild();
}
