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
 * 这些接口会真实花钱：每次重建都要重算一遍 Embedding，正文上限 20000 字可切出几十块。
 * 开放给普通用户等于把 API 账单的开关交出去，因此用 {@code @SaCheckRole(ADMIN)} 卡死。
 *
 * 白名单零改动：本路径不在白名单，全局拦截器默认拒绝，未登录 A0201、角色不足 A0301。
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
     * 每次新建、不幂等：手工录入的正文没有业务主键，判断不了「这次和上次是不是同一篇」，
     * 去重决定权交给人。向量化是外部调用且发生在事务外，耗时可能到十几秒，前端要给足超时。
     *
     * @param request title 与 content 必填，正文不超过 20000 字
     * @return docId、切分块数、状态（1 表示已索引）
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
     * 靠（来源类型, 来源 ID）定位再比对内容哈希：没变就返回 skipped=true，零 Embedding、零写库，
     * 因此可以放心重复调用。源数据不存在时返回 A0402 并把已有文档标记为失败态。
     *
     * @param request sourceType 取 project / member / certificate（大小写敏感），sourceId 必填
     * @return skipped=true 表示内容未变；rebuilt=true 表示替换了旧块；都为 false 表示首次新建
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
     * 本质是「N 次幂等 sync 的循环」，因此可重入，跑第二遍几乎零成本。单条失败不中断——
     * 一条项目正文太长，不该让另外 50 条成员档案也跟着进不去。耗时与费用随数据量线性增长。
     *
     * @return 统计：created + rebuilt + skipped + failed == total
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
     * 正文已切分进块表，拼回来意味着一页 10 条要读几百行块。这里只回答「有哪些文档、什么状态」。
     * 不加 {@code @Valid}：分页参数非法不该报错，来源类型的闭集校验在 Service（A0401）。
     *
     * @param request title 模糊、sourceType 与 status 精确 + 分页，允许为 null
     * @return 文档分页结果（不含正文）
     */
    @PostMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】知识库文档分页列表", description = "不含正文；来源类型非法返回 A0401")
    public BaseResponse<Page<KnowledgeDocVO>> listDocByPage(@RequestBody @Valid KnowledgeDocQueryRequest request) {
        // @Valid 只负责 title 的关键词长度（@Size max=64）：超长返回 A0401，
        // 与其它搜索 DTO 统一口径；sourceType 的闭集校验仍交给 Service，
        // 因为它需要给出「合法取值有哪些」的业务提示，不是字段级约束能表达的
        return ResultUtils.success(knowledgeDocService.listDocByPage(request));
    }

    /**
     * 删除文档（逻辑删文档 + 同事务级联删块 + 提交后清理向量）
     *
     * 文档与切分块须同一事务标记，否则留下「块还在、文档没了」的孤儿行。向量清理放在提交之后，
     * 因为它是内存操作、无法回滚；不清理会让悬挂命中长期占用检索名额，表现为结果变少且不报错。
     *
     * @param deleteRequest id 必填；重复删除得到 A0402，前端应把它当成「已经删掉了」
     * @return 恒为 true
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
     * 必须回业务源读，不能从切分块拼回正文——那会丢失原切分边界。手工录入的文档没有业务源，
     * 因此明确拒绝（A0401）并给出「删除后重新录入」的替代路径。内容没变时 skipped=true。
     *
     * @param rebuildRequest id 必填
     * @return skipped=true 表示内容未变，rebuilt=true 表示旧块已被替换
     */
    @PostMapping("/rebuild")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】重建知识库文档", description = "按业务源重新入库；manual 来源返回 A0401")
    public BaseResponse<KnowledgeIngestVO> rebuildDoc(@RequestBody @Valid KnowledgeRebuildRequest rebuildRequest) {
        ThrowUtils.throwIf(rebuildRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(knowledgeDocService.rebuildDoc(rebuildRequest.getId()));
    }

    /**
     * 强制重建全部文档的向量：应用重启后恢复检索能力的唯一入口
     *
     * 向量库是进程内内存，重启后向量全没了，而 /sync-all 会因内容哈希未变全部跳过——
     * 接口照常成功、检索却 0 命中，这是本模块最容易误判的故障。
     *
     * 它不读业务源、不重新切分，直接拿块行文本重新嵌入；按文本量计费，故不放在启动时自动跑。
     *
     * @return total、rebuilt、skipped、failed（互斥且完备），created 恒为 0
     */
    @PostMapping("/reindex-all")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】强制重建全部向量", description = "应用重启后恢复检索用；不读业务源、不重新切分")
    public BaseResponse<KnowledgeSyncAllVO> reindexAll() {
        return ResultUtils.success(knowledgeDocService.reindexAll());
    }
}
