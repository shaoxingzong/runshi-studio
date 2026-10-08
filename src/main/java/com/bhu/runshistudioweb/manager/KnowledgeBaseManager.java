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
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库管理器：把「一段正文」变成「N 个切分块 + N 个向量」
 *
 * author: shaoshing
 *
 * <p>本类只做三件事：<b>切分</b>、<b>向量入库</b>、<b>向量清理</b>。
 * 数据库那两行（doc / chunk）由 Service 负责——这样划分是为了让
 * 「纯内存的计算」与「落库的事务」分明，Service 才能清楚地控制事务边界。
 *
 * <p><b>向量库选型：本期是 {@code InMemoryEmbeddingStore}（进程内内存，重启丢失）。</b>
 * 这是一个明确的、有代价的决定，请连同代价一起读：
 * <ul>
 *     <li><b>代价</b>：应用重启后，库里的 chunk 行还在，但向量没了，检索会查不到任何东西。</li>
 *     <li><b>为什么本期还这么选</b>：换 Redis / PGVector / Milvus 只是换一个
 *     {@link EmbeddingStore} 实现（换一行构造代码），但会引入一个<b>必须单独运维的组件</b>。
 *     在「检索刚上线、知识库量级还很小」的前提下，先不背这个运维成本更合理；</li>
 *     <li><b>补救</b>：重启后调 {@code POST /knowledge/doc/reindex-all}
 *     即可恢复。⚠️ 注意 <b>{@code sync / sync-all} 此时会全部 skipped，救不回来</b>
 *     （内容哈希没变，它们什么都不会做）——这是最容易踩的坑；
 *     检索层已能容忍"向量为空"（反查为空即跳过）。</li>
 * </ul>
 * 这条事实已登记在 db/DESIGN.md 场景 I。
 *
 * <p><b>为什么不用 {@code EmbeddingStoreIngestor}</b>：它提供一条龙的
 * {@code ingest(document)}（切分 + 向量化 + 入库），很省事，
 * 但它<b>不返回 embeddingId</b>——而我们的 chunk 表必须落 {@code embedding_id}
 * （场景 H 的回查就是靠它把「命中的向量」翻译成「块的正文」）。
 * 拿不到 ID，向量和块就断了联系。所以这里手写「split → embed → addAll」三步。
 *
 * <p><b>切块时不写元数据</b>（不把 docId、sourceId 之类的信息塞进
 * {@code TextSegment.metadata()}）：回查一律走数据库（chunk 行里有完整信息），
 * 保持<b>单一事实源</b>——不然同一份信息存两处，迟早不一致，
 * 而向量库里的旧数据还特别难修。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseManager {

    /**
     * 内存向量库：进程内内存，<b>重启丢失</b>（理由见类注释）
     *
     * <p>用 final 字段持有单例：整个应用共用一份，否则各建各的等于数据分散
     */
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

    private final AiProperties aiProperties;

    private final AiManager aiManager;

    /**
     * 切分正文
     *
     * <p>用 {@code recursive}（递归切分）而不是固定长度硬切：它会先按段落、
     * 再按句子、最后按字符逐级尝试，尽量在<b>语义边界</b>上断开。
     * 硬切会把一句话劈成两半，检索命中后拼给模型的是残缺语义。
     *
     * <p>正文短于块大小时返回 1 块（这是正常情况，不是异常）。
     *
     * @param content 正文
     * @return 切分块列表（至少 1 块）
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
     * <p>两步：先 {@code aiManager.embedAll}（外部 HTTP，可能失败），
     * 再 {@code embeddingStore.addAll}（本地内存，几乎不会失败）。
     * <b>顺序不能反</b>——先拿到向量再入库，才能在向量失败时明确知道"还没有脏数据进向量库"。
     *
     * @param segments 切分块（顺序即块序号）
     * @return 与入参一一对应的向量条目 ID（写入 chunk 表的 embedding_id）
     * @throws BusinessException 向量化失败（C0200）或数量不符时抛出
     */
    public List<String> storeAll(List<TextSegment> segments) {
        ThrowUtils.throwIf(segments == null || segments.isEmpty(),
                ErrorCode.PARAMS_ERROR, "待入库的切分块为空");

        // ① 向量化（HTTP）
        List<Embedding> embeddings = aiManager.embedAll(
                segments.stream().map(TextSegment::text).toList());
        // ② 写入向量库，拿回条目 ID
        return embeddingStore.addAll(embeddings, segments);
    }

    /**
     * 语义检索：把问题向量化后在向量库里找最相似的若干块
     *
     * <p>两步：<b>问题向量化</b>（外部 HTTP）→ <b>向量库检索</b>（纯内存，无 SQL）。
     * 本方法<b>不含任何数据库访问</b>：命中结果里只有向量条目 ID 与块正文，
     * 「这块属于哪篇文档、那篇文档对应哪条业务数据」由 Service 回查数据库（见
     * {@code KnowledgeDocServiceImpl#retrieve}）。
     * 这样划分是为了让「内存计算」与「落库查询」的边界清楚——便于单独测试与换实现。
     *
     * <p><b>检索必须用与入库相同的向量模型</b>：这是本模块最贵的一条约束。
     * 换了 {@code studio.ai.embedding-model} 之后，新向量与库里旧向量处在<b>不同的语义空间</b>，
     * 相似度毫无意义（表现为「什么都检索不到」或「检索到的完全不相关」）。
     * 因此换模型 = 全库重建（重新 sync / 重新手工录入），已登记在 db/DESIGN.md 场景 J。
     *
     * <p><b>空库（或全被 minScore 过滤）返回空列表，而不是异常</b>：
     * 这是日常场景——本类是内存向量库，<b>每次重启都是空的</b>。
     * 返回空列表让调用方自然地走「无资料」分支，不需要额外的 try-catch。
     *
     * @param question 用户提问（已 trim）
     * @param topK     最多取回多少条（对应 {@code studio.ai.rag-top-k}）
     * @param minScore 相似度下限（对应 {@code studio.ai.rag-min-score}，<b>越高越严格</b>）
     * @return 命中列表（按相似度降序，可能为空）
     * @throws BusinessException 向量化失败（C0200）时抛出
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
        return embeddingStore.search(searchRequest).matches();
    }

    /**
     * 删除向量（<b>best-effort</b>：失败只告警，不影响主流程）
     *
     * <p>为什么要容忍失败：调用它的场景是「重建成功后清理旧向量」。
     * 此时新数据已经落库、检索已经能用，残留几个旧向量只是占点内存、
     * 且重启即清空。为了这点残留去让整个重建接口失败（甚至回滚事务）是得不偿失的。
     *
     * <p>反过来说：它<b>绝不能</b>用在"必须先成功"的环节——那应该放进事务里。
     *
     * @param embeddingIds 待清理的向量条目 ID
     */
    public void removeVectors(List<String> embeddingIds) {
        if (embeddingIds == null || embeddingIds.isEmpty()) {
            return;
        }
        try {
            embeddingStore.removeAll(embeddingIds);
        } catch (Exception e) {
            log.warn("清理旧向量失败（best-effort，不影响本次结果）| 数量={}", embeddingIds.size(), e);
        }
    }

    /**
     * 暴露向量库：<b>主要供测试与运维使用</b>（模拟重启、核对向量是否真的被清理）
     *
     * <p>返回接口类型而不是具体实现：检索层只依赖 {@link EmbeddingStore}，
     * 将来换成 Redis / PGVector 时改本类的构造即可，检索层不用动。
     *
     * @return 向量库实例
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }
}
