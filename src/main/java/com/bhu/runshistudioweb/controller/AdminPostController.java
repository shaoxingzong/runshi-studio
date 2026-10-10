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
import com.bhu.runshistudioweb.model.vo.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.PostVO;
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
 * 帖子模块的管理端接口（审核 / 删除）
 *
 * author: shaoshing
 *
 * <p><b>全部要求 admin 角色</b>：这里的每一个动作都是在改别人的内容
 * （决定公开与否、删掉谁的评论），必须严格限权。
 *
 * <p><b>为什么路径挂在 {@code /admin/post} 而不是 {@code /post}</b>：
 * 白名单里放行了 {@code /post/list/page}、{@code /post/detail}、{@code /post/comment/list} 三条匿名路径，
 * 它们用的是<b>精确匹配</b>；管理端接口另起 {@code /admin} 前缀，
 * 与匿名接口在路径上就分开，避免将来有人为了省事把白名单写成 {@code /post/**}
 * 而把审核接口一起放出去。
 *
 * <p><b>减负设计</b>：审核接口都支持<b>批量</b>（传 id 数组），
 * 管理员面对的是队列而不是单条内容——勾选多条一次通过，
 * 是把人工审核成本压下来的直接手段（见 {@code AuditRequest} 的注释）。
 */
@RestController
@RequestMapping("/admin/post")
@Tag(name = "帖子管理", description = "管理员审核帖子与评论、删除评论")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;

    private final PostCommentService postCommentService;

    /**
     * 帖子待审队列：仅待审，先进先出
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
     * 批量审核帖子：通过或驳回（驳回必填理由）
     */
    @PostMapping("/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核帖子", description = "action 为 approve / reject；驳回必须填理由")
    public BaseResponse<Integer> audit(@RequestBody @Valid AuditRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.audit(request));
    }

    /**
     * 评论待审队列
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
     */
    @PostMapping("/comment/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核评论", description = "通过后评论公开并计入帖子评论数")
    public BaseResponse<Integer> auditComment(@RequestBody @Valid AuditRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postCommentService.audit(request));
    }

    /**
     * 删除评论：连带删除它的回复，并记录删除人
     *
     * @return 实际删除条数（含回复），前端可提示「已删除 N 条」
     */
    @PostMapping("/comment/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除评论", description = "连带删除其回复；仅对已通过的评论生效")
    public BaseResponse<Integer> deleteComment(@RequestBody @Valid DeleteRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postCommentService.deleteComment(request.getId()));
    }
}
