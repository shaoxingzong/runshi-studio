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
 * 入库管线：读源 → 算 SHA-256 → 幂等判定 → 切分 → 向量化（外部 HTTP，绝不在事务内）
 * → 事务内 upsert 文档行 + 删旧块 + 插新块 → 提交后清理旧向量。旧块只在最后才删。
 *
 * 坑：向量库是进程内内存，重启后向量全丢而块行还在，sync 与 sync-all 会因「哈希没变」
 * 全部跳过，只有 reindex-all 能补回来。新建失败时不建 doc 行，否则失败永远修不回来。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    /**
     * 索引状态：已索引
     *
     * 与 DDL 中 status 列的取值一致（1-已索引，2-失败）。
     * 用 int 常量而不是枚举：它只在幂等判定与日志里用，
     * 而库里存的是数字，中间再加一层枚举转换只会多一处可能对不齐的地方。
     */
    private static final int STATUS_INDEXED = 1;

    /** 索引状态：失败（可重试），下次 sync 会无视 hash 相同而强制重建一次 */
    private static final int STATUS_FAILED = 2;

    /**
     * 手工录入的正文长度上限（与 DTO 上的 @Size(max = 20000) 保持一致）
     *
     * 两道校验都要有：注解管 Web 入口，常量管「Service 被定时任务/脚本直接调用」的情况
     */
    private static final int MAX_CONTENT_LENGTH = 20000;

    /**
     * 检索参数在配置为 null 时的兜底值（与 AiProperties 的字段默认值一致）
     *
     * 为什么两处都写：yml 里显式写 rag-top-k:（空值）会让绑定结果为 null，
     * 而字段默认值只在「键不存在」时生效。检索是提问链路的一环，不该因为一个空配置 NPE。
     */
    private static final int DEFAULT_RAG_TOP_K = 5;

    /**
     * 相似度下限兜底值（与 AiProperties#getRagMinScore 的默认值保持一致）
     *
     * 注意它是 relevance = (1 + cosine) / 2 口径：0.75 才对应余弦 0.5，
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

    /**
     * 文档表 Mapper
     *
     * 文档行只存标题、来源、内容哈希、状态与块数，正文本身在切分块表里。
     * 这个划分让「列表页」与「重建」都不必碰正文。
     */
    private final StudioKnowledgeDocMapper docMapper;

    /**
     * 切分块表 Mapper
     *
     * 一行对应一段正文（一块），并记着它在向量库里的条目 ID。
     * 检索时「命中向量 → 反查块正文」就是靠这个 ID 串起来的。
     */
    private final StudioKnowledgeChunkMapper chunkMapper;

    /**
     * 知识库管理器：切分、向量化入库、向量检索、向量清理
     *
     * 它内部没有任何数据库访问，因此它的每个方法都可以在事务之外安全调用——
     * 这正是本类能把「外部 HTTP」与「数据库事务」清楚地分开的前提。
     */
    private final KnowledgeBaseManager knowledgeBaseManager;

    /**
     * 检索参数（topK / minScore）来自配置：阈值必须可调，
     * 因为它依赖具体向量模型的分值分布，换模型就要重新校准。
     * 写成常量会变成「换模型必须改代码并重新发布」。
     */
    private final AiProperties aiProperties;

    /**
     * 三个业务来源的 Mapper
     *
     * 只注入 Mapper、不注入各自的 Service：本类只需要「按主键读一行业务数据」，
     * 而这正是 Mapper 的职责。若改成注入 Service，知识库就会与项目、成员、
     * 证书三个模块形成双向依赖（它们将来可能也要调知识库），
     * 而构造器注入下成环会让应用启动直接失败。
     */
    private final StudioProjectMapper studioProjectMapper;

    private final StudioMemberMapper studioMemberMapper;

    private final StudioCertificateMapper studioCertificateMapper;

    /**
     * 事务管理器
     *
     * 与 AiChatServiceImpl 同一套路：容器里没有 TransactionTemplate bean，
     * 注入这个（DataSource 事务管理器一定存在）再自行构造模板。
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * 事务模板：由 transactionManager 在依赖注入完成后构造
     *
     * 它不能声明成 final：Lombok 生成的构造器只做「参数 → 字段」的直接赋值，
     * 表达不了「由另一个参数二次构造」；写成 final 会被当成构造器参数，
     * 而容器里并不存在 TransactionTemplate 这个 bean，启动即失败。
     */
    private TransactionTemplate transactionTemplate;

    /**
     * 构造事务模板（容器启动时执行一次）
     *
     * 容器里没有 TransactionTemplate bean，只能自己 new；放在 @PostConstruct 而不是字段初始化，
     * 是因为字段初始化时构造器参数还没赋给 transactionManager。
     * 模板本身不持有事务状态（状态在 ThreadLocal 里），单例共用是安全的。
     */
    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 手工录入一篇知识文档并完成切分 / 向量化 / 入库
     *
     * 手工录入的正文没有业务主键，无法判断「这次提交和上次是不是同一篇」，用标题去重又是错的
     * （两篇同名的《使用说明》完全合法），所以每次调用都新建一篇，判断交给管理员。
     * 校验全部放在最前面：切分与向量化都要花钱，不该为一个必然失败的请求付账。
     *
     * 坑：必须先 trim 再判空，「   」这种纯空格能通过非 null 检查，却会切出一个全空白的块；
     * 正文长度有两道校验（DTO 上的 @Size 与本类常量），注解只在 Web 入口生效。
     *
     * @param request 录入请求：title 与 content 均必填，正文不超过 20000 字
     * @return 入库结果：docId、标题、切分块数、状态（1 表示已索引），skipped 恒为 false
     * @throws BusinessException 参数不合法抛 A0401，向量化失败抛 C0200，写入行数不为 1 抛 B0002
     */
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

    /**
     * 从业务源（项目 / 成员 / 证书）同步一篇文档
     *
     * 同一业务主键只应有一篇文档，因此用 (sourceType, sourceId) 定位，再比对正文的 SHA-256。
     * 哈希没变且已索引时直接返回 skipped=true，不调 Embedding、不写库——Embedding 按文本量计费。
     *
     * 坑：幂等判断不能只比哈希。失败态的文档不会写入新哈希，「源恢复且内容未变」时会命中
     * 「哈希相同 → 跳过」，状态就永远停在失败态，这篇文档再也修不回来。
     *
     * @param request 同步请求：sourceType 只能取 project / member / certificate（大小写敏感），
     *                sourceId 必须是正整数；任一不合法都得到 A0401
     * @return 入库结果：skipped=true 内容未变；rebuilt=true 替换了旧块；都为 false 表示首次新建
     * @throws BusinessException 参数不合法或传了 manual 抛 A0401，源数据不存在抛 A0402（同时标记失败态）
     */
    @Override
    public KnowledgeIngestVO sync(KnowledgeSyncRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // ① 来源类型：闭集校验（manual 在这里没有意义，它不属于"业务来源"）
        KnowledgeSourceTypeEnum sourceType = resolveSourceType(StrUtil.trim(request.getSourceType()));
        // 来源 ID 为 null 时先兜成 0，再统一按「小于等于 0 即非法」判断，
        // 省掉后面到处写 null 分支
        long sourceId = request.getSourceId() == null ? 0L : request.getSourceId();
        ThrowUtils.throwIf(sourceId <= 0, ErrorCode.PARAMS_ERROR, "来源 ID 不合法");

        // ② 读源并拼正文：源不存在 → A0402，同时把已有文档标记为 status=2（记录「源已删除」）
        SourceContent source = readSource(sourceType, sourceId);

        // ③ 幂等判定：内容没变且已索引才零成本返回
        String hash = sha256(source.content());
        StudioKnowledgeDoc existing = findBySource(sourceType, sourceId);
        if (existing != null && hash.equals(existing.getContentHash())
                && existing.getStatus() != null && existing.getStatus() == STATUS_INDEXED) {
            log.info("知识库内容未变化且已索引，跳过重建 | sourceType={} sourceId={}",
                    sourceType.getValue(), sourceId);
            return toVO(existing, true, false);
        }
        // 「已索引」这个条件是必须的，不能只比 hash：
        // 失败（status=2）时没有更新 content_hash（失败不写 hash 是幂等的护城河），
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
     * 两条路径只差来源类型与来源 ID，切分、向量化、落库的顺序完全一样。
     * 事务边界是关键取舍：向量化是外部 HTTP，绝不能包进事务，否则连接被长时间占用。
     *
     * 坑：新向量与块靠下标对应，embeddingIds.get(i) 必须对应 segments.get(i)，
     * 本方法不能再排序或过滤，否则会「命中第 3 条向量、取回第 5 块正文」；
     * 旧向量 ID 必须在事务里删块之前取出。
     *
     * @param sourceId 来源业务数据 ID；手工录入传 null（该列允许为空正是为此）
     * @param existing 已有文档，null 表示新建；非 null 表示替换它的块
     * @return 入库结果（块数、状态、是否重建）
     * @throws BusinessException 向量化失败抛 C0200，落库影响行数不为 1 抛 B0002
     */
    private KnowledgeIngestVO ingest(String title, String content, String sourceType, Long sourceId,
                                     StudioKnowledgeDoc existing) {
        String hash = sha256(content);

        // ③ 切分：纯内存计算，无事务
        List<TextSegment> segments = knowledgeBaseManager.split(content);

        // ④ 向量化 + 写向量库：外部 HTTP，绝不能包进事务。
        // 一次调用可能十几秒，包进事务会让数据库连接被长时间占用，并发稍高就拖垮连接池
        List<String> embeddingIds;
        try {
            embeddingIds = knowledgeBaseManager.storeAll(segments);
        } catch (Exception e) {
            // 失败：旧块与旧向量原样保留（此时一个字节都没改），只把已有文档标记 status=2。
            // 不新建 status=2 的行：新行若带上 hash，下次重试会被幂等判定跳过，永远修不回来
            log.error("知识库向量化失败 | sourceType={} sourceId={} 块数={}", sourceType, sourceId,
                    segments.size(), e);
            markFailed(existing);
            throw toEmbeddingFailure(e);
        }

        // 旧向量 ID 先取出来，等事务提交后再清理。
        // 必须在这里取：事务里旧块被逻辑删除之后，查询就自动过滤掉它们了，再也拿不到这些 ID
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
                    // 向量 ID 与块一一对应（KnowledgeBaseManager 已校验数量一致），
                    // 它就是「命中向量 → 反查块正文」的桥。按下标取，绝不能排序或过滤
                    chunk.setEmbeddingId(embeddingIds.get(i));
                    ThrowUtils.throwIf(chunkMapper.insert(chunk) != 1,
                            ErrorCode.OPERATION_ERROR, "新增切分块失败");
                }
                docIdHolder[0] = docId;
            });
        } catch (Exception e) {
            // ⑤ 失败：清理本次新写入的向量（best-effort），旧块与旧向量保留
            // ——注意事务已回滚，所以库里不会留下"半成品块行"；
            // 而向量库是内存、无法回滚，只能靠这里显式清理，否则会留下永远查不到块的悬挂向量
            knowledgeBaseManager.removeVectors(embeddingIds);
            markFailed(existing);
            log.error("知识库落库失败，已回滚 | sourceType={} sourceId={}", sourceType, sourceId, e);
            throw toEmbeddingFailure(e);
        }

        // ⑥ 事务提交后再清理旧向量：此刻库里已经是新块，删旧向量不会造成检索空窗。
        // 放在提交后而不是事务内的原因：它是内存操作、无法回滚，
        // 若放进事务里"假装"参与原子性，反而会误导读代码的人
        knowledgeBaseManager.removeVectors(oldEmbeddingIds);

        // 重新查一次拿最终状态：插入/更新后的实体不一定带着数据库里的全部列，
        // 而返回给调用方的是「库里现在长什么样」，查一次最准确
        StudioKnowledgeDoc saved = docMapper.selectById(docIdHolder[0]);
        return toVO(saved, false, existing != null);
    }

    // ==================== 检索 ====================

    /**
     * 检索与问题相关的资料
     *
     * 三步、恒定 2 条 SQL：向量检索（纯内存）→ 按 embedding_id IN 回查块 → 按 doc_id IN 回查文档。
     * 命中 N 条也只查这两次，循环里逐条回查就是 N+1。
     * 遍历的是 matches 而不是回查出来的集合：模型对「最相关的放最前」很敏感，顺序被打乱会明显降低回答质量。
     *
     * 坑：回查为空必须跳过而不是报错——向量清理是尽力而为的，残留向量命中后回查不到块是正常现象；
     * 用 Map 收集回查结果时必须提供合并函数，否则重复 key 会让整次提问抛异常。
     *
     * @param question 用户提问；为 null 或纯空白时直接返回空结果，不抛异常
     * @return 检索结果：无命中时 contextText 是空串、sources 是空列表，都不为 null
     * @throws BusinessException 问题向量化失败时抛出（C0200），是否捕获由调用方决定
     */
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

        // ③ 一条 SQL 批量回查文档（同样自动带 deleted_at = 0）。
        //    用 LinkedHashSet 去重：多块可能属于同一篇文档，重复 ID 会让 IN 列表变长且无意义
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

        // ④ 组装：按相似度降序拼资料文本，同时收集溯源
        StringBuilder context = new StringBuilder();
        List<KnowledgeSourceVO> sources = new ArrayList<>();
        int index = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            StudioKnowledgeChunk chunk = chunkByEmbeddingId.get(match.embeddingId());
            if (chunk == null) {
                // 悬挂 ①：块已被逻辑删除（向量残留）。跳过而不是报错：
                // 向量清理是 best-effort，残留条目对应已删除数据属于正常现象
                log.warn("检索命中块的反查为空，跳过该条 | embeddingId={}", match.embeddingId());
                continue;
            }
            StudioKnowledgeDoc doc = docById.get(chunk.getDocId());
            if (doc == null) {
                // 悬挂 ②：文档已被逻辑删除（块还在但文档没了）
                log.warn("检索命中块的文档反查为空，跳过该条 | docId={}", chunk.getDocId());
                continue;
            }

            // 序号从 1 开始并逐条递增：模型回答时可以用「【1】【2】」指代资料，
            // 与 sources 列表的顺序一一对应，前端据此渲染引用角标
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
        // stripTrailing 去掉末尾多余的空行：拼装时每条后面都加了两个换行，
        // 留着会让送进模型的文本尾部多出一段空白
        return new RetrievalResult(context.toString().stripTrailing(), sources);
    }

    // ==================== 管理端操作 ====================

    /**
     * 全量同步：把三类业务数据一次性灌进知识库
     *
     * 本质是 N 次幂等 sync 的循环，因此可以放心重跑：内容没变的会被跳过，第二遍代价接近于零。
     * 刻意不包大事务：单条失败只回滚那一条，其余继续；包大事务会把「50 条成功、1 条失败」
     * 变成「51 条全回滚」，而失败的那条下次重跑就能补上。
     *
     * 坑：本方法返回统计而不是抛异常，「全部失败」也是 HTTP 200 + 一份 failed=51 的统计，
     * 调用方必须看 failed 字段；四个计数互斥且完备，新增分支时要同步维护。
     *
     * @return 统计结果：total / created / rebuilt / skipped / failed；三类数据都为空时全为 0
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
     * 全量同步是可重入的批量操作，一条项目的正文太长导致向量化失败，不该让另外 50 条成员档案也进不去；
     * 失败的那条下次重跑就能补上。计数器用 int[] 而不是五个变量：它要在多个私有方法间传递并累加。
     *
     * 坑：判断顺序必须先 skipped 再 rebuilt，顺序错了会让统计口径悄悄偏移（把跳过算成重建），且不报错。
     *
     * @param sourceType 来源类型，不能为 null
     * @param sourceIds 该类型的全部业务 ID，可为空列表（此时什么都不做）
     * @param counters 计数器数组，长度为 5，下标含义见本类的 IDX_ 常量
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
     * 强制重建全部文档的向量（向量库的恢复手段）
     *
     * 向量库是进程内内存，应用重启后块行还在但向量全没了，此时 sync 与 sync-all 会因「哈希没变」
     * 全部跳过、检索命中 0 条，只有本方法能把向量补回来。它不读业务源也不重新切分，
     * 直接重新嵌入块行里已有的文本，因此对没有业务源的 manual 文档同样有效。
     *
     * 坑：skipped 在这里的含义是「该文档没有可用块」，不是「内容未变」，排查时以日志为准；
     * 每篇文档独立事务、单条失败不中断，失败计入 failed 而不向上抛。
     *
     * @return 统计：rebuilt 是成功重建的文档数，skipped 是没有可用块的文档数，failed 是失败数
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
     * 顺序严格沿用入库管线：先在事务外向量化，再在事务内把新的 embedding_id 写回各块，
     * 提交后清理旧向量。反过来的话，向量化失败会让块上记着一批不存在的向量 ID，检索命中空气。
     *
     * 坑：旧向量 ID 必须在写回之前取出，否则块上的 embedding_id 已被覆盖，旧向量永远留在库里
     * 占着 topK 名额；这里不校验 embeddingIds 的长度，由 storeAll 保证与入参块数一致。
     *
     * @param docId 文档 ID，调用方已保证文档存在
     * @return true 表示已重建；false 表示该文档没有可用块（被逻辑删光的文档会走到这里）
     * @throws BusinessException 向量化失败抛 C0200，写回影响行数不为 1 抛 B0002
     */
    private boolean reindexDoc(long docId) {
        // ① 取该文档的存活块（一条 SQL，按块序号升序：与原文顺序一致）
        List<StudioKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<StudioKnowledgeChunk>()
                        .eq(StudioKnowledgeChunk::getDocId, docId)
                        .orderByAsc(StudioKnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            // 没有块就没有可重建的向量：返回 false 让上层计为 skipped，
            // 而不是抛异常——「文档没有块」是数据状态，不是故障
            return false;
        }

        // ② 向量化：输入就是块正文，不需要读业务源、不需要切分。
        // 这一步是外部 HTTP，绝不能放进事务
        List<TextSegment> segments = chunks.stream()
                .map(chunk -> TextSegment.from(chunk.getContent()))
                .toList();
        List<String> embeddingIds = knowledgeBaseManager.storeAll(segments);

        // 旧向量 ID 先取出来，事务提交后再清理（清理是内存操作、无法回滚）。
        // 必须在写回之前取：写回之后块上的 embedding_id 就被覆盖了
        List<String> oldEmbeddingIds = chunks.stream()
                .map(StudioKnowledgeChunk::getEmbeddingId)
                .filter(Objects::nonNull)
                .toList();

        // ③ 事务内把新的 embedding_id 写回各块
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

        // ④ 提交后 best-effort 清理旧向量
        knowledgeBaseManager.removeVectors(oldEmbeddingIds);
        return true;
    }

    /**
     * 管理端分页查询文档（不含正文）
     *
     * 正文已被切分进块表，拼回来意味着一页 10 条要读几百行块。这个接口只回答「有哪些文档、
     * 各自什么状态」，管理员据此决定删除还是重建。来源类型是闭集，传了约定外的值必须报错，
     * 静默返回空列表会让管理员以为「库里没有这类文档」。
     *
     * 坑：条件构造里的「是否拼接」开关必须与参数判空成对出现，eq 的开关写错会变成
     * {@code source_type = null}，一条都查不出来且不报错。
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页），允许为 null
     * @return 分页结果，记录为 KnowledgeDocVO（不含正文）；没有数据时 records 是空列表
     * @throws BusinessException 来源类型取值非法时抛出（A0401）
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
        // 三个条件的第一个参数都是「要不要拼这段条件」：只有真的传了值才拼，
        // 否则空标题会拼出 LIKE '%%'，空来源类型会变成 source_type = null（一条都查不到）
        wrapper.like(StrUtil.isNotBlank(title), StudioKnowledgeDoc::getTitle, title)
                .eq(StrUtil.isNotBlank(sourceType), StudioKnowledgeDoc::getSourceType, sourceType)
                .eq(query.getStatus() != null, StudioKnowledgeDoc::getStatus, query.getStatus())
                // 默认按创建时间倒序：管理员最关心「最近入库/重建了什么」。
                // 次级键 id 不能省：创建时间是秒级精度，同一秒入库的文档会并列，
                // 没有稳定键时翻页会出现重复或遗漏
                .orderByDesc(StudioKnowledgeDoc::getCreatedAt)
                .orderByDesc(StudioKnowledgeDoc::getId);

        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时一个 pageSize=100000 就能把整张表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        Page<StudioKnowledgeDoc> entityPage = docMapper.selectPage(new Page<>(current, pageSize), wrapper);
        // 先建一个只保留 total/current/size 的空分页，再整体替换记录：
        // 直接 new Page 会丢掉 total，前端的分页组件就翻不了页
        Page<KnowledgeDocVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(),
                entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toDocVO).toList());
        return voPage;
    }

    /**
     * 删除文档（逻辑删文档 + 同事务级联逻辑删切分块，提交后尽力清向量）
     *
     * 文档与块必须同事务一起标记，只删文档会留下「块还在、文档没了」的孤儿行。
     * 向量清理放在提交之后：它是内存操作、无法回滚，放进事务只是假装参与原子性。
     * 不清向量的后果是悬挂命中长期占用 topK 名额，表现为「检索结果变少、相关性下降」且不报错。
     *
     * 坑：向量 ID 必须在删除前取出，块被逻辑删除后查询会自动过滤，事后再也拿不到这些 ID。
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 恒为 true，删除不成功一律抛异常
     * @throws BusinessException 参数非法抛 A0401，文档不存在抛 A0402，影响行数不为 1 抛 B0002
     */
    @Override
    public boolean deleteDoc(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "文档 id 不合法");
        StudioKnowledgeDoc doc = docMapper.selectById(id);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");

        // 向量 ID 必须在删除前取出来：块被逻辑删后查询会自动过滤掉它们，
        // 事后就再也拿不到这些 ID，向量会永远留在内存库里占着 topK 名额
        List<String> embeddingIds = listEmbeddingIds(id);

        transactionTemplate.executeWithoutResult(status -> {
            // 逻辑删 doc（MP 的 @TableLogic → UPDATE ... SET deleted_at = 毫秒时间戳），
            // 影响行数不为 1 说明它在这一瞬间被并发删掉了，属于业务上的操作失败（B0002）
            ThrowUtils.throwIf(docMapper.deleteById(id) != 1, ErrorCode.OPERATION_ERROR, "删除文档失败");
            // 同事务级联逻辑删 chunk：只删 doc 会留下"块还在、文档没了"的孤儿，
            // 它们不会出现在检索结果里（doc 反查为空会被跳过），但会白白占着行与向量。
            // 这一步的返回值不校验：影响行数为 0 只说明这篇文档本来就没有块，不是错误
            chunkMapper.delete(new LambdaQueryWrapper<StudioKnowledgeChunk>()
                    .eq(StudioKnowledgeChunk::getDocId, id));
        });

        // 提交后 best-effort 清向量：失败只告警（残留会在重启后自然消失）
        knowledgeBaseManager.removeVectors(embeddingIds);
        log.info("知识库文档已删除 | docId={} | 清理向量={} 个", id, embeddingIds.size());
        return true;
    }

    /**
     * 重建一篇文档：回到业务源重新读一遍正文，重新切分与向量化
     *
     * 必须回业务源读，不能从切分块拼回正文：拼回来会丢失当初的切分边界，再切一次得到的块
     * 与库里现有的并不一致，而且这种偏差不可逆。手工录入的文档没有业务源可回读，明确拒绝（A0401）。
     * 实现上直接复用 sync，才能保证「重建」与「同步」行为完全一致。
     *
     * 坑：重建是幂等的，源内容与库里一致时返回 skipped=true，管理员点了一次却什么都没发生——
     * 前端要把 skipped 如实展示出来，否则会被当成接口失效。
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 入库结果：skipped=true 内容未变、零 Embedding 调用；rebuilt=true 旧块已被替换
     * @throws BusinessException 参数非法或文档不存在抛 A0401 / A0402，manual 来源抛 A0401
     */
    @Override
    public KnowledgeIngestVO rebuildDoc(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "文档 id 不合法");
        StudioKnowledgeDoc doc = docMapper.selectById(id);
        ThrowUtils.throwIf(doc == null, ErrorCode.NOT_FOUND_ERROR, "文档不存在");

        // manual 没有业务源可回读：明确拒绝并给出可执行的操作路径，绝不静默拼接。
        // 这里用 throw new 而不是 ThrowUtils：提示文案较长且是这一条专属的，
        // 用 ThrowUtils 反而多一层参数包装
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
     * 读取业务数据并拼成正文（源不存在时抛 A0402，并把已有文档标记为失败态）
     *
     * 三类来源的正文模板完全不同，因此用 switch 表达式按类型分支：每个分支必须 yield 出一个标题，
     * 编译器会强制检查没有漏分支；写成「先声明 title 再逐个赋值」漏掉分支只会得到 null 标题，编译期不报错。
     * 源不存在时顺手把已有文档标记为失败态：文档留着「已索引」会让检索命中一篇源已删除的内容。
     *
     * 坑：拼装时必须跳过空字段，空行也会被切进块里白耗 token；manual 分支抛异常只是为了让 switch
     * 满足穷尽性，实际在入口就被拦掉了。
     *
     * @param sourceType 来源类型，调用方已保证不是 manual
     * @param sourceId 业务数据 ID，调用方已保证为正数
     * @return 标题与正文（record），正文至少包含一行内容
     * @throws BusinessException 源数据不存在抛 A0402，来源类型为 manual 抛 A0401，正文为空抛 B0002
     */
    private SourceContent readSource(KnowledgeSourceTypeEnum sourceType, long sourceId) {
        StringBuilder content = new StringBuilder();

        // 用 switch 表达式而不是语句：每个分支必须 yield 出一个标题，
        // 编译器会强制检查「没有漏分支」，比"先声明 title 再逐个赋值"更安全
        // （后者一旦漏掉某个分支，只会得到 null 标题，且编译期不报错）
        String title = switch (sourceType) {
            case PROJECT -> {
                StudioProject project = studioProjectMapper.selectById(sourceId);
                // selectById 自动追加 deleted_at = 0：已逻辑删除的业务数据视为"不存在"
                if (project == null) {
                    // 先标记再抛：抛了异常就走不到标记那一步，而标记正是为了
                    // 让这篇文档不再以「已索引」的身份被检索到
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
                // 级别与类型用中文文案而不是库里的英文值：
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

        // 所有字段都为空时不能入库：切分会得到一个内容空白的块，向量化纯属浪费，
        // 而且模型拿到一个空片段只会更困惑
        ThrowUtils.throwIf(content.length() == 0, ErrorCode.OPERATION_ERROR, "源数据内容为空，无法入库");
        return new SourceContent(title, content.toString());
    }

    /**
     * 解析来源类型（闭集校验，并拒绝 manual）
     *
     * 来源类型决定去查哪张业务表，传了约定外的值必须报错而不是落到默认分支——写错一个字母
     * （例如 "projects"）若被静默忽略，表现是「同步成功但什么都没发生」。
     * 拒绝 manual 是因为它没有业务主键，走 sync 无法幂等，强行允许会让每次同步都新建一份。
     *
     * 坑：错误提示里用 valuesText() 拼接合法取值，手写字符串会在枚举新增取值后变成
     * 「提示说支持三类、实际支持四类」的自相矛盾文案。
     *
     * @param sourceType 请求里的来源类型，可为 null 或空白（都会被判为非法）
     * @return 对应的枚举值，保证不是 manual
     * @throws BusinessException 取值不合法或为 manual 时抛出（A0401）
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
     * 追加一行「标签：值」；值为空则整行省略
     *
     * 知识库正文是拼给模型读的文本，不是给人看的表格：没有值的字段必须整行省略，
     * 留一个「专业：」既消耗 token，又可能让模型误读成「这一项的值是空字符串」。
     *
     * 坑：判空必须用 StrUtil.isBlank，库里的空字符串很常见；换行用 '\n' 而不是
     * System.lineSeparator()，混用会让同一份内容在不同系统上算出不同哈希，破坏幂等。
     *
     * @param content 正文构建器，不能为 null
     * @param label 标签（如「姓名」「级别」），不能为 null
     * @param value 值，可为任意类型；null 或空白时整行跳过
     */
    private void appendLine(StringBuilder content, String label, Object value) {
        if (value == null || StrUtil.isBlank(value.toString())) {
            return;
        }
        content.append(label).append("：").append(value).append('\n');
    }

    /**
     * 证书级别取中文文案（枚举里没有的脏值则退回原值，不丢信息）
     *
     * 这段文本是拼给模型读的，「国家级」比 "national" 更贴近中文语义，用户问「有哪些国家级证书」
     * 时命中率明显更高。翻译只发生在拼装正文这一步，库里的值不变。
     *
     * 坑：查不到时不要返回空串或「未知」，那会让正文少掉这一行的值；原样返回既保留信息，
     * 也能让管理员在检索结果里一眼看出这个值不在枚举内。
     *
     * @param awardLevel 库里的级别值，可为 null
     * @return 中文文案；无法识别时原样返回入参（包括 null）
     */
    private String levelText(String awardLevel) {
        CertificateLevelEnum level = CertificateLevelEnum.of(awardLevel);
        return level == null ? awardLevel : level.getDesc();
    }

    /**
     * 把库里的证书类型英文值翻成中文文案，供 AI 阅读
     *
     * software_copyright 这类下划线标识对中文提问几乎是无意义符号，用户问「有哪些软著」时很可能检索不到。
     * 翻译只发生在拼装正文这一步，是一个纯粹的「喂给模型看的视图」。
     *
     * 坑：查不到时原样返回，不要返回「未知」之类的占位文案——那会把「库里有个我们不认识的值」
     * 从数据里抹掉，而原样返回能让管理员在检索结果里直接看到那个异常值。
     *
     * @param awardType 库里的类型值（如 software_copyright），可为 null
     * @return 中文文案；无法识别时原样返回入参
     */
    private String typeText(String awardType) {
        CertificateTypeEnum type = CertificateTypeEnum.of(awardType);
        return type == null ? awardType : type.getDesc();
    }

    /**
     * 按来源定位已有文档
     *
     * 用 selectOne 而不是 selectList：一个业务主键在知识库里只应有一篇文档，
     * 真出现两篇时 selectOne 会抛异常暴露问题，而 selectList 会静默取第一条。
     * 条件顺序与 idx_source (source_type, source_id) 一致，才能命中索引最左前缀。
     *
     * 坑：查询自动带逻辑删除过滤，「已删除的文档」会被当成不存在——这正是要的：
     * 重新同步时新建一篇，而不是复活一条被管理员删掉的记录。
     *
     * @param sourceType 来源类型，不能为 null
     * @param sourceId 来源业务数据 ID
     * @return 文档；不存在时返回 null，调用方必须判空
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
     * 清理向量无法参与数据库事务，必须在「改写块上的向量 ID」之前先把旧 ID 抄下来。
     * 抄晚了就永远拿不到旧 ID，那些向量会一直占着 topK 名额，且不报任何错。
     *
     * 坑：空的向量 ID 必须跳过，它会被传给向量库的删除接口，而那个接口不认识 null。
     *
     * @param docId 文档 ID，调用方已确认文档存在
     * @return 向量 ID 列表；没有块或向量 ID 都为空时返回空列表
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
     * 新建失败时不建行：给失败的新文档也写上 content_hash，下次重试会因「哈希相同」被幂等判定
     * 直接跳过，失败就永远修不了。标记失败态是为了让管理员在列表页一眼看出「这篇没同步成功」。
     *
     * 坑：本方法刻意不校验影响行数——它是在异常处理路径上调用的，此刻再抛异常会覆盖掉真正的原因。
     *
     * @param existing 已有文档，可为 null（为 null 时什么都不做）
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
     * 源数据已不存在：把已有文档标记为失败（记录「源已删除」）
     *
     * 文档留着「已索引」会让检索命中一篇源已删除的内容，用户溯源时查不到任何业务数据。
     * 副作用是好的：下次源恢复时 sync 会因状态不是已索引而强制重建一次，文档能自动修回来。
     *
     * 坑：只改 status，不动 content_hash——改了哈希，下次重试的幂等判定就失效，变成每次都重建。
     *
     * @param sourceType 来源类型，不能为 null
     * @param sourceId 来源业务数据 ID
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
     * 用 wrapper 而不是 updateById(实体)：调用方手上的实体可能是很久之前查出来的，
     * 整体写回会把别人改过的 title 覆盖成旧值。它也不会触发审计字段填充——失败标记不是「人改的」。
     *
     * 坑：不校验影响行数，因为它用在异常处理路径上，再抛一个「更新失败」会盖掉真正的原因。
     *
     * @param docId 文档 ID，调用方已确认文档存在
     * @param status 目标状态（1-已索引，2-失败）
     */
    private void updateStatus(long docId, int status) {
        docMapper.update(null, new LambdaUpdateWrapper<StudioKnowledgeDoc>()
                .eq(StudioKnowledgeDoc::getId, docId)
                .set(StudioKnowledgeDoc::getStatus, status));
    }

    /**
     * 统一把异常收敛成 C0200（与对话接口同一文案）
     *
     * 向量化失败属于第三方服务问题（C 类），归到 B 类会让告警把「服务商故障」算成「本系统故障」。
     * 对外文案与对话接口一致，不暴露服务商细节。已经是我们自己的业务异常时原样返回，
     * 把 A0401 误改成 C0200 会让用户一直重试一个永远不会成功的请求。
     *
     * @param e 原始异常，不能为 null
     * @return 待抛出的业务异常：原异常本身，或一个新的 C0200 异常（调用方自己 throw）
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
     * 用 SHA-256 而不是 String.hashCode()：后者只有 32 位且碰撞概率不低，一旦碰撞就是
     * 「内容变了却判定为没变」，知识库永远不更新，而且没人会想到去怀疑哈希函数。
     * 十六进制输出 64 个字符，与 DDL 里 content_hash 的列宽一致。
     *
     * 坑：编码必须显式指定 UTF-8，用平台默认编码会让同一段中文在 Windows 与 Linux 上算出不同哈希；
     * 格式化必须用 %02x，少了补零会让不同内容的哈希串长度不一致。
     *
     * @param text 正文，调用方保证非 null
     * @return 64 位十六进制摘要
     * @throws BusinessException JDK 缺少 SHA-256 实现时抛出（B0001，理论上不可能发生）
     */
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

    /**
     * 组装入库结果
     *
     * skipped 与 rebuilt 是「本次做了什么」的结论，库里查不出来，只能由调用方传进来。
     * 两者互斥：skipped 表示零操作、rebuilt 表示替换了旧块、都为 false 表示首次新建，
     * 前端按这两个布尔值渲染提示文案，因此不能合并成一个枚举。
     *
     * @param doc 文档（已落库，不能为 null）
     * @param skipped 是否因内容未变而跳过（此时零 Embedding 调用、零写库）
     * @param rebuilt 是否为重建（替换了旧块、清理了旧向量）
     * @return 入库结果，字段与 VO 定义一一对应
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
     * 大部分字段同名同类型可以整体拷贝，只有 id 需要手动映射成 docId；手写全部字段反而容易漏——
     * 将来实体加一个该展示的字段，copyProperties 会自动带上。
     *
     * 坑：copyProperties 对名字不同或类型不兼容的字段会静默跳过，给 VO 加字段时必须确认名字一致。
     *
     * @param doc 文档实体，不能为 null
     * @return 列表 VO（不含正文——正文在切分块表里）
     */
    private KnowledgeDocVO toDocVO(StudioKnowledgeDoc doc) {
        KnowledgeDocVO vo = new KnowledgeDocVO();
        BeanUtils.copyProperties(doc, vo);
        // id 与 docId 名字不同，copyProperties 拷不到，必须手动映射
        vo.setDocId(doc.getId());
        return vo;
    }

    // ==================== 内部类型 ====================

    /**
     * 从业务数据拼出的「标题 + 正文」
     *
     * readSource 需要一次返回两个值，用 record 而不是普通类或 Map：不可变、不进容器、
     * 访问器是编译期检查的，拼错 key 得到 null 这种事不会发生。
     *
     * 坑：正文里已经包含标题那一行，切分时会连标题一起切进去，这是刻意的——
     * 标题出现在开头能提升该文档的命中率，不要顺手删掉。
     *
     * @param title 文档标题，非空
     * @param content 拼好的正文，至少包含一行内容
     */
    private record SourceContent(String title, String content) {
    }
}
