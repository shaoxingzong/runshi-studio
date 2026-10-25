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
 * 全部要求 admin 角色：这里的每一个动作都是在改别人的内容
 * （决定公开与否、删掉谁的内容、置顶谁的帖子），必须严格限权。
 *
 * 为什么路径挂在 /admin/post 而不是 /post：
 * 白名单里放行了 /post/list/page、/post/detail、/post/comment/list 三条匿名路径，
 * 它们用的是精确匹配；管理端接口另起 /admin 前缀，
 * 与匿名接口在路径上就分开，避免将来有人为了省事把白名单写成 /post/**
 * 而把审核接口一起放出去。
 *
 * 两类接口的分工：
 * - 审核（ /pending、/audit、评论的两个对应接口）：
 * 面向待审队列，批量处置；
 * - 管理（ /list/page、/delete、/pin、/update）：
 * 面向全部内容，查阅、删除、置顶、代改。
 *
 * 减负设计：审核接口都支持批量（传 id 数组），
 * 管理员面对的是队列而不是单条内容——勾选多条一次通过，
 * 是把人工审核成本压下来的直接手段（见 AuditRequest 的注释）。
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

    /** 帖子待审队列：仅待审，先进先出 */
    @GetMapping("/pending")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子待审队列", description = "仅待审；按提交时间先进先出")
    public BaseResponse<Page<PostVO>> listPending(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postService.listPendingByPage(current, pageSize));
    }

    /** 批量审核帖子：通过或驳回（驳回必填理由） */
    @PostMapping("/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核帖子", description = "action 为 approve / reject；驳回必须填理由")
    public BaseResponse<Integer> audit(@RequestBody @Valid AuditRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.audit(request));
    }

    /** 评论待审队列 */
    @GetMapping("/comment/pending")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】评论待审队列", description = "仅待审；通过时会累加所属帖子的评论数")
    public BaseResponse<Page<PostCommentAuditVO>> listCommentPending(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postCommentService.listPendingByPage(current, pageSize));
    }

    /** 批量审核评论 */
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

    /**
     * 帖子全量列表（三种状态均可查，支持按状态 / 标题 / 作者筛选）
     *
     * GET + 查询参数对象绑定，与用户管理等模块的查询接口同一惯例。
     * 不加 @Valid：这些参数用 GET 传递，校验失败会落到全局异常处理器的
     * 兜底分支变成 B0001；非法取值由 Service 显式校验并给出可读提示。
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页）
     * @return 分页结果（含作者昵称）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子全量列表", description = "三种状态均可查；按状态/标题/作者筛选，最新提交在前")
    public BaseResponse<Page<PostVO>> listForAdmin(PostAdminQueryRequest request) {
        // @Valid 不校验「对象整体为 null」这种场景，兜一层避免 Service 里拆箱子 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.listForAdmin(request));
    }

    /**
     * 删除帖子：同事务级联删除该帖的全部评论与点赞关系
     *
     * 待审帖子不能删（与删除评论同规则）：删了作者什么都收不到，
     * 该走驳回让理由送达作者。已通过（下线）/ 已驳回（清理）才可删。
     *
     * @return 实际删除的内容行数（帖子 1 + 其下评论数）
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除帖子", description = "级联删除该帖全部评论与点赞关系；待审帖子请改用驳回")
    public BaseResponse<Integer> deletePost(@RequestBody @Valid DeleteRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.deletePost(request.getId()));
    }

    /**
     * 置顶 / 取消置顶（仅已通过的帖子可置顶）
     *
     * @param request 置顶请求（帖子 id + pinned 0/1）
     */
    @PostMapping("/pin")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】置顶/取消置顶帖子", description = "仅已通过的帖子可置顶；取消置顶不限制状态")
    public BaseResponse<Boolean> pinPost(@RequestBody @Valid PinPostRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        postService.updatePinned(request.getId(), request.getPinned());
        return ResultUtils.success(true);
    }

    /**
     * 编辑帖子（仅管理员），保存后非待审状态一律重置为待审重新送审
     *
     * 「先审后发」的后门必须堵住：若管理员改完仍是已通过，
     * 等于存在一条没人过审就对外可见的修改路径。
     *
     * @param request 编辑请求（id、标题、正文必填）
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】编辑帖子", description = "保存后重置为待审重新送审；正文图片必须是站内路径")
    public BaseResponse<Boolean> updatePost(@RequestBody @Valid PostUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        postService.updatePost(request);
        return ResultUtils.success(true);
    }

    /**
     * 管理端帖子详情（含 Markdown 正文，不累加浏览量）
     *
     * 编辑弹窗回填正文专用：列表 VO 刻意不带 content，
     * 而公开详情接口只覆盖「已通过」的帖子且会自增浏览量，都不能用在编辑场景。
     *
     * @param id 帖子 ID
     * @return 详情（含正文）；不存在返回 A0402
     */
    @GetMapping("/detail")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子详情", description = "含 Markdown 正文；不限审核状态，不累加浏览量")
    public BaseResponse<PostAdminDetailVO> getDetail(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        return ResultUtils.success(postService.getPostForAdmin(id));
    }
}
