package com.bhu.runshistudioweb.manager;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.json.Path2;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis 向量库实现（生产）
 *
 * 用 Redis 而不是另引向量数据库：项目本就有 Redis，8.4 的 Query Engine 原生支持向量索引，
 * 且持久化后重启与多实例部署都不丢向量。
 *
 * 维度是懒确定的：Redis 建索引必须给维度，而不同向量模型维度不同（1024/1536/2048），
 * 与其在配置里写死一个容易错的数字，不如第一次 addAll 时从 Embedding.dimension() 取。
 *
 * 坑：本类只管向量索引，不管数据库里的 chunk/doc 行。清空索引后必须调
 * KnowledgeDocService.reindexAll() 重新嵌入，否则检索一直为空。
 *
 * @author shaoshing
 */
@Slf4j
public class RedisRagVectorStore implements RagVectorStore {

    /** Redis 向量索引名：固定值，便于运维用 FT.INFO 直接查 */
    private static final String INDEX_NAME = "studio-rag-chunks";

    /** JSON key 前缀：必须与索引前缀一致，且以冒号结尾 */
    private static final String KEY_PREFIX = "rag:chunk:";

    /** 持久化索引使用的向量模型名：用于检测模型变更 */
    private static final String META_MODEL = "rag:meta:model";

    /** 持久化索引的向量维度：只用于运维核对，不参与建索引决策 */
    private static final String META_DIMENSION = "rag:meta:dimension";

    private final UnifiedJedis jedis;

    private final String embeddingModel;

    /** 创建/重置索引时串行化：避免启动健康检查与入库同时改索引结构 */
    private final ReentrantLock indexLock = new ReentrantLock();

    /** 懒创建的 LangChain4j Redis 实现；索引不存在时为 null */
    private volatile RedisEmbeddingStore delegate;

    /** 模型变更/索引被清空时置 true，由启动健康检查触发重建 */
    private volatile boolean needsRebuild;

    public RedisRagVectorStore(UnifiedJedis jedis, String embeddingModel) {
        this.jedis = jedis;
        this.embeddingModel = embeddingModel;
        init();
    }

    /**
     * 启动检查：索引存在时绑定现有索引，并核对模型元数据
     */
    private void init() {
        indexLock.lock();
        try {
            // 先做模块探针：Query Engine 缺失会在 ftList 报错；JSON 缺失会在 jsonSet 报错。
            // 生产环境希望这类配置问题在启动期暴露，而不是等第一次入库才失败
            verifyRedisModules();
            if (!indexExists()) {
                log.info("RAG 向量索引尚未创建，将在首次入库时按实际向量维度建立 | index={}", INDEX_NAME);
                return;
            }
            String persistedModel = jedis.get(META_MODEL);
            if (persistedModel != null && !persistedModel.equals(embeddingModel)) {
                log.warn("RAG 向量模型变更 | 索引中的模型={} | 当前配置={} | 将清空索引并重新嵌入",
                        persistedModel, embeddingModel);
                resetInternal();
                return;
            }
            // 索引已存在时不需要维度：RedisEmbeddingStore 会直接绑定它
            delegate = createDelegate(null);
            if (persistedModel == null) {
                log.warn("RAG 索引存在但缺少模型元数据，按当前模型继续使用 | model={}", embeddingModel);
                jedis.set(META_MODEL, embeddingModel);
            }
            log.info("RAG 向量索引已加载 | index={} | vectors={} | model={}",
                    INDEX_NAME, count(), embeddingModel);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Redis 向量库初始化失败：请确认 Redis 为 8.4+ 且包含 Query Engine 与 JSON 模块；"
                            + "本地开发可设置 VECTOR_STORE_TYPE=memory", e);
        } finally {
            indexLock.unlock();
        }
    }

    @Override
    public String add(Embedding embedding) {
        String id = java.util.UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        delegateForAdd(embedding.dimension()).add(id, embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        // LangChain4j 1.20.2 的 EmbeddingStore 没有 add(id, embedding, embedded) 重载，
        // 单条带文本的写入走 addAll 的单元素列表，语义与返回 ID 的方式保持一致
        return delegateForAdd(embedding.dimension()).add(embedding, embedded);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        return delegateForAdd(embeddings.get(0).dimension()).addAll(embeddings);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        if (ids == null || ids.isEmpty() || embeddings == null || embeddings.isEmpty()) {
            return;
        }
        delegateForAdd(embeddings.get(0).dimension()).addAll(ids, embeddings, embedded);
        jedis.set(META_DIMENSION, String.valueOf(embeddings.get(0).dimension()));
    }

    @Override
    public void removeAll(Collection<String> ids) {
        RedisEmbeddingStore store = existingDelegate();
        if (store != null) {
            store.removeAll(ids);
        }
    }

    @Override
    public void removeAll(Filter filter) {
        RedisEmbeddingStore store = existingDelegate();
        if (store != null) {
            store.removeAll(filter);
        }
    }

    @Override
    public void removeAll() {
        RedisEmbeddingStore store = existingDelegate();
        if (store != null) {
            store.removeAll();
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        RedisEmbeddingStore store = existingDelegate();
        if (store == null) {
            // 索引还没建（知识库为空）：返回空结果，让调用方自然走「无资料」分支
            return new EmbeddingSearchResult<>(List.of());
        }
        return store.search(request);
    }

    @Override
    public int count() {
        if (!indexExists()) {
            return 0;
        }
        Map<String, Object> info = jedis.ftInfo(INDEX_NAME);
        Object numDocs = info == null ? null : info.get("num_docs");
        if (numDocs instanceof Number number) {
            return number.intValue();
        }
        if (numDocs != null) {
            try {
                return Integer.parseInt(String.valueOf(numDocs));
            } catch (NumberFormatException e) {
                log.warn("无法解析 Redis 索引 num_docs | value={}", numDocs);
            }
        }
        return 0;
    }

    @Override
    public void reset() {
        indexLock.lock();
        try {
            resetInternal();
        } catch (Exception e) {
            throw new IllegalStateException("清空 Redis 向量索引失败 | index=" + INDEX_NAME, e);
        } finally {
            indexLock.unlock();
        }
    }

    @Override
    public boolean needsRebuild() {
        return needsRebuild;
    }

    @PreDestroy
    public void close() {
        jedis.close();
    }

    // ==================== 私有工具 ====================

    /**
     * 取得用于写入的 delegate：索引不存在时按真实维度创建
     */
    private RedisEmbeddingStore delegateForAdd(int dimension) {
        RedisEmbeddingStore current = delegate;
        if (current != null) {
            return current;
        }
        indexLock.lock();
        try {
            if (delegate == null) {
                if (indexExists()) {
                    delegate = createDelegate(null);
                } else {
                    delegate = createDelegate(dimension);
                    jedis.set(META_MODEL, embeddingModel);
                    jedis.set(META_DIMENSION, String.valueOf(dimension));
                    log.info("RAG 向量索引已创建 | index={} | dimension={} | model={}",
                            INDEX_NAME, dimension, embeddingModel);
                }
            }
            return delegate;
        } finally {
            indexLock.unlock();
        }
    }

    /**
     * 取得只读/删除用的 delegate；索引不存在时返回 null
     */
    private RedisEmbeddingStore existingDelegate() {
        RedisEmbeddingStore current = delegate;
        if (current != null) {
            return current;
        }
        if (!indexExists()) {
            return null;
        }
        indexLock.lock();
        try {
            if (delegate == null) {
                delegate = createDelegate(null);
            }
            return delegate;
        } finally {
            indexLock.unlock();
        }
    }

    private RedisEmbeddingStore createDelegate(Integer dimension) {
        return RedisEmbeddingStore.builder()
                .unifiedJedis(jedis)
                .indexName(INDEX_NAME)
                .prefix(KEY_PREFIX)
                .dimension(dimension)
                .build();
    }

    private boolean indexExists() {
        return jedis.ftList().contains(INDEX_NAME);
    }

    /**
     * 启动探针：确认 Redis 同时具备 Query Engine 与 JSON
     *
     * <p>不能只查 ftList：Query Engine 在、JSON 不在时，索引创建与写入仍会失败，
     * 而且会拖到第一次 sync 才暴露。这里写一个临时 JSON key 再删掉，成本只有两条命令。
     */
    private void verifyRedisModules() {
        String probeKey = "rag:health:probe";
        try {
            jedis.ftList();
            jedis.jsonSet(probeKey, Path2.ROOT_PATH, "{}");
        } finally {
            // 探针失败时也尝试清理，避免留下垃圾 key；清理失败不影响主流程
            try {
                jedis.del(probeKey);
            } catch (Exception ignored) {
                // Redis 已经不可用时，这条清理失败没有额外价值
            }
        }
    }

    private void resetInternal() {
        if (indexExists()) {
            // ftDropIndexDD = drop index + delete documents（DD），一次命令清理干净
            jedis.ftDropIndexDD(INDEX_NAME);
        }
        jedis.del(META_MODEL, META_DIMENSION);
        delegate = null;
        needsRebuild = true;
        log.warn("RAG 向量索引已清空，等待重建 | index={}", INDEX_NAME);
    }
}
