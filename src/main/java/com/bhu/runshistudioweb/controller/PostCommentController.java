package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.post.PostCommentAddRequest;
import com.bhu.runshistudioweb.model.vo.post.PostCommentVO;
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
 * 鉴权分工：/post/comment/list 游客可访问，精确路径登记在白名单；/add 只要登录、不要求成员——
 * 发帖代表工作室对外发声，评论只是读者交流。白名单不能写成 /post/comment/**。
 *
 * 先审后发：评论提交后不立刻出现在列表里，需审核通过才公开。
 *
 * @author shaoshing
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
     * 楼层号由服务端分配，不接受前端传入——它是展示序号，客户端决定会立刻出现重复与跳号。
     * 评论此刻待审，因此不累加帖子评论数：计数在真正公开那一刻才加。
     *
     * @param request postId 与 content 必填；parentId 选填，不传表示顶层评论
     * @return 新评论 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/add")
    @SaCheckLogin
    @Operation(summary = "【登录用户】发表评论", description = "提交后进入待审；通过后才公开并计入评论数")
    public BaseResponse<Long> addComment(@RequestBody @Valid PostCommentAddRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 楼层号由服务端分配、评论数不在此刻累加——两条规则都在 Service 里
        return ResultUtils.success(postCommentService.addComment(request));
    }

    /**
     * 某帖子的评论列表（游客可访问，楼中楼）
     *
     * 一次查完已通过的评论再在内存里按 parentId 挂树，不能写成「先查顶层再逐条查回复」的 N+1。
     * 没有鉴权注解是刻意的：路径在白名单里，游客浏览帖子时应当能直接看到评论。
     *
     * @param postId 帖子 ID，必须大于 0，传 0 或负数得到 A0401
     * @return 顶层评论列表，每条自带 children 回复；没有已通过评论时返回空列表
     */
    @GetMapping("/list")
    @Operation(summary = "帖子评论列表（游客可访问）", description = "仅已通过；一次返回并按楼中楼组装好")
    public BaseResponse<List<PostCommentVO>> listByPost(@RequestParam("postId") long postId) {
        // ① 用 long 接参：缺参由 Spring 抛异常，非数字会被兜成 B0001
        // ② 先挡 0 与负数：它们不可能对应任何帖子，查库只会返回一个误导性的空列表
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        // ③ 楼中楼在 Service 里一次组装完成，Controller 不再排序或分组
        return ResultUtils.success(postCommentService.listApprovedByPost(postId));
    }
}
