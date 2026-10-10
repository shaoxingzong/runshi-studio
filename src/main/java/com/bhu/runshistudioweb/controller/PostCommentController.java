package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.post.PostCommentAddRequest;
import com.bhu.runshistudioweb.model.vo.PostCommentVO;
import com.bhu.runshistudioweb.service.PostCommentService;
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

import java.util.List;

/**
 * 帖子评论接口
 *
 * author: shaoshing
 *
 * <p><b>为什么单独建一个 Controller，而不是并进 {@link PostController}</b>：
 * 评论是独立的资源（有自己的发布、审核、删除流程），与帖子只是聚合关系。
 * 项目里成员-证书、成员-项目也各自独立成 Controller，遵循同一套拆分方式。
 *
 * <p><b>鉴权</b>：
 * <table border="1">
 *     <tr><th>接口</th><th>谁能调</th></tr>
 *     <tr><td>{@code /post/comment/list}</td><td><b>游客可访问</b>（已加入白名单，见 {@code SaTokenMvcConfig}）</td></tr>
 *     <tr><td>{@code /post/comment/add}</td><td>登录用户（不要求是成员，与发帖的门槛不同）</td></tr>
 * </table>
 *
 * <p>先审后发：评论提交后不会立刻出现在列表里，需审核通过。
 */
@RestController
@RequestMapping("/post/comment")
@Tag(name = "帖子评论", description = "登录用户评论（需审核）；游客可浏览已通过评论")
@RequiredArgsConstructor
public class PostCommentController {

    private final PostCommentService postCommentService;

    /**
     * 发表评论（登录用户）
     *
     * <p>可以是顶层评论（不传 parentId，自动分配楼层号），
     * 也可以是回复某条顶层评论（传 parentId）；只支持两层。
     *
     * @param request 评论请求
     * @return 新评论 ID（进入待审）
     */
    @PostMapping("/add")
    @SaCheckLogin
    @Operation(summary = "【登录用户】发表评论", description = "提交后进入待审；通过后才公开并计入评论数")
    public BaseResponse<Long> addComment(@RequestBody @Valid PostCommentAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postCommentService.addComment(request));
    }

    /**
     * 某帖子的评论列表（游客可访问，楼中楼结构）
     *
     * @param postId 帖子 ID
     * @return 顶层评论列表，每条带 {@code children}
     */
    @GetMapping("/list")
    @Operation(summary = "帖子评论列表（游客可访问）", description = "仅已通过；一次返回并按楼中楼组装好")
    public BaseResponse<List<PostCommentVO>> listByPost(@RequestParam("postId") long postId) {
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        return ResultUtils.success(postCommentService.listApprovedByPost(postId));
    }
}
