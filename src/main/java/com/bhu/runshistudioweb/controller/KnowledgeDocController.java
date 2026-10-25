package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeDocQueryRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeManualIngestRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeRebuildRequest;
import com.bhu.runshistudioweb.model.dto.knowledge.KnowledgeSyncRequest;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeDocVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeIngestVO;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSyncAllVO;
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库文档接口（HTTP 入口，全部要求 admin 角色）
 *
 * <p>本类覆盖 RAG 入库的全部管理能力：入库（手工录入 / 业务同步）、
 * 管理端（全量同步 / 分页列表 / 删除 / 重建）、向量重建。它们都要求 admin，
 * 因此合并放在同一个 Controller 里，白名单零改动。
 *
 * <p>为什么必须限管理员：这些接口会真实花钱——每重建一次就要重算一遍 Embedding
 * （把文本变成向量的外部调用，按文本量计费），而正文上限是 20000 字，可切出几十个块、
 * 几十次向量化。开放给普通用户等于把 API 账单的开关交出去，
 * 因此这里用 {@code @SaCheckRole(ADMIN)} 卡死，与白名单无关。
 *
 * <p>为什么白名单零改动：本路径不在 Sa-Token 白名单里，而全局拦截器是
 * 「默认拒绝」策略——未登录访问得到 A0201，登录了但角色不够得到 A0301。
 * 这两条都是既有机制自动覆盖的，不需要额外配置。
 *
 * <p>Controller 依旧只做三件事：接参、调 Service、包响应。编排
 * （切分 → 向量化 → 事务落库 → 清理旧向量）全在 KnowledgeDocService 里。
 *
 * @author shaoshing
 */
@Tag(name = "知识库文档模块", description = "管理员手工录入与业务来源同步（RAG 入库）")
@RestController
@RequestMapping("/knowledge/doc")
@RequiredArgsConstructor
public class KnowledgeDocController {

    private final KnowledgeDocService knowledgeDocService;

    /**
     * 手工录入一篇知识库文档
     *
     * <p>为什么这样设计：每次调用都新建一篇、不幂等。手工录入的正文没有业务主键，
     * 无法判断「这次提交和上次是不是同一篇」，因此把去重的决定权交给人。
     * 本接口会触发真实计费（切分后每块都要算一次向量），所以限管理员。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空：整个请求体为 null 时 {@code @Valid} 不会触发，
     *     后面取值会抛空指针变成 500，这里提前给出 A0401；</li>
     *     <li>② 交给 Service 完成「校验 → 切分 → 向量化 → 事务落库」并包成统一响应体。
     *     向量化是外部 HTTP 调用，发生在事务之外，因此本接口耗时可能是秒级到十几秒，
     *     前端要给足超时时间。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code @Valid} 只校验字段级约束，管不到「请求体整体为 null」。
     * 少了第 ① 步，一个空 body 的请求会变成 B0001「系统错误」，
     * 而真实原因只是少传了参数。
     *
     * @param request 录入请求：title 与 content 均必填，正文不超过 20000 字；
     *                整个对象为 null 时得到 A0401
     * @return 统一响应体，data 里是 docId、切分块数、状态（1 表示已索引）、
     *         skipped（恒为 false，手工录入从不跳过）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     * 向量化失败（C0200）、未登录或角色不足（A0201 / A0301）时抛出
     */
    @PostMapping("/manual")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】手工录入知识库文档", description = "每次新建、不幂等；正文 ≤20000 字")
    public BaseResponse<KnowledgeIngestVO> ingestManual(
            @RequestBody @Valid KnowledgeManualIngestRequest request) {
        // @Valid 不覆盖"请求体整体为 null"，兜一层防 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(knowledgeDocService.ingestManual(request));
    }

    /**
     * 同步一条业务数据到知识库（幂等、可重建）
     *
     * <p>为什么这样设计：同一个业务主键在知识库里只应有一篇文档，因此靠
     * (来源类型, 来源 ID) 定位，再比对内容哈希：内容没变直接返回 skipped=true，
     * 零 Embedding 调用、零写库。这让「同步」可以放心重复调用，
     * 也让「内容改了就重跑一次」成为标准操作。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，避免空请求体变成 500；</li>
     *     <li>② 交给 Service：校验来源类型 → 读业务源拼正文 → 比对哈希 →
     *     跳过或重建。源数据不存在时返回 A0402，同时把已有文档标记为失败态，
     *     这样管理员在列表里能看出「这篇文档的来源已被删除」。</li>
     * </ol>
     *
     * <p>容易踩的坑：来源类型是大小写敏感的闭集，传 manual 会被拒（A0401）。
     * 手工录入必须走 /manual——它没有业务来源，用 sync 既无法定位也无法幂等。
     *
     * @param request 同步请求：sourceType ∈ project / member / certificate，sourceId 必填；
     *                整个对象为 null 时得到 A0401
     * @return 统一响应体，data 里 skipped=true 表示内容未变、零成本跳过；
     *         rebuilt=true 表示替换了旧块；两者都为 false 表示首次新建
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法或传了 manual（A0401）、
     * 源数据不存在（A0402）、向量化失败（C0200）时抛出
     */
    @PostMapping("/sync")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】同步业务数据到知识库", description = "内容哈希未变则跳过；源不存在返回 A0402 并标记失败")
    public BaseResponse<KnowledgeIngestVO> sync(@RequestBody @Valid KnowledgeSyncRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(knowledgeDocService.sync(request));
    }

    // ==================== 批量与管理 ====================

    /**
     * 全量同步：把三类业务数据一次性灌进知识库
     *
     * <p>为什么这样设计：它本质是「N 次幂等 sync 的循环」，因此可重入——
     * 内容没变的会被幂等跳过，跑第二遍几乎零成本。单条失败不中断：
     * 失败计入统计并记日志，接口照常返回，一条项目正文太长导致向量化失败，
     * 不该让另外 50 条成员档案也跟着进不去。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参，直接调 Service 触发批量同步；</li>
     *     <li>② 把统计结果包成统一响应体。返回的是统计而不是「成功/失败」，
     *     因为批量操作里「部分成功」才是常态。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个接口没有请求体，也就没有任何「预检」能挡住误操作。
     * 它的耗时与费用随数据量线性增长（每条都要算向量），
     * 在数据量大时不要放在页面加载路径上，应当由管理员显式触发。
     *
     * @return 统一响应体，data 里是统计：created + rebuilt + skipped + failed == total
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）时抛出
     */
    @PostMapping("/sync-all")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】全量同步知识库", description = "三类业务数据一次性入库；幂等可重入，单条失败不中断")
    public BaseResponse<KnowledgeSyncAllVO> syncAll() {
        return ResultUtils.success(knowledgeDocService.syncAll());
    }

    /**
     * 文档分页列表（不含正文）
     *
     * <p>为什么这样设计：正文不在文档行里（它已经切分进块表），拼回来再返回意味着
     * 一页 10 条要读几百行块。这个接口只回答「有哪些文档、各自什么状态」，
     * 管理员据此决定删除还是重建，不需要看到正文。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接接收查询对象（POST + JSON body，方便前端一次传多个筛选条件）；</li>
     *     <li>② 交给 Service 做「来源类型闭集校验 + 条件组装 + 分页查询」并包响应。
     *     参数归一化（null 按第一页 10 条、上限 50）也在 Service 里，Controller 不重复。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里刻意不加 {@code @Valid}。分页参数不合法不该报错
     * （Service 会归一成默认值），而唯一需要报错的「来源类型非法」由 Service 抛 A0401；
     * 若加了校验注解，一次手滑的负数页码就会变成参数错误响应。
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页参数），
     *                允许为 null 或字段全空
     * @return 统一响应体，data 里是文档分页结果（不含正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 来源类型取值非法（A0401）、
     * 未登录或角色不足（A0201 / A0301）时抛出
     */
    @PostMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】知识库文档分页列表", description = "不含正文；来源类型非法返回 A0401")
    public BaseResponse<Page<KnowledgeDocVO>> listDocByPage(@RequestBody KnowledgeDocQueryRequest request) {
        // 刻意不加 @Valid：GET 式查询参数校验失败会抛 BindException 变成 B0001，
        // 这里只有「来源类型非法」需要报错，交给 Service 抛 A0401
        return ResultUtils.success(knowledgeDocService.listDocByPage(request));
    }

    /**
     * 删除文档（逻辑删文档 + 同事务级联删块 + 提交后清理向量）
     *
     * <p>为什么这样设计：数据库里只做「标记删除」，文档与切分块必须在同一事务里
     * 一起标记，否则会留下「块还在、文档没了」的孤儿行。向量清理放在提交之后，
     * 因为它是内存操作、无法回滚——放进事务里只是「假装」参与原子性。
     * 不清理的后果很隐蔽：悬挂命中会长期占用检索名额，表现为「结果变少、相关性下降」，
     * 而且没有任何报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，避免空请求体变成 500；</li>
     *     <li>② 取出文档 ID 交给 Service。ID 是否合法（正数）由 Service 校验，
     *     Controller 不重复判一遍；</li>
     *     <li>③ 删除成功返回 true。失败一律抛异常，因此没有「返回 false 表示失败」的分支。</li>
     * </ol>
     *
     * <p>容易踩的坑：重复删除会得到 A0402 而不是 true。逻辑删除后按 ID 查不到，
     * 与「不存在」走同一条路径——前端做「删除后刷新列表」时要把 A0402 当成
     * 「已经删掉了」处理，而不是弹一个报错。
     *
     * @param deleteRequest 删除请求：id 必填；整个对象为 null 时得到 A0401
     * @return 统一响应体，data 为 true
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（A0401）、
     * 文档不存在或已删除（A0402）、未登录或角色不足（A0201 / A0301）时抛出
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除知识库文档", description = "逻辑删文档并级联删块；文档不存在返回 A0402")
    public BaseResponse<Boolean> deleteDoc(@RequestBody @Valid DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(knowledgeDocService.deleteDoc(deleteRequest.getId()));
    }

    /**
     * 重建一篇文档（回到业务源重新读一遍正文）
     *
     * <p>为什么这样设计：重建必须回业务源读，不能从切分块拼回正文——拼回来会丢失
     * 原来的切分边界，相当于用一份被自己加工过的二手文本重新入库。
     * 手工录入的文档没有业务源可回读，因此明确拒绝（A0401），
     * 并在提示里直接给出可执行的替代路径「删除后重新录入」，
     * 而不是让管理员自己猜为什么不能重建。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，避免空请求体变成 500；</li>
     *     <li>② 交给 Service：校验文档存在 → 拒绝 manual 来源 → 复用 sync 重新读源。
     *     复用 sync 而不是另写一套重建逻辑，才能保证「重建」与「同步」行为完全一致；</li>
     *     <li>③ 返回入库结果。内容没变时 skipped=true，此时没有产生任何 Embedding 调用。</li>
     * </ol>
     *
     * <p>容易踩的坑：重建是幂等的，内容没变就什么都不做。前端必须把 skipped 如实展示，
     * 否则管理员点了一次发现「状态没变」，会误以为接口失效而反复点。
     *
     * @param rebuildRequest 重建请求：id 必填；整个对象为 null 时得到 A0401
     * @return 统一响应体，data 里 skipped=true 表示内容未变，
     *         rebuilt=true 表示旧块已被替换
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法或文档不存在（A0401 / A0402）、
     * manual 来源不支持重建（A0401）、源数据已删除（A0402）时抛出
     */
    @PostMapping("/rebuild")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】重建知识库文档", description = "按业务源重新入库；manual 来源返回 A0401")
    public BaseResponse<KnowledgeIngestVO> rebuildDoc(@RequestBody @Valid KnowledgeRebuildRequest rebuildRequest) {
        ThrowUtils.throwIf(rebuildRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(knowledgeDocService.rebuildDoc(rebuildRequest.getId()));
    }

    /**
     * 强制重建全部文档的向量：应用重启后把检索能力恢复回来的唯一入口
     *
     * <p>为什么这样设计：向量库是进程内内存，应用每次重启，切分块的行还在库里，
     * 但向量全没了。此时「全量同步」（/sync-all）会因为「内容哈希没变」全部跳过——
     * 它一条都不会重建，接口照常返回成功，检索却一直 0 命中。这是本模块最容易误判的故障：
     * 看起来什么都没坏，只是 AI 回答突然不再引用资料。因此必须有一个绕过幂等判定的入口，
     * 它不读业务源、也不重新切分，直接拿切分块行里已有的文本重新嵌入，
     * 既快，又对手工录入（没有业务源可读）的文档同样有效。
     * 做成独立接口而不是「启动时自动执行」，是因为它按文本量计费：
     * 启动自动跑意味着每次重启都产生一笔不可预期的账单，且开发环境反复重启会反复烧钱。
     * 鉴权挂 {@code @SaCheckRole(ADMIN)}，因为它能触发全量计费调用，
     * 开放给普通用户等于把账单开关交出去。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参，直接调 Service 触发全量重建。Controller 不做任何编排——
     *     逐文档重建、每篇独立事务、单条失败不中断，全在 Service 里，
     *     这样「重建」与「同步」可以共用同一套入库管线，不会出现两份行为不一致的实现；</li>
     *     <li>② 把统计结果包成统一响应体。返回的是统计而不是「成功/失败」，
     *     因为批量操作里「部分成功」才是常态，前端要能展示「成功 N 篇、失败 M 篇」。</li>
     * </ol>
     *
     * <p>容易踩的坑：这是本模块最贵的一个接口，库里有多少块就要算多少次向量，
     * 而且它是全量的，没有任何增量参数——数据量大时耗时可能是分钟级，
     * 前端要给足超时时间，也别放在页面加载路径上。
     * 另一个坑是别把它当成 /sync-all 的替代品：两者的幂等口径不同，
     * 日常内容更新仍然应该走 /sync-all，本接口只在「重启后向量丢失」时用。
     * 还有一点容易误读：返回里 skipped 的含义是「该文档没有可用切分块」，
     * 不是「内容未变」，排查时以日志为准。
     *
     * @return 统一响应体，data 是统计对象：total 为文档总数，
     *         rebuilt 为成功重建向量的文档数，skipped 为没有可用块而跳过的文档数，
     *         failed 为失败数；created 恒为 0（本接口不新建文档）。
     *         三项计数互斥且完备（rebuilt + skipped + failed == total），
     *         没有文档时全为 0，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）
     *         或角色不足（A0301）时抛出。单篇文档重建失败<b>不会</b>抛异常，
     *         而是累计进 failed；只有数据库整体不可用才会以运行时异常的形式抛出，
     *         由全局异常处理器兜成 B0001
     */
    @PostMapping("/reindex-all")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】强制重建全部向量", description = "应用重启后恢复检索用；不读业务源、不重新切分")
    public BaseResponse<KnowledgeSyncAllVO> reindexAll() {
        return ResultUtils.success(knowledgeDocService.reindexAll());
    }
}
