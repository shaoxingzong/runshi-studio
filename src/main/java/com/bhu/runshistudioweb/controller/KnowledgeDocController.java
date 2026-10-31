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

    // 批量与管理

    /**
     * 全量同步：把三类业务数据一次性灌进知识库
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
     * @param request title 模糊、sourceType 与 status 精确 + 分页，允许为 null
     * @return 文档分页结果（不含正文）
     */
    @PostMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】知识库文档分页列表", description = "不含正文；来源类型非法返回 A0401")
    public BaseResponse<Page<KnowledgeDocVO>> listDocByPage(@RequestBody @Valid KnowledgeDocQueryRequest request) {
        return ResultUtils.success(knowledgeDocService.listDocByPage(request));
    }

    /**
     * 删除文档（逻辑删文档 + 同事务级联删块 + 提交后清理向量）
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
     * @return total、rebuilt、skipped、failed（互斥且完备），created 恒为 0
     */
    @PostMapping("/reindex-all")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】强制重建全部向量", description = "应用重启后恢复检索用；不读业务源、不重新切分")
    public BaseResponse<KnowledgeSyncAllVO> reindexAll() {
        return ResultUtils.success(knowledgeDocService.reindexAll());
    }
}
