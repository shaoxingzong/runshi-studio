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
 * 为什么单独建一个 Controller，而不是并进 PostController：
 * 评论是独立的资源（有自己的发布、审核、删除流程），与帖子只是聚合关系。
 * 项目里成员-证书、成员-项目也各自独立成 Controller，遵循同一套拆分方式。
 *
 * 鉴权（本类最重要的约定）：
 * - /post/comment/list：游客可访问，已作为精确路径登记在 SaTokenMvcConfig 的白名单里；
 * - /post/comment/add：登录用户即可，不要求是工作室成员。
 * 这个门槛差别是有意的：发帖代表工作室对外发声，评论只是读者之间的交流。
 * 若评论也要求成员身份，等于把访客的参与渠道关掉。
 *
 * 白名单为什么必须写精确路径：只放行 /post/comment/list 这一条读路径，
 * 绝不写成 /post/** 或 /post/comment/**——后者会把 add 一起放出去，
 * 变成「任何人都能匿名发评论」，那是内容审核的灾难。
 *
 * 先审后发：评论提交后不会立刻出现在列表里，需审核通过才公开。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/post/comment/list。
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
     * <p>为什么这样设计：可以是顶层评论（不传 parentId，由服务端分配楼层号），
     * 也可以是回复某条顶层评论（传 parentId），只支持两层。
     * 楼层号不接受前端传入——它是展示用的序号，让客户端决定会立刻出现重复与跳号。
     * 评论此刻还是待审，因此服务端刻意不累加帖子的评论数：
     * 计数在「真正公开」那一刻才加，否则用户会看到「有 1 条评论」却点开什么都没有。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（postId 必填、正文非空且不超过 1000 字），
     *     请求体整体为 null 时它不触发；</li>
     *     <li>② 交给 Service：校验帖子存在且已通过、校验父评论合法（存在、同帖、本身是顶层）、
     *     组装实体并以「待审」状态落库、异步送机器初判；</li>
     *     <li>③ 返回新评论 ID。前端拿到它只能提示「评论已提交，审核通过后展示」，
     *     不能立刻把内容插进列表——那会与列表接口的口径冲突。</li>
     * </ol>
     *
     * <p>容易踩的坑：父评论必须属于同一个帖子，且它自己必须是顶层评论。
     * 少了这层判断，就会出现「A 帖的评论挂在 B 帖下面」这种跨帖引用，
     * 或者三层嵌套（回复的回复再挂回复）——而前端只按两层渲染，多出来的那层会被静默丢掉。
     *
     * @param request 评论请求：postId 与 content 必填；parentId 选填，
     *                不传表示直接评论帖子（顶层，有楼层号）
     * @return 新评论 ID（雪花算法生成的 19 位长整型，JSON 里是字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在或未通过审核（A0402）、父评论不合法或正文超长（A0401）、入库失败（B0001）时抛出
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
     * 某帖子的评论列表（游客可访问，楼中楼结构）
     *
     * <p>为什么这样设计：一次查询把该帖全部已通过的评论取回来，再在内存里按 parentId
     * 挂到各自的顶层评论下，接口直接返回组装好的两层结构。
     * 绝不能写成「先查顶层，再对每条顶层查它的回复」——那是标准 N+1
     * （查 1 次列表，再对每条记录各查 1 次，条数越多查询越多），
     * 一个热门帖子有 50 条顶层评论就是 50 次查询。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 postId（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何帖子，直接给 A0401，
     *     省掉一次数据库往返，也避免把「参数错」伪装成「这个帖子没有评论」；</li>
     *     <li>③ 调 Service：一次查完该帖已通过评论、批量查评论人姓名、
     *     在内存里挂树，再包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有挂鉴权注解，这是刻意设计而不是漏加——
     * 路径已在白名单里，游客浏览帖子时应当能直接看到评论。
     * 但若哪天这个路径从白名单里被删掉，游客会立刻拿到 A0201，而代码里看不出任何异常。
     * 另外这里用 long 接参：传非数字（例如 ?postId=abc）会被兜成 B0001 而不是 A0401。
     *
     * @param postId 帖子 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 顶层评论列表，记录为 PostCommentVO（每条自带 children 回复列表）；
     * 没有已通过的评论时返回空列表，不会是 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子 id 不合法（A0401）时抛出；
     * 本接口不校验登录，因此不会抛 A0201
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
