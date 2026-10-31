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
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeDocVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeIngestVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSourceVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSyncAllVO;
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
 * 入库管线：读源 → SHA-256 幂等判定 → 切分 → 向量化（外部 HTTP，不在事务内）
 * → 事务内 upsert 文档行、删旧块、插新块。向量库是进程内内存，重启后需 reindex-all 才能补回。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    /** 索引状态：已索引 */
    private static final int STATUS_INDEXED = 1;

    /** 索引状态：失败（可重试），下次 sync 会无视 hash 相同而强制重建一次 */
    private static final int STATUS_FAILED = 2;

    /** 手工录入的正文长度上限（与 DTO 上的 @Size(max = 20000) 保持一致） */
    private static final int MAX_CONTENT_LENGTH = 20000;

    /** 检索参数在配置为 null 时的兜底值（与 AiProperties 的字段默认值一致） */
    private static final int DEFAULT_RAG_TOP_K = 5;

    /** 相似度下限兜底值（与 AiProperties#getRagMinScore 的默认值保持一致） */
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

    /** 文档表 Mapper */
    private final StudioKnowledgeDocMapper docMapper;

    /** 切分块表 Mapper */
    private final StudioKnowledgeChunkMapper chunkMapper;

    /** 知识库管理器：切分、向量化入库、向量检索、向量清理 */
    private final KnowledgeBaseManager knowledgeBaseManager;

    /** 检索参数（topK / minScore）来自配置：阈值必须可调，因为它依赖具体向量模型的分值分布，换模型就要重新校准。 */
    private final AiProperties aiProperties;

    /** 三个业务来源的 Mapper */
    private final StudioProjectMapper studioProjectMapper;

    private final StudioMemberMapper studioMemberMapper;

    private final StudioCertificateMapper studioCertificateMapper;

    /** 事务管理器 */
    private final PlatformTransactionManager transactionManager;

    /** 事务模板：由 transactionManager 在依赖注入完成后构造 */
    private TransactionTemplate transactionTemplate;

    /** 构造事务模板（容器启动时执行一次） */
    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 手工录入一篇知识文档并完成切分 / 向量化 / 入库 */
    @Override
    public KnowledgeIngestVO ingestManual(KnowledgeManualIngestRequest request) {
        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免下面 getXxx() 抛 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String title = StrUtil.trim(request.getTitle());
        String content = StrUtil.trim(request.getContent());
        // 先 trim 再判空：纯空白能通过「非 null」检查，却会切出一个内容全是空白的块
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "文档标题不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(content), ErrorCode.PARAMS_ERROR, "文档正文不能为空");
        ThrowUtils.throwIf(content.length() > MAX_CONTENT_LENGTH, ErrorCode.PARAMS_ERROR,
                "文档正文不能超过 " + MAX_CONTENT_LENGTH + " 个字符");

        // 手工录入不参与幂等：没有业务主键可比，每次调用都新建一篇文档
        return ingest(title, content, KnowledgeSourceTypeEnum.MANUAL.getValue(), null, null);
    }

    /** 从业务源（项目 / 成员 / 证书）同步一篇文档 */
    @Override
    public KnowledgeIngestVO sync(KnowledgeSyncRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // 来源类型：闭集校验（manual 在这里没有意义，它不属于"业务来源"）
        KnowledgeSourceTypeEnum sourceType = resolveSourceType(StrUtil.trim(request.getSourceType()));
        long sourceId = request.getSourceId() == null ? 0L : request.getSourceId();
        ThrowUtils.throwIf(sourceId <= 0, ErrorCode.PARAMS_ERROR, "来源 ID 不合法");

        SourceContent source = readSource(sourceType, sourceId);

        // 幂等判定：内容没变且已索引才零成本返回
        String hash = sha256(source.content());
        StudioKnowledgeDoc existing = findBySource(sourceType, sourceId);
        if (existing != null && hash.equals(existing.getContentHash())
                && existing.getStatus() != null && existing.getStatus() == STATUS_INDEXED) {
            log.info("知识库内容未变化且已索引，跳过重建 | sourceType={} sourceId={}",
                    sourceType.getValue(), sourceId);
            return toVO(existing, true, false);
        }
        if (existing != null && hash.equals(existing.getContentHash())) {
            log.info("内容未变但文档处于非索引态，执行一次复位重建 | docId={} status={}",
                    existing.getId(), existing.getStatus());
        }

        return ingest(source.title(), source.content(), sourceType.getValue(), sourceId, existing);
    }

    // 入库主流程

    /** 入库主流程（手工录入与业务同步共用，保证两条路径行为一致） */
    private KnowledgeIngestVO ingest(String title, String content, String sourceType, Long sourceId,
                                     StudioKnowledgeDoc existing) {
        String hash = sha256(content);

        // 切分：纯内存计算，无事务
        List<TextSegment> segments = knowledgeBaseManager.split(content);

        List<String> embeddingIds;
        try {
            embeddingIds = knowledgeBaseManager.storeAll(segments);
        } catch (Exception e) {
            log.error("知识库向量化失败 | sourceType={} sourceId={} 块数={}", sourceType, sourceId,
                    segments.size(), e);
            markFailed(existing);
            throw toEmbeddingFailure(e);
        }

        List<String> oldEmbeddingIds = existing == null ? List.of() : listEmbeddingIds(existing.getId());

        // 事务：upsert doc + 逻辑删旧块 + 插入新块
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
                    // 只带要改的字段更新：用「空实体 + 主键」的方式，避免把没读到的列写成 null
                    StudioKnowledgeDoc update = new StudioKnowledgeDoc();
                    update.setId(existing.getId());
                    update.setTitle(title);
                    update.setContentHash(hash);
                    update.setStatus(STATUS_INDEXED);
                    update.setChunkCount(segments.size());
                    ThrowUtils.throwIf(docMapper.updateById(update) != 1,
                            ErrorCode.OPERATION_ERROR, "更新文档失败");
                    docId = existing.getId();
                    chunkMapper.delete(new LambdaQueryWrapper<StudioKnowledgeChunk>()
                            .eq(StudioKnowledgeChunk::getDocId, docId));
                }

                for (int i = 0; i < segments.size(); i++) {
                    StudioKnowledgeChunk chunk = new StudioKnowledgeChunk();
                    chunk.setDocId(docId);
                    chunk.setChunkIndex(i);
                    chunk.setContent(segments.get(i).text());
                    chunk.setEmbeddingId(embeddingIds.get(i));
                    ThrowUtils.throwIf(chunkMapper.insert(chunk) != 1,
                            ErrorCode.OPERATION_ERROR, "新增切分块失败");
                }
                docIdHolder[0] = docId;
            });
        } catch (Exception e) {
            knowledgeBaseManager.removeVectors(embeddingIds);
            markFailed(existing);
            log.error("知识库落库失败，已回滚 | sourceType={} sourceId={}", sourceType, sourceId, e);
            throw toEmbeddingFailure(e);
        }

        knowledgeBaseManager.removeVectors(oldEmbeddingIds);

        StudioKnowledgeDoc saved = docMapper.selectById(docIdHolder[0]);
        return toVO(saved, false, existing != null);
    }

    // 检索

    /** 检索与问题相关的资料 */
    @Override
    public RetrievalResult retrieve(String question) {
        String text = StrUtil.trim(question);
        if (StrUtil.isBlank(text)) {
            // 空问题直接返回空结果：向量化一次空串既没有意义，也可能被外部服务拒绝
            return RetrievalResult.empty();
        }

        // 配置兜底：字段本身已有默认值，这里是防「yml 里显式写空值」导致 NPE
        int topK = aiProperties.getRagTopK() == null ? DEFAULT_RAG_TOP_K : aiProperties.getRagTopK();
        double minScore = aiProperties.getRagMinScore() == null ? DEFAULT_RAG_MIN_SCORE : aiProperties.getRagMinScore();

        // 向量检索：纯内存，无 SQL；空库返回空列表而不是异常
        List<EmbeddingMatch<TextSegment>> matches = knowledgeBaseManager.search(text, topK, minScore);
        if (matches.isEmpty()) {
            return RetrievalResult.empty();
        }

        List<String> embeddingIds = matches.stream().map(EmbeddingMatch::embeddingId).toList();
        List<StudioKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeChunk>()
                        .in(StudioKnowledgeChunk::getEmbeddingId, embeddingIds));
        Map<String, StudioKnowledgeChunk> chunkByEmbeddingId = chunks.stream()
                .collect(Collectors.toMap(StudioKnowledgeChunk::getEmbeddingId,
                        Function.identity(), (a, b) -> a));

        Set<Long> docIds = chunks.stream().map(StudioKnowledgeChunk::getDocId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (docIds.isEmpty()) {
            // 命中的向量全部反查不到块：等价于没命中，不必再查文档
            return RetrievalResult.empty();
        }
        Map<Long, StudioKnowledgeDoc> docById = docMapper.selectList(
                        new LambdaQueryWrapper<StudioKnowledgeDoc>().in(StudioKnowledgeDoc::getId, docIds))
                .stream()
                .collect(Collectors.toMap(StudioKnowledgeDoc::getId, Function.identity(), (a, b) -> a));

        // 组装：按相似度降序拼资料文本，同时收集溯源
        StringBuilder context = new StringBuilder();
        List<KnowledgeSourceVO> sources = new ArrayList<>();
        int index = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            StudioKnowledgeChunk chunk = chunkByEmbeddingId.get(match.embeddingId());
            if (chunk == null) {
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
                // 手工录入的文档没有业务来源，source_id 为 null，此时只写来源类型
                context.append("/").append(doc.getSourceId());
            }
            context.append("）\n").append(chunk.getContent()).append("\n\n");

            KnowledgeSourceVO sourceVO = new KnowledgeSourceVO();
            sourceVO.setDocId(doc.getId());
            sourceVO.setTitle(doc.getTitle());
            sourceVO.setSourceType(doc.getSourceType());
            sourceVO.setSourceId(doc.getSourceId());
            // 带上相似度：前端可以据此展示「相关度」，运维排查时也用得上
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

    // 管理端操作

    /** 全量同步：把三类业务数据一次性灌进知识库 */
    @Override
    public KnowledgeSyncAllVO syncAll() {
        // 三类源各查一次：只 select 主键，不把正文拉回内存（正文由 sync 自己按需读）
        List<Long> projectIds = studioProjectMapper.selectList(
                        new LambdaQueryWrapper<StudioProject>().select(StudioProject::getId))
                .stream().map(StudioProject::getId).toList();
        List<Long> memberIds = studioMemberMapper.selectList(
                        new LambdaQueryWrapper<StudioMember>().select(StudioMember::getId))
                .stream().map(StudioMember::getId).toList();
        List<Long> certificateIds = studioCertificateMapper.selectList(
                        new LambdaQueryWrapper<StudioCertificate>().select(StudioCertificate::getId))
                .stream().map(StudioCertificate::getId).toList();

        // 逐条 sync：每条自带事务，单条失败只回滚自己
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

    /** 同步一批同源数据，把结果累加进计数器（单条失败只累计、不抛出） */
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
                counters[IDX_FAILED]++;
                log.error("全量同步单条失败（已跳过，可重跑本接口重试） | sourceType={} sourceId={}",
                        sourceType.getValue(), sourceId, e);
            }
        }
    }

    /** 强制重建全部文档的向量（向量库的恢复手段） */
    @Override
    public KnowledgeSyncAllVO reindexAll() {
        // 一条 SQL 取全部未删除文档（只取主键：正文在 chunk 行里，这里不需要）
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

    /** 重建单篇文档的向量 */
    private boolean reindexDoc(long docId) {
        // 取该文档的存活块（一条 SQL，按块序号升序：与原文顺序一致）
        List<StudioKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeChunk>()
                        .eq(StudioKnowledgeChunk::getDocId, docId)
                        .orderByAsc(StudioKnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            return false;
        }

        List<TextSegment> segments = chunks.stream()
                .map(chunk -> TextSegment.from(chunk.getContent()))
                .toList();
        List<String> embeddingIds = knowledgeBaseManager.storeAll(segments);

        List<String> oldEmbeddingIds = chunks.stream()
                .map(StudioKnowledgeChunk::getEmbeddingId)
                .filter(Objects::nonNull)
                .toList();

        // 事务内把新的 embedding_id 写回各块
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < chunks.size(); i++) {
                StudioKnowledgeChunk update = new StudioKnowledgeChunk();
                update.setId(chunks.get(i).getId());
                // 按下标一一对应：i 既是块的序号，也是它在 embeddingIds 里的位置
                update.setEmbeddingId(embeddingIds.get(i));
                ThrowUtils.throwIf(chunkMapper.updateById(update) != 1,
                        ErrorCode.OPERATION_ERROR, "更新切分块的向量 ID 失败");
            }
        });

        // 提交后 best-effort 清理旧向量
        knowledgeBaseManager.removeVectors(oldEmbeddingIds);
        return true;
    }

    /** 管理端分页查询文档（不含正文） */
    @Override
    public Page<KnowledgeDocVO> listDocByPage(KnowledgeDocQueryRequest request) {
        KnowledgeDocQueryRequest query = request == null ? new KnowledgeDocQueryRequest() : request;

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
                .orderByDesc(StudioKnowledgeDoc::getCreatedAt)
                .orderByDesc(StudioKnowledgeDoc::getId);

        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时一个 pageSize=100000 就能把整张表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        Page<StudioKnowledgeDoc> entityPage = docMapper.selectPage(new Page<>(current, pageSize), wrapper);
        Page<KnowledgeDocVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(),
                entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toDocVO).toList());
        return voPage;
    }

    /** 删除文档（逻辑删文档 + 同事务级联逻辑删切分块，提交后尽力清向量） */
    @Override
    public boolean deleteDoc(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "文档 id 不合法");
        StudioKnowledgeDoc doc = docMapper.selectById(id);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");

        List<String> embeddingIds = listEmbeddingIds(id);

        transactionTemplate.executeWithoutResult(status -> {
            ThrowUtils.throwIf(docMapper.deleteById(id) != 1, ErrorCode.OPERATION_ERROR, "删除文档失败");
            chunkMapper.delete(new LambdaQueryWrapper<StudioKnowledgeChunk>()
                    .eq(StudioKnowledgeChunk::getDocId, id));
        });

        // 提交后 best-effort 清向量：失败只告警（残留会在重启后自然消失）
        knowledgeBaseManager.removeVectors(embeddingIds);
        log.info("知识库文档已删除 | docId={} | 清理向量={} 个", id, embeddingIds.size());
        return true;
    }

    /** 重建一篇文档：回到业务源重新读一遍正文，重新切分与向量化 */
    @Override
    public KnowledgeIngestVO rebuildDoc(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "文档 id 不合法");
        StudioKnowledgeDoc doc = docMapper.selectById(id);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");

        if (KnowledgeSourceTypeEnum.MANUAL.getValue().equals(doc.getSourceType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "手工录入的文档没有业务来源，无法重建；请删除后重新手工录入");
        }
        ThrowUtils.throwIf(doc.getSourceId() == null, ErrorCode.PARAMS_ERROR,
                "该文档缺少来源业务 ID，无法重建");

        KnowledgeSyncRequest request = new KnowledgeSyncRequest();
        request.setSourceType(doc.getSourceType());
        request.setSourceId(doc.getSourceId());
        return sync(request);
    }

    // 读源与拼装

    /** 读取业务数据并拼成正文（源不存在时抛 A0402，并把已有文档标记为失败态） */
    private SourceContent readSource(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        StringBuilder content = new StringBuilder();

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

    /** 解析来源类型（闭集校验，并拒绝 manual） */
    private KnowledgeSourceTypeEnum resolveSourceType(String sourceType) {
        KnowledgeSourceTypeEnum resolved = KnowledgeSourceTypeEnum.of(sourceType);
        ThrowUtils.throwIf(resolved == null, ErrorCode.PARAMS_ERROR,
                "来源类型不合法，仅支持 " + KnowledgeSourceTypeEnum.valuesText());
        // manual 没有业务主键，走 sync 无法幂等，明确引导到手工录入接口
        ThrowUtils.throwIf(resolved == KnowledgeSourceTypeEnum.MANUAL, ErrorCode.PARAMS_ERROR,
                "手工录入没有业务来源，请改用 /knowledge/doc/manual");
        return resolved;
    }

    // 私有工具

    /** 追加一行「标签：值」；值为空则整行省略 */
    private void appendLine(StringBuilder content, String label, Object value) {
        if (value == null || StrUtil.isBlank(value.toString())) {
            return;
        }
        content.append(label).append("：").append(value).append('\n');
    }

    /** 证书级别取中文文案（枚举里没有的脏值则退回原值，不丢信息） */
    private String levelText(String awardLevel) {
        CertificateLevelEnum level = CertificateLevelEnum.of(awardLevel);
        return level == null ? awardLevel : level.getDesc();
    }

    /** 把库里的证书类型英文值翻成中文文案，供 AI 阅读 */
    private String typeText(String awardType) {
        CertificateTypeEnum type = CertificateTypeEnum.of(awardType);
        return type == null ? awardType : type.getDesc();
    }

    /** 按来源定位已有文档 */
    private StudioKnowledgeDoc findBySource(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        // 条件顺序与 idx_source (source_type, source_id) 一致，才能命中索引最左前缀
        return docMapper.selectOne(new LambdaQueryWrapper<StudioKnowledgeDoc>()
                .eq(StudioKnowledgeDoc::getSourceType, sourceType.getValue())
                .eq(StudioKnowledgeDoc::getSourceId, sourceId));
    }

    /** 取某文档当前的全部向量 ID（重建前调用，用于提交后清理） */
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

    /** 把已有文档标记为「失败」（仅当文档已存在） */
    private void markFailed(StudioKnowledgeDoc existing) {
        if (existing == null) {
            return;
        }
        updateStatus(existing.getId(), STATUS_FAILED);
        log.warn("知识库文档标记为失败状态 | docId={} sourceType={} sourceId={}",
                existing.getId(), existing.getSourceType(), existing.getSourceId());
    }

    private void markSourceMissing(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        StudioKnowledgeDoc existing = findBySource(sourceType, sourceId);
        if (existing == null) {
            return;
        }
        updateStatus(existing.getId(), STATUS_FAILED);
        log.warn("知识库文档的源数据已不存在，标记为失败 | docId={} sourceType={} sourceId={}",
                existing.getId(), sourceType.getValue(), sourceId);
    }

    /** 只更新 status 字段 */
    private void updateStatus(long docId, int status) {
        docMapper.update(null, new LambdaUpdateWrapper<StudioKnowledgeDoc>()
                .eq(StudioKnowledgeDoc::getId, docId)
                .set(StudioKnowledgeDoc::getStatus, status));
    }

    /** 统一把异常收敛成 C0200（与对话接口同一文案） */
    private BusinessException toEmbeddingFailure(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException;
        }
        // 向量化走的是外部 AI 服务，属 C 类（第三方服务），不是本系统 bug
        return new BusinessException(ErrorCode.AI_SERVICE_ERROR, EMBEDDING_UNAVAILABLE_MESSAGE);
    }

    /** SHA-256 摘要（用于增量去重） */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 显式指定 UTF-8：平台默认编码会让同一段中文在不同系统上算出不同哈希
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                // %02x 的补零不能省：小于 0x10 的字节只输出一位，哈希串长度就不一致了
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，理论上不可能走到这里
            log.error("计算内容摘要失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统错误");
        }
    }

    /** 组装入库结果 */
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

    /** 文档实体转列表 VO */
    private KnowledgeDocVO toDocVO(StudioKnowledgeDoc doc) {
        KnowledgeDocVO vo = new KnowledgeDocVO();
        BeanUtils.copyProperties(doc, vo);
        // id 与 docId 名字不同，copyProperties 拷不到，必须手动映射
        vo.setDocId(doc.getId());
        return vo;
    }

    // 内部类型

    /** 从业务数据拼出的「标题 + 正文」 */
    private record SourceContent(String title, String content) {
    }
}
