package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.post.PostAddRequest;
import com.bhu.runshistudioweb.model.dto.post.PostLikeRequest;
import com.bhu.runshistudioweb.model.dto.post.PostUpdateRequest;
import com.bhu.runshistudioweb.model.vo.post.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.post.PostSelfDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostVO;
import com.bhu.runshistudioweb.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

// 帖子接口
//
// 鉴权分工：
// 接口 | 谁能调
// /post/list/page、 /post/detail | 游客可访问（已加入白名单，见 SaTokenMvcConfig）
// /post/add | 登录用户（是否成员由 Service 查 studio_member 判定）
// /post/like | 登录用户（点赞幂等，只能赞不能取消）
// /post/my | 登录用户
//
// 为什么发帖只挂 @SaCheckLogin 而不挂 @SaCheckRole("member")：
// 「成员」不是角色标签，而是管理员在 studio_member 表里的档案。
// 角色可能与档案不同步（有人被移出名录但角色没改），按角色放行会绕开真正的准入规则。
// 所以这里只保证「已登录」，成员身份由 Service 认表判定——与考勤模块同一套路。
//
// 白名单必须写精确路径：只放行 /post/list/page 与 /post/detail，
// 绝不写成 /post/**——那样会把将来新增的帖子管理端接口（审核、删除）一起放行。
//
// 接口地址前缀 /api，完整路径形如 http://localhost:8080/api/post/list/page。
@RestController
@RequestMapping("/post")
@Tag(name = "帖子模块", description = "游客浏览已通过帖子；工作室成员发帖")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    // 发布帖子（成员）
    //
    // 返回的是帖子 ID；内容进入待审，不会立刻出现在公开列表——
    // 作者要在「我的发帖」里看审核状态（先审后发的设计）。
    //
    // @param request 发布请求（标题、正文必填）
    // @return 新帖子 ID
    @PostMapping("/add")
    @SaCheckLogin
    @Operation(summary = "【成员】发布帖子", description = "正文为 Markdown，图片需先用编辑器上传；发布后进入待审")
    public BaseResponse<Long> addPost(@RequestBody @Valid PostAddRequest request) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.addPost(request));
    }

    // 点赞帖子（需登录，幂等）
    //
    // 用户身份从登录态取，请求体里不收 userId——否则「替别人点赞」就成了一个功能。
    //
    // 返回的是点赞后的最新点赞数：前端直接用这个数字刷新界面，
    // 不做乐观 +1——避免与「重复点赞不计数」的幂等语义冲突导致数字跳变。
    //
    // 本模块只能赞、不能取消，因此没有 unlike / toggle 接口。
    //
    // @param request 点赞请求（postId 必填）
    // @return 最新点赞数（重复点赞时原样返回，不重复 +1）
    @PostMapping("/like")
    @SaCheckLogin
    @Operation(summary = "【登录用户】点赞帖子", description = "幂等：重复点赞不重复计数；只能赞，不能取消")
    public BaseResponse<Long> likePost(@RequestBody @Valid PostLikeRequest request) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.likePost(StpUtil.getLoginIdAsLong(), request.getPostId()));
    }

    // 公开帖子列表（游客可访问）
    //
    // 只返回已通过的帖子，置顶优先、时间倒序。
    //
    // @param current 页码，默认 1
    // @param pageSize 每页条数，默认 10（上限 50）
    // @return 分页结果
    @GetMapping("/list/page")
    @Operation(summary = "帖子列表（游客可访问）", description = "仅已通过；置顶优先 + 时间倒序")
    public BaseResponse<Page<PostFrontVO>> listApprovedByPage(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postService.listApprovedByPage(current, pageSize));
    }

    // 帖子详情（游客可访问），并累加浏览量
    //
    // @param id 帖子 ID
    // @return 详情（含 Markdown 正文）
    @GetMapping("/detail")
    @Operation(summary = "帖子详情（游客可访问）", description = "仅已通过；待审或已驳回对公众等同于不存在")
    public BaseResponse<PostFrontDetailVO> getApprovedDetail(@RequestParam("id") long id) {
        // 用 long 接参：非数字字符串会由全局异常处理器兜成 A0401；这里再挡一次明显的非法值
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        return ResultUtils.success(postService.getApprovedDetail(id));
    }

    // 我的发帖（含待审与已驳回）
    //
    // 先审后发的关键接口：作者在这里看自己内容的审核状态，
    // 已驳回时 rejectReason 会给出理由。
    //
    // @param current 页码
    // @param pageSize 每页条数
    // @return 分页结果
    @GetMapping("/my")
    @SaCheckLogin
    @Operation(summary = "我的发帖", description = "含待审与已驳回；驳回时带理由")
    public BaseResponse<Page<PostVO>> listMyByPage(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postService.listMyByPage(current, pageSize));
    }

    // 作者修改自己被驳回的帖子（改完重新送审）
    //
    // 与发帖一样只要求登录（不挂成员角色）：「是不是成员、是不是本人、
    // 是不是已驳回」全由 Service 判定——作者只能改自己的、且只能改已驳回的，
    // 其余情况按「帖子不存在」返回，不暴露存在性。
    //
    // 保存后帖子回到待审：内容变了就必须重新过审，这是「先审后发」的底线。
    @PostMapping("/update-mine")
    @SaCheckLogin
    @Operation(summary = "【作者】修改被驳回的自己的帖子", description = "仅本人 + 仅已驳回；保存后重置为待审重新送审")
    public BaseResponse<Boolean> updateMyRejectedPost(@RequestBody @Valid PostUpdateRequest request) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        postService.updateMyRejectedPost(request);
        return ResultUtils.success(true);
    }

    // 作者读自己「未公开」帖子的详情（含正文，不累加浏览量）
    //
    // 改稿的前置步骤：驳回稿的正文作者本来读不到（公开详情只返回已通过），
    // 要改就得先看到自己写了什么。仅本人 + 仅待审/已驳回。
    //
    // @param id 帖子 ID
    // @return 详情（含正文）；非本人或状态不符按「不存在」返回
    @GetMapping("/my/detail")
    @SaCheckLogin
    @Operation(summary = "【作者】我的未公开帖子详情", description = "仅本人 + 仅待审/已驳回；含正文，不累加浏览量")
    public BaseResponse<PostSelfDetailVO> getMyDetail(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        return ResultUtils.success(postService.getMyPostDetail(id));
    }
}
