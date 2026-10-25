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
 * 本类承载四组能力：入库、检索、管理端操作、向量重建。
 * 下面是入库的流程图（另外三条链路各有自己的方法注释）。
 *
 * 完整流程图（改代码前先读这一张）：
 * ① 读源 → 拼正文 → SHA-256 hash                  （无事务；源不存在 → A0402 + 已有文档标 status=2）
 * ② 幂等判定：查 (sourceType, sourceId)
 *      hash 相同且状态为已索引 → 直接返回 skipped=true （零 Embedding 调用、零写库）
 * ③ 切分：按配置的块大小与重叠字符数递归切分          （纯内存）
 * ④ 向量化：embedAll + addAll → embeddingIds        （外部 HTTP！绝不在事务内）
 * ⑤ 事务（TransactionTemplate）：
 *        upsert doc 行(status=1, hash, chunkCount)
 *      + 逻辑删旧块 + 逐条插入新块
 * ⑥ 提交后：清理旧向量                              （best-effort，失败仅告警）
 *
 * 失败路径：④ 或 ⑤ 失败 → 清理本次新向量 + 已有文档标 status=2，
 *          旧块与旧向量原样保留
 *
 * 三个刻意的设计，理由必须记住：
 * - 旧块只在最后（事务内）才删——「先备好新材料，再原子替换」。
 * 向量化失败时旧内容依然可检索，不存在「删了旧的、新的没建好」的不可用窗口。
 * 如果反过来先删旧的，那么一次 Embedding 故障就会让这篇文档彻底查不到；
 * - 不用 EmbeddingStoreIngestor——它一条龙 ingest(document) 很省事，
 * 但不返回 embeddingId，而 chunk 表必须落 embedding_id
 * （检索回查全靠它把向量翻译成块的正文）。所以手写 split → embed → addAll 三步；
 * - 向量库是进程内内存（InMemoryEmbeddingStore），应用重启后向量全部丢失，
 * 而切分块行还在。此时 sync 与 sync-all 会因为「内容哈希没变」全部跳过、
 * 一条都不重建，只有 reindex-all 能把向量补回来。这是本模块最容易误判的故障。
 *
 * 关于失败时"要不要新建一条 status=2 的 doc 行"：
 * 只在已有文档时标记 status=2；新建失败时不建行。
 * 原因是幂等判定依赖 content_hash——如果给失败的新文档也写上新 hash，
 * 下次重试时会因为「hash 相同」被判定为无需重建而直接跳过，
 * 失败就永远修复不了（除非内容再变）。这条坑写在这里，改动时不要"顺手补上"。
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
     * <p>为什么这样设计：容器里没有 TransactionTemplate 这个 bean，
     * 只能自己 new；放在 @PostConstruct 而不是字段初始化，
     * 是因为字段初始化时构造器参数还没赋给 transactionManager。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用已注入的事务管理器构造模板；</li>
     *     <li>赋值给字段，之后所有需要事务的地方共用这一个实例（它是线程安全的）。</li>
     * </ol>
     *
     * <p>容易踩的坑：模板本身不持有事务状态（状态存在 ThreadLocal 里），
     * 所以单例共用是安全的；不要为了「保险」每次调用都 new 一个，
     * 那样只是多创建对象，并没有任何隔离效果。
     */
    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 手工录入一篇知识文档并完成切分 / 向量化 / 入库
     *
     * <p>为什么这样设计：手工录入的正文没有业务主键（它不对应项目、成员、证书里的任何一行），
     * 因此无法判断「这次提交和上次是不是同一篇」。强行用标题去重是错误的——
     * 两篇同名的《使用说明》是完全合法的。所以这里选择「每次调用都新建一篇」，
     * 把「要不要重复录入」的判断交给管理员。这也解释了为什么它不能复用 sync 的幂等逻辑：
     * 幂等的前提是有一个稳定的业务主键可比。
     * 校验全部放在最前面：切分与向量化都要花钱，不该为一个必然失败的请求付账。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验请求体非空——@Valid 只校验字段级约束，整个对象为 null 时不会触发；</li>
     *     <li>trim 后校验标题与正文非空、正文不超长。必须先 trim 再判空：
     *     「   」这种纯空格能通过「非 null」检查，却会切出一个内容全是空白的块，
     *     白花一次向量化费用；</li>
     *     <li>进入与业务同步共用的入库主流程，来源类型固定为 manual、来源 ID 传 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：正文长度有两道校验（DTO 上的 @Size 与本类的常量），缺一不可。
     * 注解只在 Web 入口生效，若这个方法被定时任务或测试直接调用，注解不会执行；
     * 反之常量也替代不了注解，因为注解能在参数绑定阶段就拒绝超大请求体，
     * 不至于先把几 MB 的正文读进内存再判断。
     *
     * @param request 录入请求：title 与 content 均必填，正文不超过 20000 字；
     *                整个对象为 null 时得到 A0401
     * @return 入库结果：docId、标题、切分块数、状态（1 表示已索引）、
     * skipped 恒为 false（手工录入从不跳过）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     * 向量化失败（C0200）、数据库写入影响行数不为 1（B0002）时抛出
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
     * <p>为什么这样设计：同一个业务主键（例如某个项目）在知识库里只应有一篇文档，
     * 所以用 (sourceType, sourceId) 定位，再比对正文的 SHA-256 摘要
     * （内容哈希：同一段文本必然算出同一个 64 位十六进制串，改一个字就会变），
     * 哈希没变且文档已索引时直接返回 skipped=true，不调用 Embedding、不写库——
     * 幂等（重复调用结果一致）省下的不只是时间，Embedding 是按文本量计费的，跳过就是省钱。
     * 读源必须排在幂等判定之前：「内容有没有变」只有读到源才能算。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>① 校验来源类型（闭集）与来源 ID。类型必须是 project / member / certificate，
     *     manual 在这里没有意义——它没有业务来源，走这个入口无法幂等；</li>
     *     <li>② 读业务源并拼正文。源不存在时抛 A0402，同时把已有文档标记为失败态，
     *     记录「源已删除」这件事；</li>
     *     <li>③ 幂等判定：哈希相同「且」状态为已索引，才零成本返回。
     *     两个条件缺一不可（原因见行内注释）；</li>
     *     <li>进入入库主流程（内容变了，或状态不是已索引，都要重建一次）。</li>
     * </ol>
     *
     * <p>容易踩的坑：幂等判断不能只比哈希。失败态（status=2）的文档不会写入新哈希，
     * 于是「源恢复且内容未变」时会命中「哈希相同 → 跳过」，状态就永远停在失败态，
     * 这篇文档再也修不回来。加上状态判断后，失败态即使哈希相同也会走一次复位重建。
     * 另一个坑是「先删旧块再向量化」——那样一次 Embedding 故障就会让文档彻底查不到，
     * 正确顺序是「先备好新材料，再在事务里原子替换」。
     *
     * @param request 同步请求：sourceType 只能取 project / member / certificate（大小写敏感），
     *                sourceId 必须是正整数；两者任一不合法都得到 A0401
     * @return 入库结果：skipped=true 表示内容未变、什么都没做；
     * rebuilt=true 表示替换了旧块；两者都 false 表示首次新建
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法或传了 manual（A0401）、
     * 源数据不存在（A0402，同时把已有文档标记为失败态）、向量化失败（C0200）时抛出
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
     * <p>为什么这样设计：两条入库路径的差别只有「来源类型与来源 ID」，
     * 切分、向量化、事务落库、清理旧向量的顺序完全一样。合成一个方法之后，
     * 「手工录入的文档与同步来的文档在库里长得一样」是结构上成立的，
     * 而不是靠两处代码各写一遍再人工比对。事务边界是这里最关键的取舍：
     * 向量化是外部 HTTP 调用（一次可能十几秒），绝不能包进数据库事务——
     * 包进去会让数据库连接被长时间占用，并发稍高就拖垮连接池。
     * 所以顺序是「先在事务外算好全部向量，再用事务原子替换数据库里的块」。
     *
     * <p>执行步骤（与代码里的 ③④⑤⑥ 标注一一对应）：
     * <ol>
     *     <li>③ 切分：纯内存计算，无事务、无外部调用；</li>
     *     <li>④ 向量化并写向量库：外部 HTTP，失败时只把已有文档标记为失败态，
     *     旧块与旧向量一个字节都没动，因此内容依然可检索；</li>
     *     <li>取出旧向量 ID 备用。必须在事务里删块之前取，否则块被逻辑删除后就查不到了；</li>
     *     <li>⑤ 事务：upsert 文档行 → 逻辑删旧块 → 逐条插入新块。
     *     这三步必须原子，否则中途失败会出现「旧块已删、新块没插完」的空窗；</li>
     *     <li>⑥ 提交后清理旧向量：它是内存操作、无法回滚，放进事务里只是「假装」参与原子性。</li>
     * </ol>
     *
     * <p>容易踩的坑：新向量与块的对应关系靠下标，embeddingIds.get(i) 必须对应 segments.get(i)。
     * 这个前提由 KnowledgeBaseManager.storeAll 保证（它内部已校验数量一致），
     * 本方法不能再自行排序或过滤，否则会出现「命中第 3 条向量、取回第 5 块正文」的错位，
     * 表现为答非所问且极难排查。另外失败路径里清理的是「本次新写入的向量」，
     * 不是旧向量——旧向量要留着，因为旧内容还要继续服务检索。
     *
     * @param title 文档标题，调用方已保证非空
     * @param content 正文，调用方已保证非空且不超长
     * @param sourceType 来源类型（manual / project / member / certificate）
     * @param sourceId 来源业务数据 ID；手工录入传 null（该列允许为空正是为此）
     * @param existing 已有文档，null 表示新建；非 null 表示替换它的块
     * @return 入库结果（块数、状态、是否重建）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 向量化失败（C0200）、
     * 落库影响行数不为 1（B0002）时抛出；参数类异常原样透出
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
     * <p>为什么这样设计：三步、恒定 2 条 SQL——向量检索（无 SQL，纯内存）
     * → 按 embedding_id IN 回查切分块 → 按 doc_id IN 回查文档。
     * 命中 N 条也只查这两次，绝不在循环里逐条回查：那是标准的 N+1
     * （查 1 次列表，再对每条记录各查 1 次），命中 5 条就会变成 11 次查询。
     * 排序必须保持向量检索给出的相似度降序：模型对「最相关的资料放最前」很敏感，
     * 顺序被打乱会明显降低回答质量。因此第 ④ 步遍历的是 matches，
     * 而不是回查出来的列表——回查结果是集合，顺序没有意义。
     * 本方法失败时抛异常而不自己吞掉：「检索失败还能不能继续聊天」是调用方的策略，
     * 问答链路选择降级为「无资料」，但那不该写死在这里。
     *
     * <p>执行步骤（与代码里的 ①②③④ 标注一一对应）：
     * <ol>
     *     <li>① 问题向量化 + 向量检索：空库返回空列表而不是异常，这是日常场景
     *     （内存向量库每次重启都是空的）；</li>
     *     <li>② 一条 SQL 按 embedding_id 批量回查块。向量库里只有向量与块正文，
     *     「这块属于哪篇文档」只在数据库里，所以必须回查；</li>
     *     <li>③ 一条 SQL 按 doc_id 批量回查文档，溯源要展示标题与来源，这些字段只在文档行上；</li>
     *     <li>④ 按相似度降序拼资料文本与溯源列表。</li>
     * </ol>
     *
     * <p>容易踩的坑：回查为空必须跳过而不是报错。向量清理是尽力而为的
     * （删文档时若清理失败，向量库里会残留条目），所以「命中一条向量、
     * 回查却查不到块」是正常现象；一条脏数据不该让整次提问失败。
     * 另外用 Map 收集回查结果时必须提供合并函数，否则同一个 key 出现两次
     * 会让收集动作抛异常，把整次提问打断。
     *
     * @param question 用户提问；为 null 或纯空白时直接返回空结果，不抛异常
     * （提问链路可能传入空串，让它抛异常会把「没问题可检索」变成一次故障）
     * @return 检索结果：无命中时 contextText 是空串、sources 是空列表，两者都不为 null，
     * 调用方用 hit() 判断有没有命中即可
     * @throws com.bhu.runshistudioweb.exception.BusinessException 问题向量化失败时抛出（C0200）；
     * 是否捕获由调用方决定（问答链路会捕获并降级）
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
     * <p>为什么这样设计：它本质就是「N 次幂等 sync 的循环」，因此可以放心重跑——
     * 内容没变的会被幂等跳过（零 Embedding、零写库），跑第二遍的代价接近于零。
     * 刻意不包进一个大事务：每条 sync 自己是一个事务，单条失败只回滚那一条，
     * 其余继续。包大事务既会让向量化期间长时间占着连接，又会把
     * 「50 条成功、1 条失败」变成「51 条全回滚」——而失败的那条下次重跑就能补上。
     * 也刻意不做「批量向量化」优化：一次 sync-all 的耗时主要在 Embedding，
     * 而计费按文本量而不是请求数，把请求攒成一批只能减少往返次数，
     * 却要重写「块与向量 ID 一一对应」的装配逻辑，收益与风险不成比例。
     *
     * <p>执行步骤（与代码里的 ①② 标注一一对应）：
     * <ol>
     *     <li>① 三类源各查一次 ID 列表，只 select 主键、不把正文拉回内存
     *     （正文由每条 sync 自己按需读），避免一次把几十篇长文读进堆；</li>
     *     <li>② 逐条走 sync，把结果累加进计数器（总数 / 新建 / 重建 / 跳过 / 失败）；</li>
     *     <li>组装并返回统计，同时打一条汇总日志。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回统计而不是抛异常，所以「全部失败」也会是
     * HTTP 200 + 一份 failed=51 的统计。调用方（管理端页面）必须看 failed 字段，
     * 不能只看「接口没报错」就以为同步成功。另一个坑是五个计数互斥且完备：
     * created + rebuilt + skipped + failed 一定等于 total，新增分支时必须同步维护它，
     * 否则运维会按这些数字判断「有没有漏」。
     *
     * @return 统计结果：total / created / rebuilt / skipped / failed；
     * 三类数据都为空时 total 为 0，其余字段也都是 0，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不主动抛业务异常：
     * 每条 sync 的失败都被 syncBatch 累计成 failed 计数，不向上传播。
     * 只有数据库整体不可用时才会以运行时异常的形式抛出，由全局异常处理器兜成 B0001
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
     * <p>为什么这样设计：全量同步是「可重入的批量操作」，因此单条失败不该中断整批。
     * 一条项目的正文太长导致向量化失败，不该让另外 50 条成员档案也进不去。
     * 失败的那条下次重跑本接口就能补上——因为每条都是幂等的，重跑没有副作用。
     * 计数器用 int[] 而不是五个独立变量：它需要在多个私有方法之间传递并累加，
     * 数组是引用传递，最省事也最不容易漏掉某个槽位。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先把总数加一（无论成败，这条都「尝试过」）；</li>
     *     <li>组一个 sync 请求并调用，成功则按 skipped / rebuilt / created 三选一累加；</li>
     *     <li>任何异常都只累加 failed 并记日志，不向外抛。</li>
     * </ol>
     *
     * <p>容易踩的坑：判断顺序必须是「先 skipped 再 rebuilt」。
     * 正常情况下两者不会同时为 true，但若将来有人改动 sync 的返回值，
     * 顺序错了会让统计口径悄悄偏移（例如把跳过算成重建），而这种偏移
     * 不会报错，只会让运维对着一份错误的汇总排查半天。
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
     * <p>为什么这样设计：向量库是进程内内存，应用每次重启，切分块行还在但向量全没了。
     * 此时 sync 与 sync-all 会因为「内容哈希没变」全部跳过、一条都不重建，
     * 检索命中 0 条——只有本方法能把向量补回来。它不读业务源、也不重新切分：
     * 向量化的输入就是 chunk 行里已有的文本，直接「重新嵌入现有块」即可。
     * 这带来两个好处：快（省掉读源与切分），且对 manual 类文档同样有效
     * （它没有业务源可读，sync 那条路径救不了它）。
     * 每篇文档独立事务、单条失败不中断，与 sync-all 同一风格。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>① 一条 SQL 取全部未删除文档的主键（正文在块行里，这里不需要）；</li>
     *     <li>② 逐文档重建：读该文档的存活块 → 重新向量化 → 事务内写回新的向量 ID
     *     → 提交后清理旧向量；</li>
     *     <li>③ 返回统计。本方法不新建任何文档，因此 created 恒为 0。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要指望 sync-all 能替代它。sync-all 的幂等判定是
     * 「哈希没变就跳过」，而重启后哈希确实没变，于是它一条都不会重建——
     * 看起来接口成功、检索却始终为空，这是本模块最容易误判的故障。
     * 另外 skipped 的含义在这里是「该文档没有可用块」，不是「内容未变」，
     * 两者都计入 skipped，但原因完全不同，排查时以日志为准。
     *
     * @return 统计：rebuilt 是成功重建向量的文档数，skipped 是没有可用块而跳过的文档数，
     * failed 是失败数；没有文档时三项都是 0，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不主动抛业务异常：
     * 单篇重建的失败被累计成 failed 计数，不向上传播。
     * 只有数据库整体不可用时才会以运行时异常的形式抛出，由全局异常处理器兜成 B0001
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
     * <p>为什么这样设计：顺序严格沿用入库管线——先向量化（外部 HTTP，事务外）
     * → 事务内把新的 embedding_id 写回各块 → 提交后清理旧向量。
     * 顺序反过来的话，一旦向量化失败，块上记的就是一批不存在的向量 ID，
     * 检索会命中空气；而按这个顺序，失败时块仍指向旧向量，旧内容依然可用。
     *
     * <p>执行步骤（与代码里的 ①②③④ 标注一一对应）：
     * <ol>
     *     <li>① 取该文档的存活块，按块序号升序（与原文顺序一致）；</li>
     *     <li>② 把块正文包成 TextSegment 后批量向量化。输入就是块正文，
     *     不需要读业务源、不需要切分；</li>
     *     <li>③ 事务内逐块写回新的向量 ID。按下标一一对应，不能排序或过滤；</li>
     *     <li>④ 提交后尽力清理旧向量（失败只告警）。</li>
     * </ol>
     *
     * <p>容易踩的坑：旧向量 ID 必须在写回之前取出来，否则块上的 embedding_id
     * 已经被覆盖，旧向量就再也找不到、永远留在向量库里占着 topK 名额。
     * 另外本方法不校验 embeddingIds 的长度：它由 storeAll 保证与入参块数一致，
     * 在这里再判一次只会掩盖上游的问题。
     *
     * @param docId 文档 ID，调用方已保证文档存在
     * @return true 表示已重建；false 表示该文档没有可用块（被逻辑删光的文档会走到这里）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 向量化失败（C0200）、
     * 写回影响行数不为 1（B0002）时抛出，由调用方（reindexAll）累计为 failed
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
     * <p>为什么这样设计：正文不在文档行里（它已经被切分进块表），
     * 拼回来再返回意味着一页 10 条要读几百行块。这个接口只回答
     * 「有哪些文档、各自什么状态」，管理员据此决定删除还是重建，不需要看正文。
     * 来源类型是闭集（只允许四个固定取值），传了约定外的值必须报错而不是
     * 静默返回空列表——否则管理员会以为「库里没有这类文档」，排查方向被彻底带偏。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>请求对象为 null 时兜一个空对象，省掉后面每处判空；</li>
     *     <li>校验来源类型（非空时必须是合法取值）；</li>
     *     <li>组装条件：标题模糊、来源类型与状态精确匹配，三个条件都只在「有传值」时生效；</li>
     *     <li>归一化分页参数并查询，转成 VO 分页。</li>
     * </ol>
     *
     * <p>容易踩的坑：条件构造里的「是否拼接」开关必须与参数判空成对出现。
     * 把 like 的布尔开关写成常量 true，空标题就会拼出 title LIKE '%%'——
     * 虽然结果碰巧正确，但会白白多一个用不上索引的条件。
     * 更危险的是 eq 的开关写错：不传来源类型时会变成 source_type = null，
     * 一条都查不出来，而且不报错。
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页参数），
     *                允许为 null，此时按第一页 10 条返回
     * @return 分页结果，记录为 KnowledgeDocVO（不含正文）；没有数据时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 来源类型取值非法时抛出（A0401）
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
     * <p>为什么这样设计：数据库里只做「标记删除」（把 deleted_at 写成毫秒时间戳，
     * 查询时由框架自动追加 deleted_at = 0 过滤），因此文档与切分块必须在同一个事务里
     * 一起标记——只删文档会留下「块还在、文档没了」的孤儿行。这些孤儿行虽然
     * 不会出现在检索结果里（文档反查为空会被跳过），但会一直占着行与向量。
     * 向量清理放在事务提交之后：它是内存操作、无法回滚，放进事务里只是"假装"参与原子性。
     * 为什么必须清向量：检索层对「反查为空」的容忍只是兜底。不清理的话，
     * 这些悬挂命中会长期占用 topK 名额（topK=5 里混进 3 条已删文档的块），
     * 表现为「检索结果变少、相关性下降」，而且没有任何报错。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验 ID 并查文档是否存在，不存在直接 A0402。
     *     先查一次而不是直接删，是为了区分「不存在」与「删除失败」——
     *     直接删只会得到影响行数 0，无法告诉调用方到底是哪种情况；</li>
     *     <li>删除前先把向量 ID 取出来。块被逻辑删除后查询会自动过滤，
     *     事后就再也拿不到这些 ID，向量也就永远清不掉了；</li>
     *     <li>事务内标记删除文档与全部切分块，两者同生共死；</li>
     *     <li>提交后清理向量：尽力而为，失败只告警（残留会在应用重启后自然消失）。</li>
     * </ol>
     *
     * <p>容易踩的坑：向量 ID 必须在删除前取。若写成「先删库、再查向量 ID」，
     * 查询会因为逻辑删除条件而返回空列表，清理动作变成空转——而且完全不报错。
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 恒为 true。删除不成功一律抛异常，没有「返回 false 表示失败」的分支
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（A0401）、
     * 文档不存在或已删除（A0402）、删除影响行数不为 1（B0002）时抛出
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
     * <p>为什么这样设计：重建必须回业务源读，不能从切分块拼回正文——
     * 拼回来会丢失当初的切分边界，再切一次得到的块与库里现有的块并不一致，
     * 相当于用一份被自己加工过的二手文本重新入库，而且这种偏差不可逆。
     * 手工录入的文档没有业务源可回读，因此明确拒绝（A0401）并引导到
     * 「删除后重新手工录入」，而不是静默拼接或什么都不做。
     * 实现上直接复用 sync：读源 → 比对哈希 → 未变则跳过、变了则重建。
     * 复用而不是另写一套，才能保证「重建」与「同步」的行为完全一致。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验 ID 并查文档，不存在 A0402；</li>
     *     <li>拒绝 manual 来源。这个判断必须排在调用 sync 之前，
     *     否则会带着 null 来源 ID 进去，报出一个与真实原因无关的错误；</li>
     *     <li>校验来源 ID 非空；</li>
     *     <li>组装 sync 请求并复用同一条链路。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要以为「重建」一定会真的重建。它是幂等的——
     * 源内容与库里一致时返回 skipped=true，管理员点了一次却什么都没发生。
     * 这是正确行为，前端需要把 skipped 如实展示出来，否则会被当成接口失效。
     *
     * @param id 文档 ID；小于等于 0 时得到 A0401
     * @return 入库结果：skipped=true 表示内容未变、本次零 Embedding 调用；
     * rebuilt=true 表示旧块已被替换
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法或文档不存在
     * （A0401 / A0402）、manual 来源不支持重建（A0401）、源数据已删除（A0402）时抛出
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
     * <p>为什么这样设计：三类来源的正文模板完全不同（项目取标题/摘要/正文，
     * 成员取姓名/届别/专业/方向/简介，证书取名称/级别/类型/日期），
     * 所以用一个 switch 表达式按类型分支。选表达式而不是语句，是因为
     * 每个分支必须 yield 出一个标题，编译器会强制检查「没有漏分支」；
     * 若写成「先声明 title 再逐个赋值」，一旦漏掉某个分支只会得到 null 标题，
     * 而且编译期不报错，直到入库时才会因为标题为空而莫名其妙地失败。
     * 源不存在时不只是抛异常，还要顺手把已有文档标记为失败态——
     * 文档留着「已索引」会让检索命中一篇源已删除的内容，
     * 用户点进去溯源查不到任何业务数据，那比「查不到」更糟。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按来源类型进对应分支，用 selectById 读源。
     *     它自带逻辑删除过滤，所以已删除的业务数据在这里就是 null；</li>
     *     <li>源为 null 时先标记失败态、再抛 A0402（顺序不能反：抛了异常就走不到标记那一步）；</li>
     *     <li>逐行拼装非空字段，最后 yield 出标题；</li>
     *     <li>正文长度为 0 时抛 B0002——所有字段都为空说明这条业务数据没有任何可用内容，
     *     继续往下会切出空块并白花一次向量化费用。</li>
     * </ol>
     *
     * <p>容易踩的坑：拼装时必须跳过空字段，不能写「专业：」这种只有标签的空行。
     * 空行也会被切进块里，白白消耗 token，还可能让模型误以为「这一项就是空的」。
     * 另外 manual 分支写成抛异常而不是返回空：它只是为了让 switch 满足穷尽性要求，
     * 实际在入口（resolveSourceType）就被拦掉了。
     *
     * @param sourceType 来源类型，调用方已保证不是 manual
     * @param sourceId 业务数据 ID，调用方已保证为正数
     * @return 标题与正文（record），正文至少包含一行内容
     * @throws com.bhu.runshistudioweb.exception.BusinessException 源数据不存在（A0402，
     * 同时把已有文档标记为失败态）、来源类型为 manual（A0401）、
     * 拼出的正文为空（B0002）时抛出
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
     * <p>为什么这样设计：来源类型决定「去查哪张业务表」，它是一个闭集，
     * 传了约定外的值必须报错而不是落到默认分支。写错一个字母（例如 "projects"）
     * 若被静默忽略，表现是「同步成功但什么都没发生」，排查起来极其费时。
     * 拒绝 manual 是业务上的必要限制：它没有业务主键，走 sync 无法幂等，
     * 强行允许会让同一篇文档在每次同步时都新建一份。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按取值查枚举，查不到抛 A0401 并附上全部合法取值；</li>
     *     <li>查到但等于 manual 时也抛 A0401，并给出正确的入口。</li>
     * </ol>
     *
     * <p>容易踩的坑：错误提示里用 valuesText() 拼接合法取值，而不是手写字符串。
     * 手写的话，将来枚举新增一个取值时提示就会漏掉它，变成
     * 「提示说支持三类、实际支持四类」这种自相矛盾的文案。
     *
     * @param sourceType 请求里的来源类型，可为 null 或空白（都会被判为非法）
     * @return 对应的枚举值，保证不是 manual
     * @throws com.bhu.runshistudioweb.exception.BusinessException 取值不合法或为 manual 时抛出（A0401）
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
     * <p>为什么这样设计：知识库正文是「拼给模型读的文本」，不是给人看的表格，
     * 因此没有值的字段必须整行省略，而不是留一个「专业：」这样的空标签。
     * 空行同样会被切分器切进块里，既消耗 token，又可能让模型把
     * 「这一项没有值」误读成「这一项的值是空字符串」。
     * 参数类型是 Object 而不是 String：调用方会传 Integer（届别）之类的字段，
     * 用 Object 接住可以省掉每处 String.valueOf。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>值为 null 或 toString 后为空白时直接返回，不写任何内容；</li>
     *     <li>否则追加「标签 + 中文冒号 + 值 + 换行」。</li>
     * </ol>
     *
     * <p>容易踩的坑：判空必须用 StrUtil.isBlank 而不是只判 null。
     * 数据库里的空字符串很常见（例如成员的方向字段被填成 ""），
     * 只判 null 会拼出「方向：」这样的空行。另外换行符用 '\n' 而不是
     * System.lineSeparator()：切分器与模型都不关心平台差异，
     * 而混用会让同一份内容在不同操作系统上算出不同的哈希，破坏幂等。
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
     * <p>为什么这样设计：库里存的是 national / provincial / municipal 这类英文标识，
     * 而这段文本是拼给向量模型与对话模型读的——「国家级」比 "national"
     * 更贴近中文语义，用户问「有哪些国家级证书」时命中率明显更高。
     * 翻译只发生在「拼装知识库正文」这一步，库里的值本身不变，
     * 因此展示层与业务逻辑都不受影响。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按取值查枚举；</li>
     *     <li>查不到就原样返回入参。脏数据不该让整篇文档同步失败——
     *     让模型看到原始值总比抛异常好。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在查不到时返回空串或「未知」。那会让正文里少掉这一行的值，
     * 而原文其实是有的；原样返回至少保留了信息，也让管理员在检索结果里
     * 一眼看出「这个级别值不在枚举内」。
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
     * <p>为什么这样设计：模型对「软著」「竞赛」这类中文词的语义理解明显好于
     * software_copyright 这种下划线标识——后者对中文提问来说几乎是一个无意义的符号，
     * 用户问「有哪些软著」时很可能检索不到。翻译只发生在「拼装知识库正文」这一步，
     * 库里的值本身不变，因此这是一个纯粹的「喂给模型看的视图」。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按取值查枚举；</li>
     *     <li>查不到时原样返回：脏数据不该让整篇文档同步失败。</li>
     * </ol>
     *
     * <p>容易踩的坑：与级别同理，不要返回「未知」之类的占位文案。
     * 那样会把「库里有一个我们不认识的值」这件事从数据里抹掉，
     * 而原样返回能让管理员在检索结果里直接看到那个异常值。
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
     * <p>为什么这样设计：幂等判定必须先知道「这条业务数据在库里有没有对应的文档」，
     * 而 (sourceType, sourceId) 这一对是它唯一的业务身份。
     * 用 selectOne 而不是 selectList：一个业务主键在知识库里只应有一篇文档，
     * 用 selectOne 能在真的出现两篇时立刻暴露（它会抛异常），
     * 而 selectList 会静默取第一条，把数据异常藏起来。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按 source_type 与 source_id 两个条件等值查询；</li>
     *     <li>条件的书写顺序与联合索引的列顺序保持一致，才能命中索引最左前缀。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法的查询自动带逻辑删除过滤，因此「已删除的文档」
     * 会被当成「不存在」——这正是我们要的：重新同步时会新建一篇，
     * 而不是去复活一条被管理员删掉的记录。
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
     * <p>为什么这样设计：向量库是内存结构，清理动作无法参与数据库事务，
     * 所以必须在「改写块上的向量 ID」之前，先把旧的 ID 抄下来。
     * 抄晚了（例如写完新 ID 再查）就永远拿不到旧 ID，那些向量会一直留在库里
     * 占着 topK 名额，表现为「检索结果变少、相关性下降」且不报任何错。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按 doc_id 查出该文档的全部块；</li>
     *     <li>逐个过滤掉空的向量 ID 后收集。空值必须跳过：它会被传给向量库的删除接口，
     *     而那个接口不认识 null，可能整批删除失败。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里没有分页。一篇文档的块数由正文长度决定，通常是几个到几十个，
     * 一次全查是合理的；若将来允许录入几十万字的文档，才需要考虑分批。
     *
     * @param docId 文档 ID，调用方已确认文档存在
     * @return 向量 ID 列表；文档没有块、或块的向量 ID 都为空时返回空列表，不会返回 null
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
     * <p>为什么这样设计：新建失败时不建行，这个取舍在类注释里有完整理由——
     * 若给失败的新文档也写上 content_hash，下次重试会因为「哈希相同」被幂等判定
     * 直接跳过，失败就永远修复不了（除非内容再变）。所以这里只处理 existing 非空的情况。
     * 标记失败态的意义是让管理员在列表页一眼看出「这篇没同步成功」，
     * 而不是等到检索不到内容时才发现。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>existing 为 null 时直接返回（新建失败没有行可标）；</li>
     *     <li>把 status 改成失败态；</li>
     *     <li>打一条 warn 日志，带上文档 ID 与来源，便于定位是哪篇。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法自己吞掉了「更新失败」的可能性——updateStatus 不校验影响行数。
     * 这是刻意的：它是在异常处理路径上调用的，此刻再抛一个异常会覆盖掉真正的失败原因
     * （向量化失败），让排查方向跑偏。状态没标上最多是显示不准，不影响主流程。
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
     * <p>为什么这样设计：为什么必须标记而不是无视——文档留着「已索引」会让检索
     * 命中一篇源已删除的内容，用户点进去溯源会查不到任何业务数据，
     * 那是比「查不到」更差的体验。另外这个标记还会带来一个副作用：
     * 下次源数据恢复时，sync 会因为状态不是已索引而强制重建一次
     * （即使内容哈希没变），于是文档能自动修回来。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按来源查已有文档，查不到说明这篇业务数据从来没同步过，直接返回；</li>
     *     <li>把 status 改成失败态并打日志。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只改 status，不动 content_hash。
     * 若把哈希也清空或改写，下次重试时的幂等判定就会失效，
     * 变成「每次同步都重建」，白白消耗 Embedding 额度。
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
     * <p>为什么这样设计：用 wrapper 更新而不是 updateById(实体)，因为这里只需要改一个字段。
     * updateById 会把整个实体写回去，而调用方手上的实体可能是很久之前查出来的，
     * 中间别人改过的 title 会被旧值覆盖；wrapper 形式只 set 指定的列，
     * 不存在这个问题。它也不会触发审计字段填充——失败标记不是「人改的」，
     * 不该把 updated_by 写成某个操作人。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按主键定位；</li>
     *     <li>set status 后执行更新。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验影响行数。它被用在异常处理路径上
     * （向量化失败、源数据丢失），此刻再抛一个「更新失败」会盖掉真正的原因。
     * 调用方也不需要知道它是否成功——状态显示不准远不如保留原始异常重要。
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
     * <p>为什么这样设计：向量化走的是外部 AI 服务，它失败属于第三方服务问题（C 类），
     * 不是本系统的 bug——归到 B 类会让告警把「服务商故障」算成「本系统故障」，
     * 污染可用性指标，也会让排查方向被带偏。但对外的文案必须与对话接口保持一致，
     * 不暴露服务商名称与内部细节：用户不需要知道是哪家云服务挂了。
     * 已经是我们自己的业务异常（例如参数错误）时原样返回，
     * 不要把 A0401 误改成 C0200——那会把「用户传错了」说成「AI 服务不可用」，
     * 用户会一直重试一个永远不会成功的请求。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>判断是不是业务异常，是就原样返回；</li>
     *     <li>否则包成 C0200 返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回异常而不是抛出异常，调用方必须自己 throw。
     * 这样设计是为了让调用点能写成 throw toEmbeddingFailure(e)，
     * 一眼看出「这里在转换异常」，而不是被一个不返回的方法名误导。
     *
     * @param e 原始异常，不能为 null
     * @return 待抛出的业务异常：原异常本身，或一个新的 C0200 异常
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
     * <p>为什么这样设计：幂等判定靠「内容有没有变」，而这必须用一个足够可靠的指纹。
     * 用 SHA-256 而不是 String.hashCode()：后者只有 32 位且碰撞概率不低，
     * 一旦碰撞就会「内容变了却判定为没变」，导致知识库永远不更新——
     * 这种错误极其隐蔽（文档看起来一切正常，就是内容过时），
     * 而且不会有人想到去怀疑一个哈希函数。
     * 十六进制输出是 64 个字符，正好与 DDL 里 content_hash 的列宽一致。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>取 SHA-256 摘要器；</li>
     *     <li>按 UTF-8 编码正文后计算摘要。编码必须显式指定：
     *     用平台默认编码会让同一段中文在 Windows 与 Linux 上算出不同的哈希，
     *     表现为「代码没改，同步却一直认为内容变了」；</li>
     *     <li>逐字节格式化成两位十六进制并拼接。</li>
     * </ol>
     *
     * <p>容易踩的坑：格式化必须用 %02x 而不是 %x。少了补零，
     * 小于 0x10 的字节会输出一位，导致不同内容的哈希串长度不一致，
     * 而且这种哈希看起来依然「像那么回事」，极难发现。
     * 另外本方法假定 text 非 null——调用方（ingest / sync）传的都是已校验过的正文。
     *
     * @param text 正文，调用方保证非 null
     * @return 64 位十六进制摘要
     * @throws com.bhu.runshistudioweb.exception.BusinessException JDK 缺少 SHA-256 实现时抛出
     * （B0001，理论上不可能发生）
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
     * <p>为什么这样设计：skipped 与 rebuilt 是「本次做了什么」的结论，
     * 库里查不出来（库里只有当前状态），所以必须由调用方作为参数传进来。
     * 两者是互斥的语义：skipped=true 表示零操作，rebuilt=true 表示替换了旧块，
     * 都为 false 表示首次新建。调用方（管理端页面）靠这三个状态决定提示文案，
     * 因此不能把它们合并成一个枚举——前端已经在按这两个布尔值渲染了。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>新建 VO，逐字段赋值；</li>
     *     <li>返回。docId 是雪花 ID，由全局 JSON 配置序列化成字符串，这里不必加注解。</li>
     * </ol>
     *
     * <p>容易踩的坑：chunkCount 与 status 都是 Integer 而不是 Long——
     * 它们是计数与状态码，不是 ID，用 Long 会被全局配置序列化成字符串，
     * 前端拿到 "3" 就没法直接做数值比较了。
     *
     * @param doc 文档（已落库，不能为 null；调用方在插入后会重新查一次）
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
     * <p>为什么这样设计：实体与 VO 的字段大部分同名同类型（title / sourceType /
     * status / chunkCount / 时间），可以整体拷贝；只有 id 需要手动映射成 docId——
     * VO 里写 docId 是为了让前端一眼看出它是文档 ID，而不是某个业务主键。
     * 手写全部字段反而更容易漏：将来实体加一个该展示的字段时，
     * copyProperties 会自动带上，而手写清单会漏。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>新建 VO 并整体拷贝同名同类型的字段；</li>
     *     <li>手动补 docId（名字不一致，拷贝不到）。</li>
     * </ol>
     *
     * <p>容易踩的坑：copyProperties 对名字不同或类型不兼容的字段会静默跳过，不报错。
     * 所以给 VO 加字段时必须确认：字段名与实体一致，否则要手动赋值，
     * 否则前端会拿到 null 而不是报错。
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
     * <p>为什么这样设计：readSource 需要一次返回两个值（标题与正文），
     * 而 Java 没有多返回值。用 record 而不是普通类：它只是数据载体，
     * 不可变、不需要 setter、也不需要进 Spring 容器。
     * 也不做成 Map 或 Object[]：那样调用方要按字符串或下标取值，
     * 拼错一个 key 就得到 null，而 record 的访问器是编译期检查的。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>构造时按顺序传入标题与正文；</li>
     *     <li>调用方用 title() / content() 取值，两个访问器由编译器自动生成。</li>
     * </ol>
     *
     * <p>容易踩的坑：正文里已经包含了标题那一行（由 appendLine 拼入），
     * 所以 ingest 切分时会连标题一起切进去。这是刻意的：
     * 标题出现在正文开头能提升该文档在相关问题上的命中率，
     * 不要「顺手」把它从正文里删掉。
     *
     * @param title 文档标题，非空
     * @param content 拼好的正文，至少包含一行内容
     */
    private record SourceContent(String title, String content) {
    }
}
