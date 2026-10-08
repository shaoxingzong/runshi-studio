package com.bhu.runshistudioweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.KnowledgeBaseManager;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.mapper.StudioKnowledgeChunkMapper;
import com.bhu.runshistudioweb.mapper.StudioKnowledgeDocMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioProjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeDocQueryRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeManualIngestRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeSyncRequest;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeChunk;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeDoc;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.model.enums.KnowledgeSourceTypeEnum;
import com.bhu.runshistudioweb.model.vo.KnowledgeDocVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeIngestVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeSourceVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeSyncAllVO;
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 接口的 static 方法不继承给实现类，因此简名调用要靠静态导入（否则每处都要写全限定名）
import static com.bhu.runshistudioweb.service.KnowledgeDocService.RetrievalResult.empty;

/**
 * 知识库文档服务实现（RAG 的编排中枢）
 *
 * author: shaoshing
 *
 * <p>本类承载四期能力（接口上的分层说明见 {@link KnowledgeDocService}）：
 * 入库、 检索、 管理端操作、 向量重建。
 * 下面是<b>入库</b>的流程图（另外三条链路各有自己的注释小节）。
 *
 * <p><b>完整流程图（改代码前先读这一张）</b>：
 * <pre>
 * ① 读源 → 拼正文 → SHA-256 hash                  （无事务；源不存在 → A0402 + 已有文档标 status=2）
 * ② 幂等判定：查 (sourceType, sourceId)
 *      hash 相同 → 直接返回 skipped=true          （零 Embedding 调用、零写库）
 * ③ 切分：DocumentSplitters.recursive(500, 50)     （纯内存）
 * ④ 向量化：embedAll + store.addAll → embeddingIds （外部 HTTP！绝不在事务内）
 * ⑤ 事务（TransactionTemplate）：
 *        upsert doc 行(status=1, hash, chunkCount)
 *      + 逻辑删旧块 + 逐条插入新块
 * ⑥ 提交后：清理旧向量                             （best-effort，失败仅告警）
 *
 * 失败路径：④ 或 ⑤ 失败 → 清理本次新向量 + 已有文档标 status=2，
 *          旧块与旧向量<b>原样保留</b>
 * </pre>
 *
 * <p><b>两个刻意的设计，理由必须记住</b>：
 * <ol>
 *     <li><b>旧块只在最后（事务内）才删</b>——「先备好新材料，再原子替换」。
 *     向量化失败时旧内容依然可检索，不存在「删了旧的、新的没建好」的不可用窗口。
 *     如果反过来先删旧的，那么一次 Embedding 故障就会让这篇文档彻底查不到；</li>
 *     <li><b>不用 {@code EmbeddingStoreIngestor}</b>——它一条龙 {@code ingest(document)} 很省事，
 *     但<b>不返回 embeddingId</b>，而 chunk 表必须落 {@code embedding_id}
 *     （场景 H 的回查全靠它把向量翻译成块的正文）。所以手写 split → embed → addAll 三步。</li>
 * </ol>
 *
 * <p><b>关于失败时"要不要新建一条 status=2 的 doc 行"</b>：
 * 只在<b>已有文档</b>时标记 status=2；新建失败时<b>不建行</b>。
 * 原因是幂等判定依赖 {@code content_hash}——如果给失败的新文档也写上新 hash，
 * 下次重试时会因为「hash 相同」被判定为无需重建而直接跳过，
 * 失败就永远修复不了（除非内容再变）。这条坑写在这里，改动时不要"顺手补上"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    /** 索引状态：已索引 */
    private static final int STATUS_INDEXED = 1;

    /** 索引状态：失败（可重试） */
    private static final int STATUS_FAILED = 2;

    /**
     * 手工录入的正文长度上限（与 DTO 上的 {@code @Size(max = 20000)} 保持一致）
     *
     * <p>两道校验都要有：注解管 Web 入口，常量管「Service 被定时任务/脚本直接调用」的情况
     */
    private static final int MAX_CONTENT_LENGTH = 20000;

    /**
     * 检索参数在配置为 null 时的兜底值（与 {@link AiProperties} 的字段默认值一致）
     *
     * <p>为什么两处都写：yml 里显式写 {@code rag-top-k:}（空值）会让绑定结果为 null，
     * 而字段默认值只在「键不存在」时生效。检索是提问链路的一环，不该因为一个空配置 NPE。
     */
    private static final int DEFAULT_RAG_TOP_K = 5;

    /**
     * 相似度下限兜底值（与 {@link AiProperties#getRagMinScore()} 的默认值保持一致）
     *
     * <p>注意它是 <b>relevance = (1 + cosine) / 2</b> 口径：0.75 才对应余弦 0.5，
     * 不要照余弦直觉把 0.5 当成「有点相关」（那是正交，等于不过滤）。
     */
    private static final double DEFAULT_RAG_MIN_SCORE = 0.75;

    /** 文档列表分页的默认值与上限（与其它模块同一套口径：上限 50） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 全量同步计数器各槽位的下标（用 int[] 而不是 5 个变量，便于在私有方法间传递） */
    private static final int IDX_TOTAL = 0;
    private static final int IDX_CREATED = 1;
    private static final int IDX_REBUILT = 2;
    private static final int IDX_SKIPPED = 3;
    private static final int IDX_FAILED = 4;

    /** 向量化失败的对外文案：与对话接口保持一致，不暴露服务商细节 */
    private static final String EMBEDDING_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    private final StudioKnowledgeDocMapper docMapper;

    private final StudioKnowledgeChunkMapper chunkMapper;

    private final KnowledgeBaseManager knowledgeBaseManager;

    /**
     * 检索参数（topK / minScore）来自配置：阈值必须可调，
     * 因为它依赖具体向量模型的分值分布，换模型就要重新校准
     */
    private final AiProperties aiProperties;

    // 三个业务来源：只注入 Mapper，不注入各自的 Service——避免任何潜在的循环依赖
    private final StudioProjectMapper studioProjectMapper;

    private final StudioMemberMapper studioMemberMapper;

    private final StudioCertificateMapper studioCertificateMapper;

    /** 事务管理器：容器里没有 TransactionTemplate bean，靠它自行构造（与 AiChatServiceImpl 同一套路） */
    private final PlatformTransactionManager transactionManager;

    /**
     * 事务模板：由 {@code transactionManager} 在依赖注入完成后构造
     *
     * <p>它<b>不能</b>声明成 final：Lombok 生成的构造器只做「参数 → 字段」的直接赋值，
     * 表达不了「由另一个参数二次构造」；写成 final 会被当成构造器参数，
     * 而容器里并不存在 TransactionTemplate 这个 bean，启动即失败。
     */
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public KnowledgeIngestVO ingestManual(KnowledgeManualIngestRequest request) {
        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免下面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String title = StrUtil.trim(request.getTitle());
        String content = StrUtil.trim(request.getContent());
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "文档标题不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(content), ErrorCode.PARAMS_ERROR, "文档正文不能为空");
        ThrowUtils.throwIf(content.length() > MAX_CONTENT_LENGTH, ErrorCode.PARAMS_ERROR,
                "文档正文不能超过 " + MAX_CONTENT_LENGTH + " 个字符");

        // 手工录入不参与幂等：没有业务主键可比，每次调用都新建一篇文档
        return ingest(title, content, KnowledgeSourceTypeEnum.MANUAL.getValue(), null, null);
    }

    @Override
    public KnowledgeIngestVO sync(KnowledgeSyncRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // ① 来源类型：闭集校验（manual 在这里没有意义，它不属于"业务来源"）
        KnowledgeSourceTypeEnum sourceType = resolveSourceType(StrUtil.trim(request.getSourceType()));
        long sourceId = request.getSourceId() == null ? 0L : request.getSourceId();
        ThrowUtils.throwIf(sourceId <= 0, ErrorCode.PARAMS_ERROR, "来源 ID 不合法");

        // ② 读源并拼正文：源不存在 → A0402，同时把已有文档标记为 status=2（收口 R2 的"源已删除"）
        SourceContent source = readSource(sourceType, sourceId);

        // ③ 幂等判定：内容没变<b>且已索引</b>才零成本返回
        String hash = sha256(source.content());
        StudioKnowledgeDoc existing = findBySource(sourceType, sourceId);
        if (existing != null && hash.equals(existing.getContentHash())
                && existing.getStatus() != null && existing.getStatus() == STATUS_INDEXED) {
            log.info("知识库内容未变化且已索引，跳过重建 | sourceType={} sourceId={}",
                    sourceType.getValue(), sourceId);
            return toVO(existing, true, false);
        }
        // ⚠️ 「已索引」这个条件是必须的，不能只比 hash：
        // 失败（status=2）时**没有更新 content_hash**（失败不写 hash 是幂等的护城河），
        // 于是源恢复且内容未变时会命中「hash 相同 → 跳过」，status 就永远停在 2，
        // 这篇文档再也修不回来。加上状态判断后，失败态即使 hash 相同也走一次复位重建。
        if (existing != null && hash.equals(existing.getContentHash())) {
            log.info("内容未变但文档处于非索引态，执行一次复位重建 | docId={} status={}",
                    existing.getId(), existing.getStatus());
        }

        return ingest(source.title(), source.content(), sourceType.getValue(), sourceId, existing);
    }

    // ==================== 入库主流程 ====================

    /**
     * 入库主流程（手工录入与业务同步共用，保证两条路径行为一致）
     *
     * @param title     文档标题
     * @param content   正文
     * @param sourceType 来源类型
     * @param sourceId  来源业务数据 ID（手工录入为 null）
     * @param existing  已有文档（null 表示新建）
     * @return 入库结果
     */
    private KnowledgeIngestVO ingest(String title, String content, String sourceType, Long sourceId,
                                     StudioKnowledgeDoc existing) {
        String hash = sha256(content);

        // ③ 切分：纯内存计算，无事务
        List<TextSegment> segments = knowledgeBaseManager.split(content);

        // ④ 向量化 + 写向量库：<b>外部 HTTP，绝不能包进事务</b>。
        // 一次调用可能十几秒，包进事务会让数据库连接被长时间占用，并发稍高就拖垮连接池
        List<String> embeddingIds;
        try {
            embeddingIds = knowledgeBaseManager.storeAll(segments);
        } catch (Exception e) {
            // 失败：旧块与旧向量原样保留（此时一个字节都没改），只把已有文档标记 status=2
            log.error("知识库向量化失败 | sourceType={} sourceId={} 块数={}", sourceType, sourceId,
                    segments.size(), e);
            markFailed(existing);
            throw toEmbeddingFailure(e);
        }

        // 旧向量 ID 先取出来，等事务提交后再清理（见下方 ⑥ 的说明）
        List<String> oldEmbeddingIds = existing == null ? List.of() : listEmbeddingIds(existing.getId());

        // ⑤ 事务：upsert doc + 逻辑删旧块 + 插入新块
        long[] docIdHolder = new long[1];
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long docId;
                if (existing == null) {
                    StudioKnowledgeDoc doc = new StudioKnowledgeDoc();
                    doc.setTitle(title);
                    doc.setSourceType(sourceType);
                    doc.setSourceId(sourceId);
                    doc.setContentHash(hash);
                    doc.setStatus(STATUS_INDEXED);
                    doc.setChunkCount(segments.size());
                    ThrowUtils.throwIf(docMapper.insert(doc) != 1, ErrorCode.OPERATION_ERROR, "新增文档失败");
                    docId = doc.getId();
                } else {
                    StudioKnowledgeDoc update = new StudioKnowledgeDoc();
                    update.setId(existing.getId());
                    update.setTitle(title);
                    update.setContentHash(hash);
                    update.setStatus(STATUS_INDEXED);
                    update.setChunkCount(segments.size());
                    ThrowUtils.throwIf(docMapper.updateById(update) != 1,
                            ErrorCode.OPERATION_ERROR, "更新文档失败");
                    docId = existing.getId();
                    // 逻辑删旧块必须和"插入新块"同在一个事务：
                    // 否则中途失败会出现「旧块已删、新块没插完」的空窗，这篇文档就查不到了
                    chunkMapper.delete(new LambdaQueryWrapper<StudioKnowledgeChunk>()
                            .eq(StudioKnowledgeChunk::getDocId, docId));
                }

                for (int i = 0; i < segments.size(); i++) {
                    StudioKnowledgeChunk chunk = new StudioKnowledgeChunk();
                    chunk.setDocId(docId);
                    chunk.setChunkIndex(i);
                    chunk.setContent(segments.get(i).text());
                    // 向量 ID 与块一一对应（AiManager 已校验数量一致），
                    // 它就是场景 H 里「命中向量 → 反查块正文」的桥
                    chunk.setEmbeddingId(embeddingIds.get(i));
                    ThrowUtils.throwIf(chunkMapper.insert(chunk) != 1,
                            ErrorCode.OPERATION_ERROR, "新增切分块失败");
                }
                docIdHolder[0] = docId;
            });
        } catch (Exception e) {
            // ⑤ 失败：清理本次新写入的向量（best-effort），旧块与旧向量保留
            // ——注意事务已回滚，所以库里不会留下"半成品块行"
            knowledgeBaseManager.removeVectors(embeddingIds);
            markFailed(existing);
            log.error("知识库落库失败，已回滚 | sourceType={} sourceId={}", sourceType, sourceId, e);
            throw toEmbeddingFailure(e);
        }

        // ⑥ 事务提交后再清理旧向量：此刻库里已经是新块，删旧向量不会造成检索空窗。
        // 放在提交后而不是事务内的原因：它是内存操作、无法回滚，
        // 若放进事务里"假装"参与原子性，反而会误导后来人
        knowledgeBaseManager.removeVectors(oldEmbeddingIds);

        StudioKnowledgeDoc saved = docMapper.selectById(docIdHolder[0]);
        return toVO(saved, false, existing != null);
    }

    // ==================== 检索 ====================

    /**
     * 检索与问题相关的资料（接口契约见 {@link KnowledgeDocService#retrieve}）
     *
     * <p><b>三步、恒定 2 条 SQL</b>：向量检索（无 SQL）→ 按 {@code embedding_id IN} 回查块
     * → 按 {@code doc_id IN} 回查文档。命中 N 条也只查这两次，绝不在循环里逐条回查。
     *
     * <p><b>排序保持向量检索给出的相似度降序</b>：模型对「最相关的资料放最前」敏感，
     * 顺序被打乱会明显降低回答质量。因此这里遍历的是 {@code matches}，
     * 而不是回查出来的列表（回查结果是集合，顺序无意义）。
     *
     * <p><b>悬挂容忍</b>：块或文档查不到时 {@code continue} 跳过——
     * 向量清理是 best-effort，残留条目对应已删除数据是正常现象（R2 收口）。
     */
    @Override
    public RetrievalResult retrieve(String question) {
        String text = StrUtil.trim(question);
        if (StrUtil.isBlank(text)) {
            return RetrievalResult.empty();
        }

        // 配置兜底：字段本身已有默认值，这里是防「yml 里显式写空值」导致 NPE
        int topK = aiProperties.getRagTopK() == null ? DEFAULT_RAG_TOP_K : aiProperties.getRagTopK();
        double minScore = aiProperties.getRagMinScore() == null ? DEFAULT_RAG_MIN_SCORE : aiProperties.getRagMinScore();

        // ① 向量检索：纯内存，无 SQL；空库返回空列表而不是异常
        List<EmbeddingMatch<TextSegment>> matches = knowledgeBaseManager.search(text, topK, minScore);
        if (matches.isEmpty()) {
            return RetrievalResult.empty();
        }

        // ② 一条 SQL 批量回查块（MP 自动追加 deleted_at = 0，已删除的块天然查不到）
        List<String> embeddingIds = matches.stream().map(EmbeddingMatch::embeddingId).toList();
        List<StudioKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeChunk>()
                        .in(StudioKnowledgeChunk::getEmbeddingId, embeddingIds));
        // 合并函数 (a, b) -> a 是防御：万一同一 embedding_id 有两行（不该发生），
        // 取第一条即可，绝不能让 toMap 抛 Duplicate key 把整次提问打断
        Map<String, StudioKnowledgeChunk> chunkByEmbeddingId = chunks.stream()
                .collect(Collectors.toMap(StudioKnowledgeChunk::getEmbeddingId,
                        Function.identity(), (a, b) -> a));

        // ③ 一条 SQL 批量回查文档（同样自动带 deleted_at = 0）
        Set<Long> docIds = chunks.stream().map(StudioKnowledgeChunk::getDocId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (docIds.isEmpty()) {
            return RetrievalResult.empty();
        }
        Map<Long, StudioKnowledgeDoc> docById = docMapper.selectList(
                        new LambdaQueryWrapper<StudioKnowledgeDoc>().in(StudioKnowledgeDoc::getId, docIds))
                .stream()
                .collect(Collectors.toMap(StudioKnowledgeDoc::getId, Function.identity(), (a, b) -> a));

        // ④ 组装：按相似度降序拼资料文本，同时收集溯源
        StringBuilder context = new StringBuilder();
        List<KnowledgeSourceVO> sources = new ArrayList<>();
        int index = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            StudioKnowledgeChunk chunk = chunkByEmbeddingId.get(match.embeddingId());
            if (chunk == null) {
                // 悬挂 ①：块已被逻辑删除（向量残留）
                log.warn("检索命中块的反查为空，跳过该条 | embeddingId={}", match.embeddingId());
                continue;
            }
            StudioKnowledgeDoc doc = docById.get(chunk.getDocId());
            if (doc == null) {
                // 悬挂 ②：文档已被逻辑删除（块还在但文档没了）
                log.warn("检索命中块的文档反查为空，跳过该条 | docId={}", chunk.getDocId());
                continue;
            }

            context.append("【").append(index).append("】《").append(doc.getTitle()).append("》（来源：")
                    .append(doc.getSourceType());
            if (doc.getSourceId() != null) {
                context.append("/").append(doc.getSourceId());
            }
            context.append("）\n").append(chunk.getContent()).append("\n\n");

            KnowledgeSourceVO sourceVO = new KnowledgeSourceVO();
            sourceVO.setDocId(doc.getId());
            sourceVO.setTitle(doc.getTitle());
            sourceVO.setSourceType(doc.getSourceType());
            sourceVO.setSourceId(doc.getSourceId());
            sourceVO.setScore(match.score());
            sources.add(sourceVO);
            index++;
        }

        if (sources.isEmpty()) {
            // 全部命中都被悬挂过滤掉了：等价于没命中
            return RetrievalResult.empty();
        }
        return new RetrievalResult(context.toString().stripTrailing(), sources);
    }

    // ==================== 管理端操作 ====================

    /**
     * 全量同步（接口契约见 {@link KnowledgeDocService#syncAll}）
     *
     * <p><b>固定 3 条列表查询</b>（一类一条，只取主键列），之后逐条走 sync。
     * 每条 sync 内部还会按主键读一次源正文——那是拿内容的必需步骤，
     * 不存在「循环里逐条回查列表」的 N+1。
     */
    @Override
    public KnowledgeSyncAllVO syncAll() {
        // ① 三类源各查一次：只 select 主键，不把正文拉回内存（正文由 sync 自己按需读）
        List<Long> projectIds = studioProjectMapper.selectList(
                        new LambdaQueryWrapper<StudioProject>().select(StudioProject::getId))
                .stream().map(StudioProject::getId).toList();
        List<Long> memberIds = studioMemberMapper.selectList(
                        new LambdaQueryWrapper<StudioMember>().select(StudioMember::getId))
                .stream().map(StudioMember::getId).toList();
        List<Long> certificateIds = studioCertificateMapper.selectList(
                        new LambdaQueryWrapper<StudioCertificate>().select(StudioCertificate::getId))
                .stream().map(StudioCertificate::getId).toList();

        // ② 逐条 sync：每条自带事务，单条失败只回滚自己
        int[] counters = new int[5];
        syncBatch(KnowledgeSourceTypeEnum.PROJECT, projectIds, counters);
        syncBatch(KnowledgeSourceTypeEnum.MEMBER, memberIds, counters);
        syncBatch(KnowledgeSourceTypeEnum.CERTIFICATE, certificateIds, counters);

        KnowledgeSyncAllVO summary = new KnowledgeSyncAllVO();
        summary.setTotal(counters[IDX_TOTAL]);
        summary.setCreated(counters[IDX_CREATED]);
        summary.setRebuilt(counters[IDX_REBUILT]);
        summary.setSkipped(counters[IDX_SKIPPED]);
        summary.setFailed(counters[IDX_FAILED]);
        log.info("知识库全量同步完成 | total={} created={} rebuilt={} skipped={} failed={}",
                counters[IDX_TOTAL], counters[IDX_CREATED], counters[IDX_REBUILT],
                counters[IDX_SKIPPED], counters[IDX_FAILED]);
        return summary;
    }

    /**
     * 同步一批同源数据，把结果累加进计数器（单条失败只累计、不抛出）
     *
     * @param sourceType 来源类型
     * @param sourceIds  该类型的全部业务 ID
     * @param counters   计数器 [total, created, rebuilt, skipped, failed]
     */
    private void syncBatch(KnowledgeSourceTypeEnum sourceType, List<Long> sourceIds, int[] counters) {
        for (Long sourceId : sourceIds) {
            counters[IDX_TOTAL]++;
            try {
                KnowledgeSyncRequest request = new KnowledgeSyncRequest();
                request.setSourceType(sourceType.getValue());
                request.setSourceId(sourceId);
                KnowledgeIngestVO result = sync(request);
                if (result.isSkipped()) {
                    counters[IDX_SKIPPED]++;
                } else if (result.isRebuilt()) {
                    counters[IDX_REBUILT]++;
                } else {
                    counters[IDX_CREATED]++;
                }
            } catch (Exception e) {
                // 单条失败不中断：全量同步是「可重入的批量操作」，
                // 失败的那条留着下次重试即可——因为每条都是幂等的
                counters[IDX_FAILED]++;
                log.error("全量同步单条失败（已跳过，可重跑本接口重试） | sourceType={} sourceId={}",
                        sourceType.getValue(), sourceId, e);
            }
        }
    }

    /**
     * 强制重建全部文档的向量（接口契约见 {@link KnowledgeDocService#reindexAll}）
     *
     * <p><b>不读业务源、也不重新切分</b>：向量化的输入就是 chunk 行里已有的文本，
     * 直接「重新嵌入现有 chunk」即可。这带来两个好处——
     * 快（省掉读源与切分），且对 {@code manual} 类文档同样有效（它没有业务源可读）。
     */
    @Override
    public KnowledgeSyncAllVO reindexAll() {
        // ① 一条 SQL 取全部未删除文档（只取主键：正文在 chunk 行里，这里不需要）
        List<StudioKnowledgeDoc> docs = docMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeDoc>().select(StudioKnowledgeDoc::getId));

        int[] counters = new int[5];
        for (StudioKnowledgeDoc doc : docs) {
            counters[IDX_TOTAL]++;
            try {
                // 逐文档独立事务 + 单条失败不中断：与 sync-all 同一风格
                if (reindexDoc(doc.getId())) {
                    counters[IDX_REBUILT]++;
                } else {
                    counters[IDX_SKIPPED]++;
                }
            } catch (Exception e) {
                counters[IDX_FAILED]++;
                log.error("重建向量失败（已跳过，可重跑本接口重试）| docId={}", doc.getId(), e);
            }
        }

        KnowledgeSyncAllVO summary = new KnowledgeSyncAllVO();
        summary.setTotal(counters[IDX_TOTAL]);
        // created 恒为 0：本接口只重建向量，不新建任何文档
        summary.setCreated(0);
        summary.setRebuilt(counters[IDX_REBUILT]);
        summary.setSkipped(counters[IDX_SKIPPED]);
        summary.setFailed(counters[IDX_FAILED]);
        log.info("知识库向量重建完成 | total={} rebuilt={} skipped={} failed={}",
                counters[IDX_TOTAL], counters[IDX_REBUILT], counters[IDX_SKIPPED], counters[IDX_FAILED]);
        return summary;
    }

    /**
     * 重建单篇文档的向量
     *
     * <p>顺序沿用入库管线（场景 I）：<b>先向量化（HTTP，事务外）→ 事务内更新 embedding_id
     * → 提交后清理旧向量</b>。
     *
     * @param docId 文档 ID
     * @return true 表示已重建；false 表示该文档没有可用块（跳过）
     */
    private boolean reindexDoc(long docId) {
        // ① 取该文档的存活块（一条 SQL，按块序号升序：与原文顺序一致）
        List<StudioKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeChunk>()
                        .eq(StudioKnowledgeChunk::getDocId, docId)
                        .orderByAsc(StudioKnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            return false;
        }

        // ② 向量化：输入就是块正文，不需要读业务源、不需要切分。
        // 这一步是外部 HTTP，<b>绝不能</b>放进事务
        List<TextSegment> segments = chunks.stream()
                .map(chunk -> TextSegment.from(chunk.getContent()))
                .toList();
        List<String> embeddingIds = knowledgeBaseManager.storeAll(segments);

        // 旧向量 ID 先取出来，事务提交后再清理（清理是内存操作、无法回滚）
        List<String> oldEmbeddingIds = chunks.stream()
                .map(StudioKnowledgeChunk::getEmbeddingId)
                .filter(Objects::nonNull)
                .toList();

        // ③ 事务内把新的 embedding_id 写回各块
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < chunks.size(); i++) {
                StudioKnowledgeChunk update = new StudioKnowledgeChunk();
                update.setId(chunks.get(i).getId());
                update.setEmbeddingId(embeddingIds.get(i));
                ThrowUtils.throwIf(chunkMapper.updateById(update) != 1,
                        ErrorCode.OPERATION_ERROR, "更新切分块的向量 ID 失败");
            }
        });

        // ④ 提交后 best-effort 清理旧向量
        knowledgeBaseManager.removeVectors(oldEmbeddingIds);
        return true;
    }

    /**
     * 管理端分页查询文档（接口契约见 {@link KnowledgeDocService#listDocByPage}）
     */
    @Override
    public Page<KnowledgeDocVO> listDocByPage(KnowledgeDocQueryRequest request) {
        KnowledgeDocQueryRequest query = request == null ? new KnowledgeDocQueryRequest() : request;

        // 来源类型是闭集：非法值必须报错而不是静默返回空列表
        // （否则管理员会以为"库里没有这类文档"，排查方向被带偏）
        String sourceType = StrUtil.trim(query.getSourceType());
        if (StrUtil.isNotBlank(sourceType)) {
            ThrowUtils.throwIf(KnowledgeSourceTypeEnum.of(sourceType) == null, ErrorCode.PARAMS_ERROR,
                    "来源类型不合法，仅支持 " + KnowledgeSourceTypeEnum.valuesText());
        }
        String title = StrUtil.trim(query.getTitle());

        LambdaQueryWrapper<StudioKnowledgeDoc> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotBlank(title), StudioKnowledgeDoc::getTitle, title)
                .eq(StrUtil.isNotBlank(sourceType), StudioKnowledgeDoc::getSourceType, sourceType)
                .eq(query.getStatus() != null, StudioKnowledgeDoc::getStatus, query.getStatus())
                // 默认按创建时间倒序：管理员最关心「最近入库/重建了什么」
                .orderByDesc(StudioKnowledgeDoc::getCreatedAt)
                .orderByDesc(StudioKnowledgeDoc::getId);

        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        Page<StudioKnowledgeDoc> entityPage = docMapper.selectPage(new Page<>(current, pageSize), wrapper);
        Page<KnowledgeDocVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(),
                entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toDocVO).toList());
        return voPage;
    }

    /**
     * 删除文档（接口契约见 {@link KnowledgeDocService#deleteDoc}）
     *
     * <p><b>顺序与入库重建时一致</b>：先在事务里改库（doc + chunk 一起逻辑删），
     * 提交后<b>再</b>清向量。清向量必须放在事务后——它是内存操作、无法回滚，
     * 放进事务里只是"假装"参与原子性。
     *
     * <p><b>为什么必须清向量</b>：T26 的「反查为空即跳过」只是兜底。
     * 不清理的话，这些悬挂命中会长期占用 topK 名额（topK=5 里混进 3 条已删文档的块），
     * 表现为「检索结果变少、相关性下降」，而且没有任何报错。
     */
    @Override
    public boolean deleteDoc(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "文档 id 不合法");
        StudioKnowledgeDoc doc = docMapper.selectById(id);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");

        // 向量 ID 必须在删除前取出来：块被逻辑删后仍然是"查得到行"的，
        // 但为了语义清晰（已删数据不再被检索），还是在事务前先拿
        List<String> embeddingIds = listEmbeddingIds(id);

        transactionTemplate.executeWithoutResult(status -> {
            // 逻辑删 doc（MP 的 @TableLogic → UPDATE ... SET deleted_at = 毫秒时间戳）
            ThrowUtils.throwIf(docMapper.deleteById(id) != 1, ErrorCode.OPERATION_ERROR, "删除文档失败");
            // 同事务级联逻辑删 chunk：只删 doc 会留下"块还在、文档没了"的孤儿，
            // 它们不会出现在检索结果里（doc 反查为空会被跳过），但会白白占着行与向量
            chunkMapper.delete(new LambdaQueryWrapper<StudioKnowledgeChunk>()
                    .eq(StudioKnowledgeChunk::getDocId, id));
        });

        // 提交后 best-effort 清向量：失败只告警（残留会在重启后自然消失）
        knowledgeBaseManager.removeVectors(embeddingIds);
        log.info("知识库文档已删除 | docId={} | 清理向量={} 个", id, embeddingIds.size());
        return true;
    }

    /**
     * 重建文档（接口契约见 {@link KnowledgeDocService#rebuildDoc}）
     */
    @Override
    public KnowledgeIngestVO rebuildDoc(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "文档 id 不合法");
        StudioKnowledgeDoc doc = docMapper.selectById(id);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");

        // manual 没有业务源可回读：明确拒绝并给出可执行的操作路径，绝不静默拼接
        if (KnowledgeSourceTypeEnum.MANUAL.getValue().equals(doc.getSourceType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "手工录入的文档没有业务来源，无法重建；请删除后重新手工录入");
        }
        ThrowUtils.throwIf(doc.getSourceId() == null, ErrorCode.PARAMS_ERROR,
                "该文档缺少来源业务 ID，无法重建");

        // 走既有 sync：读源 → 比对哈希 → 未变则跳过、变了则重建。
        // 复用而不是另写一套，才能保证"重建"与"同步"的行为完全一致
        KnowledgeSyncRequest request = new KnowledgeSyncRequest();
        request.setSourceType(doc.getSourceType());
        request.setSourceId(doc.getSourceId());
        return sync(request);
    }

    // ==================== 读源与拼装 ====================

    /**
     * 读取业务数据并拼成正文（源不存在时抛 A0402，并把已有文档标记 status=2）
     *
     * <p>拼装规则：<b>null / 空字段整行省略</b>，不写「专业：」这种空行——
     * 空行也会被切进块里，白白消耗 token，还可能让模型以为"这一项就是空的"。
     *
     * @param sourceType 来源类型（非 manual）
     * @param sourceId   业务数据 ID
     * @return 标题与正文
     */
    private SourceContent readSource(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        StringBuilder content = new StringBuilder();

        // 用 switch **表达式**而不是语句：每个分支必须 yield 出一个标题，
        // 编译器会强制检查「没有漏分支」，比"先声明 title 再逐个赋值"更安全
        // （后者一旦漏掉某个分支，只会得到 null 标题，且编译期不报错）
        String title = switch (sourceType) {
            case PROJECT -> {
                StudioProject project = studioProjectMapper.selectById(sourceId);
                // selectById 自动追加 deleted_at = 0：已逻辑删除的业务数据视为"不存在"
                if (project == null) {
                    markSourceMissing(sourceType, sourceId);
                    throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "项目不存在");
                }
                appendLine(content, "标题", project.getTitle());
                appendLine(content, "摘要", project.getDescription());
                appendLine(content, "正文", project.getContent());
                yield project.getTitle();
            }
            case MEMBER -> {
                StudioMember member = studioMemberMapper.selectById(sourceId);
                if (member == null) {
                    markSourceMissing(sourceType, sourceId);
                    throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "成员不存在");
                }
                appendLine(content, "姓名", member.getName());
                appendLine(content, "届别", member.getGradeYear());
                appendLine(content, "专业", member.getMajor());
                appendLine(content, "方向", member.getDirection());
                appendLine(content, "简介", member.getSummary());
                yield member.getName();
            }
            case CERTIFICATE -> {
                StudioCertificate certificate = studioCertificateMapper.selectById(sourceId);
                if (certificate == null) {
                    markSourceMissing(sourceType, sourceId);
                    throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "证书不存在");
                }
                appendLine(content, "名称", certificate.getTitle());
                // 级别与类型用<b>中文文案</b>而不是库里的英文值：
                // 向量是给模型读的，"国家级"比 "national" 更贴近中文语义，
                // 用户问"有哪些国家级证书"时命中率更高
                appendLine(content, "级别", levelText(certificate.getAwardLevel()));
                appendLine(content, "类型", typeText(certificate.getAwardType()));
                appendLine(content, "获奖日期", certificate.getAwardDate());
                yield certificate.getTitle();
            }
            // manual 在入口就被拦掉了，这里到不了；写出来是为了满足表达式的"穷尽性"要求
            case MANUAL -> throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "手工录入没有业务来源，请改用 /knowledge/doc/manual");
        };

        ThrowUtils.throwIf(content.length() == 0, ErrorCode.OPERATION_ERROR, "源数据内容为空，无法入库");
        return new SourceContent(title, content.toString());
    }

    /**
     * 解析来源类型（闭集校验，并拒绝 manual）
     *
     * @param sourceType 请求里的来源类型
     * @return 枚举
     */
    private KnowledgeSourceTypeEnum resolveSourceType(String sourceType) {
        KnowledgeSourceTypeEnum resolved = KnowledgeSourceTypeEnum.of(sourceType);
        ThrowUtils.throwIf(resolved == null, ErrorCode.PARAMS_ERROR,
                "来源类型不合法，仅支持 " + KnowledgeSourceTypeEnum.valuesText());
        // manual 没有业务主键，走 sync 无法幂等，明确引导到手工录入接口
        ThrowUtils.throwIf(resolved == KnowledgeSourceTypeEnum.MANUAL, ErrorCode.PARAMS_ERROR,
                "手工录入没有业务来源，请改用 /knowledge/doc/manual");
        return resolved;
    }

    // ==================== 私有工具 ====================

    /**
     * 追加一行「标签：值」；值为空则<b>整行省略</b>
     *
     * @param content 正文构建器
     * @param label   标签
     * @param value   值（可为任意类型，null 或空串时跳过）
     */
    private void appendLine(StringBuilder content, String label, Object value) {
        if (value == null || StrUtil.isBlank(value.toString())) {
            return;
        }
        content.append(label).append("：").append(value).append('\n');
    }

    /**
     * 级别取中文文案（枚举里没有的脏值则退回原值，不丢信息）
     *
     * @param awardLevel 库里的级别值
     * @return 中文文案
     */
    private String levelText(String awardLevel) {
        CertificateLevelEnum level = CertificateLevelEnum.of(awardLevel);
        return level == null ? awardLevel : level.getDesc();
    }

    /**
     * 类型取中文文案（同上）
     *
     * @param awardType 库里的类型值
     * @return 中文文案
     */
    private String typeText(String awardType) {
        CertificateTypeEnum type = CertificateTypeEnum.of(awardType);
        return type == null ? awardType : type.getDesc();
    }

    /**
     * 按来源定位已有文档
     *
     * @param sourceType 来源类型
     * @param sourceId   来源业务数据 ID
     * @return 文档；不存在时返回 null
     */
    private StudioKnowledgeDoc findBySource(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        // 条件顺序与 idx_source (source_type, source_id) 一致，才能命中索引最左前缀
        return docMapper.selectOne(new LambdaQueryWrapper<StudioKnowledgeDoc>()
                .eq(StudioKnowledgeDoc::getSourceType, sourceType.getValue())
                .eq(StudioKnowledgeDoc::getSourceId, sourceId));
    }

    /**
     * 取某文档当前的全部向量 ID（重建前调用，用于提交后清理）
     *
     * @param docId 文档 ID
     * @return 向量 ID 列表（已过滤空值）
     */
    private List<String> listEmbeddingIds(long docId) {
        List<String> ids = new ArrayList<>();
        for (StudioKnowledgeChunk chunk : chunkMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeChunk>()
                        .eq(StudioKnowledgeChunk::getDocId, docId))) {
            if (StrUtil.isNotBlank(chunk.getEmbeddingId())) {
                ids.add(chunk.getEmbeddingId());
            }
        }
        return ids;
    }

    /**
     * 把已有文档标记为「失败」（仅当文档已存在）
     *
     * <p>新建失败时<b>不建行</b>的原因写在类注释里：
     * 写入新 hash 会让下次重试被幂等判定为"内容未变"而跳过，失败就永远修复不了。
     *
     * @param existing 已有文档（可为 null）
     */
    private void markFailed(StudioKnowledgeDoc existing) {
        if (existing == null) {
            return;
        }
        updateStatus(existing.getId(), STATUS_FAILED);
        log.warn("知识库文档标记为失败状态 | docId={} sourceType={} sourceId={}",
                existing.getId(), existing.getSourceType(), existing.getSourceId());
    }

    /**
     * 源数据已不存在：把已有文档标记为失败（R2「源已删除」的收口动作）
     *
     * <p>为什么必须标记而不是无视：文档留着 status=1 会让检索命中一篇"源已删除"的内容，
     * 用户点进去溯源会查不到任何业务数据——那是比"查不到"更差的体验。
     *
     * @param sourceType 来源类型
     * @param sourceId   来源业务数据 ID
     */
    private void markSourceMissing(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        StudioKnowledgeDoc existing = findBySource(sourceType, sourceId);
        if (existing == null) {
            return;
        }
        updateStatus(existing.getId(), STATUS_FAILED);
        log.warn("知识库文档的源数据已不存在，标记为失败 | docId={} sourceType={} sourceId={}",
                existing.getId(), sourceType.getValue(), sourceId);
    }

    /**
     * 只更新 status 字段
     *
     * <p>用 wrapper 更新而不是 updateById(实体)：这里只需要改一个字段，
     * wrapper 形式不会触碰其它列，也不会触发审计字段填充。
     *
     * @param docId  文档 ID
     * @param status 目标状态
     */
    private void updateStatus(long docId, int status) {
        docMapper.update(null, new LambdaUpdateWrapper<StudioKnowledgeDoc>()
                .eq(StudioKnowledgeDoc::getId, docId)
                .set(StudioKnowledgeDoc::getStatus, status));
    }

    /**
     * 统一把异常收敛成 C0200（与对话接口同一文案）
     *
     * <p>已经是我们自己的 BusinessException（例如参数错误）时原样抛出，
     * 不要把 A0401 误改成 C0200。
     *
     * @param e 原始异常
     * @return 待抛出的业务异常
     */
    private BusinessException toEmbeddingFailure(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException;
        }
        // 向量化走的是外部 AI 服务，属 C 类（第三方服务），不是本系统 bug
        return new BusinessException(ErrorCode.AI_SERVICE_ERROR, EMBEDDING_UNAVAILABLE_MESSAGE);
    }

    /**
     * SHA-256 摘要（用于增量去重）
     *
     * <p>用 SHA-256 而不是 {@code String.hashCode()}：后者只有 32 位且可能碰撞，
     * 一旦碰撞就会「内容变了却判定为没变」，导致知识库永远不更新——
     * 这种错误极其隐蔽（文档看起来一切正常，就是内容过时）。
     *
     * @param text 正文
     * @return 64 位十六进制摘要
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，理论上不可能走到这里
            log.error("计算内容摘要失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统错误");
        }
    }

    /**
     * 组装返回结果
     *
     * @param doc     文档（已落库）
     * @param skipped 是否因内容未变而跳过
     * @param rebuilt 是否为重建（替换了旧块）
     * @return 入库结果
     */
    private KnowledgeIngestVO toVO(StudioKnowledgeDoc doc, boolean skipped, boolean rebuilt) {
        KnowledgeIngestVO vo = new KnowledgeIngestVO();
        vo.setDocId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setChunkCount(doc.getChunkCount());
        vo.setStatus(doc.getStatus());
        vo.setSkipped(skipped);
        vo.setRebuilt(rebuilt);
        return vo;
    }

    /**
     * 文档实体转列表 VO
     *
     * <p>字段名不一致的两处必须手动赋值：实体的 {@code id} 对应 VO 的 {@code docId}
     * （VO 里写 {@code docId} 是为了让前端一眼看出它是文档 ID，而不是某个业务主键）。
     * {@code BeanUtils} 对同名同类型的字段（title / sourceType / status / chunkCount / 时间）
     * 会自动拷贝，不写反而更容易漏。
     *
     * @param doc 文档实体
     * @return 列表 VO（不含正文）
     */
    private KnowledgeDocVO toDocVO(StudioKnowledgeDoc doc) {
        KnowledgeDocVO vo = new KnowledgeDocVO();
        BeanUtils.copyProperties(doc, vo);
        vo.setDocId(doc.getId());
        return vo;
    }

    // ==================== 内部类型 ====================

    /**
     * 从业务数据拼出的「标题 + 正文」
     *
     * <p>用 {@code record} 而不是普通类：它只是"一次返回两个值"的载体，
     * 不可变、不需要 setter、也不需要进 Spring 容器。
     *
     * @param title   文档标题
     * @param content 拼好的正文
     */
    private record SourceContent(String title, String content) {
    }
}
