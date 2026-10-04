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
import com.bhu.runshistudioweb.model.vo.KnowledgeDocVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeIngestVO;
import com.bhu.runshistudioweb.model.vo.KnowledgeSyncAllVO;
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库文档接口（<b>全部要求 admin 角色</b>）
 *
 * <p>本类覆盖三期落地的能力： 入库（manual / sync）、
 * 管理端（sync-all / list/page / delete / rebuild）、
 * 向量重建（reindex-all）。它们都要求 admin，因此合并放在同一个 Controller 里，
 * 白名单零改动。
 *
 * author: shaoshing
 *
 * <p>为什么必须是管理员：这两个接口都会<b>真实花钱</b>——每重建一次就要重算一遍 Embedding，
 * 而正文上限是 20000 字（可切出几十个向量）。开放给普通用户等于把 API 账单的开关交出去，
 * 因此这里用 {@code @SaCheckRole(ADMIN)} 卡死，和白名单无关。
 *
 * <p><b>白名单零改动</b>：本接口不在 {@code SaTokenMvcConfig} 的白名单里，
 * 而全局拦截器是「默认拒绝」策略——未登录访问会得到 A0201，
 * 登录了但角色不够会得到 A0301。这两条都是既有机制自动覆盖的，不需要额外配置。
 *
 * <p>Controller 依旧只做三件事：接参、调 Service、包响应。
 * 编排（切分 → 向量化 → 事务落库 → 清理旧向量）全在 {@link KnowledgeDocService} 里。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/knowledge/doc/manual}。
 */
@Tag(name = "知识库文档模块", description = "管理员手工录入与业务来源同步（RAG 入库）")
@RestController
@RequestMapping("/knowledge/doc")
public class KnowledgeDocController {

    @Resource
    private KnowledgeDocService knowledgeDocService;

    /**
     * 手工录入一篇文档（每次都新建，不幂等）
     *
     * @param request 录入请求（title、content 必填）
     * @return 入库结果（chunkCount 为切出的块数，status=1 表示已索引）
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
     * 同步某条业务数据到知识库（幂等、可重建）
     *
     * @param request 同步请求（sourceType ∈ project/member/certificate，sourceId 必填）
     * @return 入库结果（skipped=true 表示内容未变、零成本跳过；rebuilt=true 表示替换了旧块）
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
     * 全量同步（把三类业务数据一次性灌进知识库）
     *
     * <p><b>可重入</b>：内容没变的会被幂等跳过，因此跑第二遍几乎零成本；
     * <b>单条失败不中断</b>：失败计入 {@code failed} 并返回统计，不抛整体异常——
     * 一条项目正文太长导致向量化失败，不该让另外 50 条成员档案也跟着进不去。
     *
     * @return 统计：{@code created + rebuilt + skipped + failed == total}
     */
    @PostMapping("/sync-all")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】全量同步知识库", description = "三类业务数据一次性入库；幂等可重入，单条失败不中断")
    public BaseResponse<KnowledgeSyncAllVO> syncAll() {
        return ResultUtils.success(knowledgeDocService.syncAll());
    }

    /**
     * 文档分页列表（<b>不含正文</b>）
     *
     * <p>正文不在 doc 行里（已切分进 chunk），拼回来再返回意味着一页 10 条要读几百行块。
     * 这个接口只回答「有哪些文档、各自什么状态」——管理员据此决定删除或重建。
     *
     * @param request 查询条件（title 模糊 / sourceType 精确 / status 精确 + 分页参数）
     * @return 分页结果
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
     * 删除文档（逻辑删 + 同事务级联删块 + 提交后清理向量）
     *
     * @param deleteRequest 删除请求（id 必填）
     * @return true 表示删除成功
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
     * <p><b>手工录入的文档无法重建</b>（A0401）：它没有业务源可回读，
     * 从块拼回正文会丢失原切分意图。提示文案直接给出可执行的替代路径——
     * 删除后重新录入，而不是让管理员自己猜。
     *
     * @param rebuildRequest 重建请求（id 必填）
     * @return 入库结果（内容未变时 skipped=true）
     */
    @PostMapping("/rebuild")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】重建知识库文档", description = "按业务源重新入库；manual 来源返回 A0401")
    public BaseResponse<KnowledgeIngestVO> rebuildDoc(@RequestBody @Valid KnowledgeRebuildRequest rebuildRequest) {
        ThrowUtils.throwIf(rebuildRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(knowledgeDocService.rebuildDoc(rebuildRequest.getId()));
    }

    /**
     * 强制重建全部文档的向量（<b>应用重启后的恢复手段</b>，观察项 e）
     *
     * <p>为什么需要它：向量库是进程内内存，重启后 chunk 行还在但向量全没了。
     * 此时 {@code sync-all} 会因为「内容哈希未变」全部跳过（什么都不会做），
     * 检索一直 0 命中——只有本接口能恢复。
     *
     * <p>它<b>不读业务源、也不重新切分</b>：直接用 chunk 行里已有的文本重新嵌入，
     * 因此对手工录入（{@code manual}，没有业务源）的文档同样有效。
     *
     * @return 统计（rebuilt / skipped / failed）
     */
    @PostMapping("/reindex-all")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】强制重建全部向量", description = "应用重启后恢复检索用；不读业务源、不重新切分")
    public BaseResponse<KnowledgeSyncAllVO> reindexAll() {
        return ResultUtils.success(knowledgeDocService.reindexAll());
    }
}
