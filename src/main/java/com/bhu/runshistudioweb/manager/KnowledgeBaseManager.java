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
 * <p>本类只做三件事：切分、向量入库、向量清理。数据库那两行（文档与切分块）
 * 由 Service 负责——这样划分是为了让「纯内存的计算」与「落库的事务」分明，
 * Service 才能清楚地控制事务边界。换句话说，本类里没有任何数据库访问，
 * 因此它的每个方法都可以在事务之外被安全调用。
 *
 * <p>向量库选型：本期是 InMemoryEmbeddingStore（进程内内存，重启丢失）。
 * 这是一个明确的、有代价的决定，请连同代价一起读：
 * <ul>
 *     <li>代价：应用重启后，库里的切分块行还在，但向量没了，检索会查不到任何东西；</li>
 *     <li>为什么本期还这么选：换 Redis、PGVector、Milvus 只是换一个 EmbeddingStore
 *     实现（换一行构造代码），但会引入一个必须单独运维的组件。
 *     在「检索刚上线、知识库量级还很小」的前提下，先不背这个运维成本更合理；</li>
 *     <li>补救：重启后调用知识库的 reindex-all 接口即可恢复。注意此时 sync 与 sync-all
 *     会全部跳过（内容哈希没变，它们什么都不会做），救不回来——这是最容易踩的坑。</li>
 * </ul>
 *
 * <p>为什么不用 EmbeddingStoreIngestor（LangChain4j 提供的一条龙入库工具）：
 * 它一个 ingest(document) 就把切分、向量化、入库全做完，很省事，
 * 但它不返回 embeddingId——而切分块表必须落这个 ID，检索回查就是靠它
 * 把「命中的向量」翻译成「块的正文」。拿不到 ID，向量和块就断了联系。
 * 所以这里手写「split → embed → addAll」三步。
 *
 * <p>切块时不写元数据（不把文档 ID、来源 ID 之类的信息塞进 TextSegment 的 metadata）：
 * 回查一律走数据库（切分块行里有完整信息），保持单一事实源——不然同一份信息存两处，
 * 迟早不一致，而向量库里的旧数据还特别难修。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseManager {

    /**
     * 内存向量库：进程内内存，重启丢失（理由见类注释）
     *
     * <p>用 final 字段持有单例：整个应用共用一份，否则各处各建一个等于数据分散，
     * 检索时只会看到自己那一份。
     */
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

    private final AiProperties aiProperties;

    private final AiManager aiManager;

    /**
     * 切分正文
     *
     * <p>为什么这样设计：用递归切分（recursive）而不是固定长度硬切。递归切分会先按
     * 段落分，段落还太长再按句子分，句子还太长才按字符分——尽量在语义边界上断开。
     * 硬切会把一句话劈成两半，检索命中后拼给模型的是残缺语义，
     * 表现为「资料明明有，回答却答非所问」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验正文非空：空正文切不出任何块，继续往下只会在向量化时白花一次调用；</li>
     *     <li>② 把正文包成 Document。切分器只认 Document，这里是一次必要的包装，
     *     不涉及任何 IO；</li>
     *     <li>③ 用「块大小 + 重叠字符数」两个配置构造递归切分器。重叠不能省：
     *     一句话恰好跨在边界上时，不加重叠就会把完整语义劈成两半；</li>
     *     <li>④ 切分并兜底判空。理论上正文非空就至少一块，兜一层是为了避免下游出现
     *     「0 块但文档已索引」的怪状态。</li>
     * </ol>
     *
     * <p>容易踩的坑：块大小与重叠都来自配置，若重叠被配成大于等于块大小，
     * 切分器可能切出大量几乎相同的块，向量化费用成倍增长而检索质量不升反降。
     * 另外正文短于块大小时返回 1 块，这是正常情况，不是异常。
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
     * <p>为什么这样设计：两步——先调 Embedding 服务算向量（外部 HTTP，可能失败），
     * 再把向量写进内存库（本地操作，几乎不会失败）。顺序不能反：先拿到全部向量再入库，
     * 才能在向量化失败时明确知道「向量库里还没有任何脏数据」，
     * 不需要再做一次回滚清理。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验切分块非空：空列表去调 Embedding 只会得到一次必然失败的请求；</li>
     *     <li>② 取出每块的纯文本，交给 AI 管理器批量向量化。批量而不是逐条：
     *     Embedding 接口一次可以收一批文本，逐条调用会把「一次 HTTP」放大成 N 次；</li>
     *     <li>③ 把向量与原文一起写进向量库，拿回每条向量的条目 ID。
     *     这两个参数必须一一对应，否则会出现「命中第 3 条向量、取回第 5 块的正文」的错位。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的 ID 列表长度必须与入参块数一致——调用方要用第 i 个 ID
     * 对应第 i 个块。向量化那一步已经校验过数量，但这里仍不能想当然：
     * 一旦错位，检索会命中张冠李戴的内容，而且极难排查（看起来一切正常，就是答非所问）。
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
        // ② 写入向量库，拿回条目 ID
        return embeddingStore.addAll(embeddings, segments);
    }

    /**
     * 语义检索：把问题向量化后，在向量库里找最相似的若干块
     *
     * <p>为什么这样设计：两步——问题向量化（外部 HTTP）→ 向量库检索（纯内存，无 SQL）。
     * 本方法不含任何数据库访问：命中结果里只有向量条目 ID 与块正文，
     * 「这块属于哪篇文档、那篇文档对应哪条业务数据」由 Service 回查数据库。
     * 这样划分是为了让「内存计算」与「落库查询」的边界清楚，便于单独测试与换实现。
     *
     * <p>检索必须用与入库相同的向量模型：这是本模块最贵的一条约束。
     * 换了向量模型之后，新问题算出的向量与库里旧向量处在不同的语义空间，
     * 相似度毫无意义（表现为「什么都检索不到」或「检索到的完全不相关」）。
     * 因此换模型等于全库重建。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 问题向量化：与入库走同一个方法，保证同一套超时与失败收敛；</li>
     *     <li>② 校验向量非空：拿到空向量说明外部服务返回了异常结构，
     *     这属于第三方服务问题（C 类），不是本系统的 bug；</li>
     *     <li>③ 组装检索请求：maxResults 控制条数、minScore 过滤低质量命中，
     *     两个参数都来自配置——阈值依赖具体模型的分值分布，换模型就要重新校准；</li>
     *     <li>④ 执行检索并只取命中列表。空库返回空列表而不是异常，
     *     这是日常场景（内存向量库每次重启都是空的）。</li>
     * </ol>
     *
     * <p>容易踩的坑：minScore 的口径不是余弦相似度本身，而是
     * (1 + 余弦) / 2 映射到 0 到 1 之间的相关度。0.5 对应「正交」（毫不相关）
     * 而不是「有点相关」，0.75 才对应余弦 0.5。把它当余弦来调，会把阈值整体压低一半，
     * 放进大量噪声。另外空库返回空列表是刻意的：调用方可以自然地走「无资料」分支，
     * 不需要额外 try-catch。
     *
     * @param question 用户提问（已 trim）；为 null 或空白时抛 A0401
     * @param topK 最多取回多少条（对应 studio.ai.rag-top-k），小于 1 时由向量库按 0 条处理
     * @param minScore 相似度下限（对应 studio.ai.rag-min-score，越高越严格）
     * @return 命中列表（按相似度降序），可能为空，不会为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 问题为空（A0401）、
     * 向量化失败或结果为空（C0200）时抛出
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
     * 删除向量（尽力而为：失败只告警，不影响主流程）
     *
     * <p>为什么这样设计：调用它的场景都是「新数据已经就位、旧向量该清了」——
     * 重建成功后、删除文档提交后。此时检索已经能用，残留几个旧向量只是占点内存，
     * 且重启即清空。为了这点残留去让整个重建接口失败（甚至回滚事务）是得不偿失的。
     * 反过来说，它绝不能用在「必须先成功」的环节——那应该放进数据库事务里，
     * 而内存操作本身无法回滚。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空列表直接返回：没有要删的就不要往向量库发一次调用；</li>
     *     <li>② 调向量库批量删除；异常只记 warn 并吞掉，向上层暴露的是「删过了」这个语义。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法吞异常，调用方不能靠它判断「向量真的被清掉了」。
     * 这正是检索层必须容忍「命中向量但回查不到块」的原因——清理失败是允许发生的，
     * 兜底责任在检索层，不在本方法。
     *
     * @param embeddingIds 待清理的向量条目 ID；为 null 或空列表时什么都不做
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
     * 暴露向量库实例（主要供测试与运维核对）
     *
     * <p>为什么这样设计：返回接口类型而不是具体实现，是因为检索层只依赖
     * EmbeddingStore 这一层抽象。将来换成 Redis 或 PGVector 时，只改本类的构造，
     * 依赖它的代码一行都不用动。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接返回内部持有的那个实例。注意这是「暴露引用」而不是拷贝，
     *     拿到它的人可以清空整个库——因此它只该在测试与运维核对时使用，
     *     业务代码不要拿它绕开本类的方法。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在业务代码里用它做检索。绕开 search 意味着绕开了
     * 「问题向量化」这一步，而向量库只能拿向量去查，直接传文本是查不到东西的。
     *
     * @return 向量库实例（与业务用的是同一个对象，不是副本）
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }
}
