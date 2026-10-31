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
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseManager {

    /** 向量库实例：由 RagVectorStoreConfig 按 studio.ai.vector-store-type 装配 */
    private final RagVectorStore ragVectorStore;

    private final AiProperties aiProperties;

    private final AiManager aiManager;

    /**
     * 切分正文
     *
     * @param content 正文（已 trim 且非空）；为 null 或纯空白时抛 A0401
     * @return 切分块列表，至少 1 块；顺序即原文顺序
     * @throws com.bhu.runshistudioweb.exception.BusinessException 正文为空（A0401）、切分结果为空（B0002）时抛出
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
     * @param segments 切分块，顺序即块序号；为 null 或空列表时抛 A0401
     * @return 与入参一一对应的向量条目 ID（会落进切分块表的 embedding_id 列）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 切分块为空（A0401）、向量化失败或返回数量不符（C0200）时抛出
     */
    public List<String> storeAll(List<TextSegment> segments) {
        ThrowUtils.throwIf(segments == null || segments.isEmpty(),
                ErrorCode.PARAMS_ERROR, "待入库的切分块为空");

        // 向量化（HTTP）
        List<Embedding> embeddings = aiManager.embedAll(
                segments.stream().map(TextSegment::text).toList());
        // 写入向量库，拿回条目 ID（生产是 Redis，开发/测试是内存）
        return ragVectorStore.addAll(embeddings, segments);
    }

    /**
     * 语义检索：把问题向量化后，在向量库里找最相似的若干块
     *
     * 必须用与入库相同的向量模型：换模型后新旧向量处在不同语义空间，相似度毫无意义，等于全库重建。
     *
     * @param question 用户提问（已 trim）；为 null 或空白时抛 A0401
     * @param topK 最多取回多少条（对应 studio.ai.rag-top-k）
     * @param minScore 相似度下限（对应 studio.ai.rag-min-score，越高越严格）
     * @return 命中列表（按相似度降序），可能为空，不会为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 问题为空（A0401）、向量化失败或结果为空（C0200）
     */
    public List<EmbeddingMatch<TextSegment>> search(String question, int topK, double minScore) {
        ThrowUtils.throwIf(StrUtil.isBlank(question), ErrorCode.PARAMS_ERROR, "检索问题不能为空");

        // 问题向量化：与入库走同一个 embedAll，保证同一套超时与失败收敛
        List<Embedding> queryEmbeddings = aiManager.embedAll(List.of(question));
        ThrowUtils.throwIf(queryEmbeddings == null || queryEmbeddings.isEmpty(),
                // 向量化由外部 AI 服务完成，返回空属于第三方服务异常 → C 类
                ErrorCode.AI_SERVICE_ERROR, "问题向量化结果为空");

        // 检索：maxResults 控制条数、minScore 过滤低质量命中，两个参数都来自配置
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
     * @return 向量库实例（与业务用的是同一个对象，不是副本）
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return ragVectorStore;
    }
}
