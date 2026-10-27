package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库管理器：把「一段正文」变成「N 个切分块 + N 个向量」
 *
 * 只做三件事：切分、向量入库、向量清理。本类没有任何数据库访问，因此每个方法都可以在事务之外
 * 安全调用，事务边界由 Service 掌握。
 *
 * 向量库由 {@link RagVectorStore} 决定：生产用 Redis（持久化、多实例共享），开发/测试用内存实现。
 * 不用 EmbeddingStoreIngestor 是因为它不返回 embeddingId，而切分块表必须落这个 ID。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseManager {

    /**
     * 向量库实例：由 RagVectorStoreConfig 按 studio.ai.vector-store-type 装配
     *
     * 依赖接口而非具体实现：生产切 Redis 时本类一行不用改，测试用内存实现也不需要 Redis 模块。
     */
    private final RagVectorStore ragVectorStore;

    private final AiProperties aiProperties;

    private final AiManager aiManager;

    /**
     * 切分正文
     *
     * 用递归切分而不是固定长度硬切：先按段落、再按句子、最后才按字符，尽量在语义边界上断开。
     * 硬切会把一句话劈成两半，检索命中后拼给模型的是残缺语义，表现为「资料明明有，回答却答非所问」。
     *
     * 坑：块大小与重叠都来自配置，重叠若被配成大于等于块大小，会切出大量几乎相同的块，
     * 向量化费用成倍增长而检索质量不升反降。
     *
     * @param content 正文（已 trim 且非空）；为 null 或纯空白时抛 A0401
     * @return 切分块列表，至少 1 块；顺序即原文顺序
     * @throws com.bhu.runshistudioweb.exception.BusinessException 正文为空（A0401）、
     * 切分结果为空（B0002）时抛出
     */
    public List<TextSegment> split(String content) {
        ThrowUtils.throwIf(StrUtil.isBlank(content), ErrorCode.PARAMS_ERROR, "正文不能为空");

        Document document = Document.from(content);
        DocumentSplitter splitter = DocumentSplitters.recursive(
                aiProperties.getChunkMaxSize(), aiProperties.getChunkOverlap());
        List<TextSegment> segments = splitter.split(document);

        // 理论上不会为空（正文非空就至少一块），兜一层避免下游出现"0 块但文档已索引"的怪状态
        ThrowUtils.throwIf(segments.isEmpty(), ErrorCode.OPERATION_ERROR, "正文切分结果为空");
        return segments;
    }

    /**
     * 把切分块向量化并写入向量库
     *
     * 先向量化（外部 HTTP，可能失败）再入库（本地操作，几乎不会失败）：顺序不能反，
     * 先拿到全部向量再入库，才能在向量化失败时明确知道「向量库里没有任何脏数据」。
     *
     * 坑：返回的 ID 列表长度必须与入参块数一致。一旦错位，检索会命中张冠李戴的内容，
     * 而且极难排查——看起来一切正常，就是答非所问。
     *
     * @param segments 切分块，顺序即块序号；为 null 或空列表时抛 A0401
     * @return 与入参一一对应的向量条目 ID（会落进切分块表的 embedding_id 列）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 切分块为空（A0401）、
     * 向量化失败或返回数量不符（C0200）时抛出
     */
    public List<String> storeAll(List<TextSegment> segments) {
        ThrowUtils.throwIf(segments == null || segments.isEmpty(),
                ErrorCode.PARAMS_ERROR, "待入库的切分块为空");

        // ① 向量化（HTTP）
        List<Embedding> embeddings = aiManager.embedAll(
                segments.stream().map(TextSegment::text).toList());
        // ② 写入向量库，拿回条目 ID（生产是 Redis，开发/测试是内存）
        return ragVectorStore.addAll(embeddings, segments);
    }

    /**
     * 语义检索：把问题向量化后，在向量库里找最相似的若干块
     *
     * 本方法不含任何数据库访问：命中结果里只有向量条目 ID 与块正文，「这块属于哪篇文档」由 Service 回查。
     *
     * 必须用与入库相同的向量模型：换模型后新旧向量处在不同语义空间，相似度毫无意义，等于全库重建。
     *
     * 坑：minScore 的口径不是余弦相似度，而是 (1 + 余弦) / 2，0.5 对应「正交」（毫不相关）。
     *
     * @param question 用户提问（已 trim）；为 null 或空白时抛 A0401
     * @param topK 最多取回多少条（对应 studio.ai.rag-top-k）
     * @param minScore 相似度下限（对应 studio.ai.rag-min-score，越高越严格）
     * @return 命中列表（按相似度降序），可能为空，不会为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 问题为空（A0401）、向量化失败或结果为空（C0200）
     */
    public List<EmbeddingMatch<TextSegment>> search(String question, int topK, double minScore) {
        ThrowUtils.throwIf(StrUtil.isBlank(question), ErrorCode.PARAMS_ERROR, "检索问题不能为空");

        // ① 问题向量化：与入库走同一个 embedAll，保证同一套超时与失败收敛
        List<Embedding> queryEmbeddings = aiManager.embedAll(List.of(question));
        ThrowUtils.throwIf(queryEmbeddings == null || queryEmbeddings.isEmpty(),
                // 向量化由外部 AI 服务完成，返回空属于第三方服务异常 → C 类
                ErrorCode.AI_SERVICE_ERROR, "问题向量化结果为空");

        // ② 检索：maxResults 控制条数、minScore 过滤低质量命中，两个参数都来自配置
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbeddings.get(0))
                .maxResults(topK)
                .minScore(minScore)
                .build();
        return ragVectorStore.search(searchRequest).matches();
    }

    /**
     * 删除向量（尽力而为：失败只告警，不影响主流程）
     *
     * 调用它的场景都是「新数据已经就位、旧向量该清了」，此时检索已经能用，
     * 残留几个旧向量只是占点内存，为这点残留让整个重建接口失败是得不偿失的。
     *
     * 坑：本方法吞异常，调用方不能靠它判断「向量真的被清掉了」。这正是检索层必须容忍
     * 「命中向量但回查不到块」的原因——兜底责任在检索层，不在本方法。
     *
     * @param embeddingIds 待清理的向量条目 ID；为 null 或空列表时什么都不做
     */
    public void removeVectors(List<String> embeddingIds) {
        if (embeddingIds == null || embeddingIds.isEmpty()) {
            return;
        }
        try {
            ragVectorStore.removeAll(embeddingIds);
        } catch (Exception e) {
            log.warn("清理旧向量失败（best-effort，不影响本次结果）| 数量={}", embeddingIds.size(), e);
        }
    }

    /**
     * 暴露向量库实例（主要供测试与运维核对）
     *
     * 坑：返回的是内部持有的引用而不是拷贝，拿到它的人可以清空整个库；
     * 业务代码不要用它绕开 {@link #search}——绕开就意味着绕开了「问题向量化」这一步，
     * 而向量库只能拿向量去查，直接传文本查不到东西。
     *
     * @return 向量库实例（与业务用的是同一个对象，不是副本）
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return ragVectorStore;
    }
}
