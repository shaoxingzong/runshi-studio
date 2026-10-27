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
 * 全部要求 {@code @SaCheckRole(ADMIN)}：每个动作都在改别人的内容，角色实时查库判定，
 * 降级后下一次请求立刻失效。路径另起 /admin/post，与白名单放行的 /post/** 精确路径分开。
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
     * 队列顺序就是作者的等待时长，按 id 倒序会让最早提交的那批永远轮不到。
     * 只挂角色注解不是漏写：角色校验的前提就是已登录，未登录先抛 A0201。
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
        // ① 两个分页参数由 Spring 绑定，都有默认值，缺参时不会抛「缺少必填参数」
        // ②③ 交给 Service 取一页并直接包成统一响应体：分页越界由 Service 收敛，这里不做二次校验
        return ResultUtils.success(postService.listPendingByPage(current, pageSize));
    }

    /**
     * 批量审核帖子：通过或驳回（驳回必须填理由）
     *
     * 返回实际处理条数而不是布尔值：批次里可能有人刚被审过，前端要能提示「提交 5 条、处理 3 条」。
     *
     * @param request ids 至少一个，action 只能是 approve / reject，驳回时 rejectReason 必填
     * @return 实际生效的条数，可能小于传入的 id 个数
     */
    @PostMapping("/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核帖子", description = "action 为 approve / reject；驳回必须填理由")
    public BaseResponse<Integer> audit(@RequestBody @Valid AuditRequest request) {
        // ① @Valid 不覆盖「请求体整体为 null」，兜一层避免 Service 里拆箱子 NPE 变成 B0001
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 审核与通知都在 Service 的事务里完成；返回的是实际生效条数
        return ResultUtils.success(postService.audit(request));
    }

    /**
     * 评论待审队列
     *
     * 与帖子队列分开：两者处置节奏不同，帖子要读全文，评论扫一眼即可。
     * 返回 PostCommentAuditVO 而非公开的 PostCommentVO——后者没有审核状态与机器初判结论。
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
        // ① 两个分页参数都有默认值，缺参时不会抛「缺少必填参数」
        // ②③ 待审条件写在 Service 的查询里，这里只负责转发与包装
        return ResultUtils.success(postCommentService.listPendingByPage(current, pageSize));
    }

    /**
     * 批量审核评论
     *
     * 与帖子审核共用 AuditRequest，动作同构；副作用不同：通过后累加所属帖子评论数并通知被回复者。
     *
     * @param request ids 至少一个，action 为 approve / reject，驳回时理由必填
     * @return 实际处理的评论条数，可能小于传入的 id 个数
     */
    @PostMapping("/comment/audit")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量审核评论", description = "通过后评论公开并计入帖子评论数")
    public BaseResponse<Integer> auditComment(@RequestBody @Valid AuditRequest request) {
        // ① 请求体整体为 null 时 @Valid 不触发，必须在这里挡住
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 累加评论数与通知回复者都在 Service 内完成，Controller 只转发条数
        return ResultUtils.success(postCommentService.audit(request));
    }

    /**
     * 删除评论：连带删除它的回复，并记录删除人
     *
     * 只删顶层会留下 parentId 指向已删评论的孤儿回复。仅已通过的评论可删，待审的应走驳回——
     * 驳回会把理由送到作者眼前，直接删掉他什么都收不到。
     *
     * @param request id 必填且为正整数；请求体或 id 为 null 时得到 A0401
     * @return 实际删除条数（含回复），供前端提示「删除 N 条」
     */
    @PostMapping("/comment/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除评论", description = "连带删除其回复；仅对已通过的评论生效")
    public BaseResponse<Integer> deleteComment(@RequestBody @Valid DeleteRequest request) {
        // ① 两个条件写在一起：请求体为 null 时，取 id 的那一步同样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ②③ 评论数回退也在 Service 里做，返回值是「删了几条」而不是「成没成功」
        return ResultUtils.success(postCommentService.deleteComment(request.getId()));
    }

    /**
     * 帖子全量列表（三种状态均可查，可按状态 / 标题 / 作者筛选）
     *
     * 服务于管理台账，与待审队列是两种用途。参数走 GET + 对象绑定，便于把筛选条件贴进链接排查。
     * 不加 {@code @Valid}：GET 校验失败抛 BindException 会变成 B0001，而页码传错不该让查询失败。
     *
     * @param request 状态精确、标题与作者模糊、分页；允许整体为 null
     * @return 分页结果（PostVO，不含正文）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子全量列表", description = "三种状态均可查；按状态/标题/作者筛选，最新提交在前")
    public BaseResponse<Page<PostVO>> listForAdmin(@Valid PostAdminQueryRequest request) {
        // ① @Valid 只负责标题/作者关键词长度（@Size max=64）；它不校验「对象整体为 null」，
        //    兜一层避免 Service 里拆箱子 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 三个筛选项全是「传了才生效」，不传表示不限，而不是查该字段为 null 的记录
        return ResultUtils.success(postService.listForAdmin(request));
    }

    /**
     * 删除帖子：同事务级联删除该帖的全部评论与点赞关系
     *
     * 待审帖子不能删——作者只知道「我提交了」，该走驳回让理由送达。已通过（下线）与
     * 已驳回（清理）才可删。返回内容行数而不是布尔值，前端才能提示「删除 1 篇及 12 条评论」。
     *
     * @param request id 必填且为正整数
     * @return 实际删除的内容行数（帖子 1 + 其下评论数，点赞关系不计入）
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除帖子", description = "级联删除该帖全部评论与点赞关系；待审帖子请改用驳回")
    public BaseResponse<Integer> deletePost(@RequestBody @Valid DeleteRequest request) {
        // ①② 判空与交给 Service 一起写：这里不能只判请求体，id 为 null 时取 id 一样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ③ 返回内容行数，前端据此组织「删除 1 篇帖子及 N 条评论」的提示
        return ResultUtils.success(postService.deletePost(request.getId()));
    }

    /**
     * 置顶 / 取消置顶（仅已通过的帖子可置顶）
     *
     * 只有已通过的可置顶：公开列表只返回已通过内容，置顶待审帖在门户上没有任何效果。
     * 取消置顶刻意不限状态——帖子编辑后会重置为待审而 pinned 仍是 1，恢复操作不该被拦住。
     *
     * @param request id 必填，pinned 取 1（置顶）或 0（取消）
     * @return 恒为 true，失败一律抛异常
     */
    @PostMapping("/pin")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】置顶/取消置顶帖子", description = "仅已通过的帖子可置顶；取消置顶不限制状态")
    public BaseResponse<Boolean> pinPost(@RequestBody @Valid PinPostRequest request) {
        // ① 请求体与 id 都要判：空 body 时 @Valid 整条链路都不生效
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ② Service 用异常表达失败，因此这里拿不到返回值
        postService.updatePinned(request.getId(), request.getPinned());
        // ③ 固定 true：不存在「返回 false 表示没改成」的分支
        return ResultUtils.success(true);
    }

    /**
     * 编辑帖子（仅管理员），保存后重置为待审
     *
     * 编辑权只给管理员：先审后发之下，若作者能改已通过的正文，等于给审核开后门。
     * 正文里的图片必须是站内路径（/uploads/...），外链会在对方失效后变成无法修复的破图。
     *
     * @param request id、标题、正文必填；摘要与封面为空时由服务端兜底生成
     * @return 恒为 true，失败一律抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】编辑帖子", description = "保存后重置为待审重新送审；正文图片必须是站内路径")
    public BaseResponse<Boolean> updatePost(@RequestBody @Valid PostUpdateRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ② 「重置为待审」是 Service 里的固定动作，不提供「保存但不送审」的开关
        postService.updatePost(request);
        // ③ 与其它写接口一致：成功只可能是 true
        return ResultUtils.success(true);
    }

    /**
     * 管理端帖子详情（含 Markdown 正文，不累加浏览量）
     *
     * 公开详情只返回已通过、且每次调用都给浏览量 +1；管理员在编辑弹窗反复打开不能污染热度，
     * 因此单开一个接口，三种状态都能取到且无副作用。
     *
     * @param id 帖子 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return PostAdminDetailVO（含正文）
     */
    @GetMapping("/detail")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子详情", description = "含 Markdown 正文；不限审核状态，不累加浏览量")
    public BaseResponse<PostAdminDetailVO> getDetail(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何行，查库只会返回一个误导性的「帖子不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        // ③ 管理端详情不累加浏览量，因此可以放心在编辑弹窗里反复打开
        return ResultUtils.success(postService.getPostForAdmin(id));
    }
}
