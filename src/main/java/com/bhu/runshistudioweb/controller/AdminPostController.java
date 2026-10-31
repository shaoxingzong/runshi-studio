package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.post.PinPostRequest;
import com.bhu.runshistudioweb.model.dto.post.PostAdminQueryRequest;
import com.bhu.runshistudioweb.model.dto.post.PostUpdateRequest;
import com.bhu.runshistudioweb.model.vo.post.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.post.PostVO;
import com.bhu.runshistudioweb.model.vo.post.PostAdminDetailVO;
import com.bhu.runshistudioweb.service.PostCommentService;
import com.bhu.runshistudioweb.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子模块的管理端接口（审核 / 管理）
 *
 * 返回 PostVO（列表无正文）与 PostAdminDetailVO（详情含正文），两者不能互相顶替。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/post")
@Tag(name = "帖子管理", description = "管理员审核帖子与评论、删除评论，管理全部帖子（删除/置顶/编辑）")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;

    private final PostCommentService postCommentService;

    /**
     * 帖子待审队列：只列待审，按提交时间先进先出
     *
     * @param current 页码，缺省 1；pageSize 缺省 10，越界值由 Service 收敛
     * @return 待审帖分页（PostVO，含状态与作者名，不含正文）
     */
    @GetMapping("/pending")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子待审队列", description = "仅待审；按提交时间先进先出")
    public BaseResponse<Page<PostVO>> listPending(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postService.listPendingByPage(current, pageSize));
    }

    /**
     * 批量审核帖子：通过或驳回（驳回必须填理由）
     *
     * @param request ids 至少一个，action 只能是 approve / reject，驳回时 rejectReason 必填
     * @return 实际生效的条数，可能小于传入的 id 个数
     */
    @PostMapping("/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核帖子", description = "action 为 approve / reject；驳回必须填理由")
    public BaseResponse<Integer> audit(@RequestBody @Valid AuditRequest request) {
        // @Valid 不覆盖「请求体整体为 null」，兜一层避免 Service 里拆箱子 NPE 变成 B0001
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 审核与通知都在 Service 的事务里完成；返回的是实际生效条数
        return ResultUtils.success(postService.audit(request));
    }

    /**
     * 评论待审队列
     *
     * @param current 页码，缺省 1；pageSize 缺省 10
     * @return 待审评论分页（含评论正文、所属帖子与审核状态）
     */
    @GetMapping("/comment/pending")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】评论待审队列", description = "仅待审；通过时会累加所属帖子的评论数")
    public BaseResponse<Page<PostCommentAuditVO>> listCommentPending(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postCommentService.listPendingByPage(current, pageSize));
    }

    /**
     * 批量审核评论
     *
     * @param request ids 至少一个，action 为 approve / reject，驳回时理由必填
     * @return 实际处理的评论条数，可能小于传入的 id 个数
     */
    @PostMapping("/comment/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核评论", description = "通过后评论公开并计入帖子评论数")
    public BaseResponse<Integer> auditComment(@RequestBody @Valid AuditRequest request) {
        // 请求体整体为 null 时 @Valid 不触发，必须在这里挡住
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 累加评论数与通知回复者都在 Service 内完成，Controller 只转发条数
        return ResultUtils.success(postCommentService.audit(request));
    }

    /**
     * 删除评论：连带删除它的回复，并记录删除人
     *
     * @param request id 必填且为正整数；请求体或 id 为 null 时得到 A0401
     * @return 实际删除条数（含回复），供前端提示「删除 N 条」
     */
    @PostMapping("/comment/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除评论", description = "连带删除其回复；仅对已通过的评论生效")
    public BaseResponse<Integer> deleteComment(@RequestBody @Valid DeleteRequest request) {
        // 两个条件写在一起：请求体为 null 时，取 id 的那一步同样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postCommentService.deleteComment(request.getId()));
    }

    /**
     * 帖子全量列表（三种状态均可查，可按状态 / 标题 / 作者筛选）
     *
     * @param request 状态精确、标题与作者模糊、分页；允许整体为 null
     * @return 分页结果（PostVO，不含正文）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子全量列表", description = "三种状态均可查；按状态/标题/作者筛选，最新提交在前")
    public BaseResponse<Page<PostVO>> listForAdmin(@Valid PostAdminQueryRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 三个筛选项全是「传了才生效」，不传表示不限，而不是查该字段为 null 的记录
        return ResultUtils.success(postService.listForAdmin(request));
    }

    /**
     * 删除帖子：同事务级联删除该帖的全部评论与点赞关系
     *
     * @param request id 必填且为正整数
     * @return 实际删除的内容行数（帖子 1 + 其下评论数，点赞关系不计入）
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除帖子", description = "级联删除该帖全部评论与点赞关系；待审帖子请改用驳回")
    public BaseResponse<Integer> deletePost(@RequestBody @Valid DeleteRequest request) {
        // ② 判空与交给 Service 一起写：这里不能只判请求体，id 为 null 时取 id 一样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // 返回内容行数，前端据此组织「删除 1 篇帖子及 N 条评论」的提示
        return ResultUtils.success(postService.deletePost(request.getId()));
    }

    /**
     * 置顶 / 取消置顶（仅已通过的帖子可置顶）
     *
     * @param request id 必填，pinned 取 1（置顶）或 0（取消）
     * @return 恒为 true，失败一律抛异常
     */
    @PostMapping("/pin")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】置顶/取消置顶帖子", description = "仅已通过的帖子可置顶；取消置顶不限制状态")
    public BaseResponse<Boolean> pinPost(@RequestBody @Valid PinPostRequest request) {
        // 请求体与 id 都要判：空 body 时 @Valid 整条链路都不生效
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // Service 用异常表达失败，因此这里拿不到返回值
        postService.updatePinned(request.getId(), request.getPinned());
        return ResultUtils.success(true);
    }

    /**
     * 编辑帖子（仅管理员），保存后重置为待审
     *
     * @param request id、标题、正文必填；摘要与封面为空时由服务端兜底生成
     * @return 恒为 true，失败一律抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】编辑帖子", description = "保存后重置为待审重新送审；正文图片必须是站内路径")
    public BaseResponse<Boolean> updatePost(@RequestBody @Valid PostUpdateRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // 「重置为待审」是 Service 里的固定动作，不提供「保存但不送审」的开关
        postService.updatePost(request);
        // 与其它写接口一致：成功只可能是 true
        return ResultUtils.success(true);
    }

    /**
     * 管理端帖子详情（含 Markdown 正文，不累加浏览量）
     *
     * @param id 帖子 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return PostAdminDetailVO（含正文）
     */
    @GetMapping("/detail")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子详情", description = "含 Markdown 正文；不限审核状态，不累加浏览量")
    public BaseResponse<PostAdminDetailVO> getDetail(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        // 管理端详情不累加浏览量，因此可以放心在编辑弹窗里反复打开
        return ResultUtils.success(postService.getPostForAdmin(id));
    }
}
