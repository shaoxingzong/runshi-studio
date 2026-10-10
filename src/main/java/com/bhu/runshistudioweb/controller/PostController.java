package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.post.PostAddRequest;
import com.bhu.runshistudioweb.model.vo.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.PostVO;
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

/**
 * 帖子接口
 *
 * author: shaoshing
 *
 * <p><b>鉴权分工</b>：
 * <table border="1">
 *     <tr><th>接口</th><th>谁能调</th></tr>
 *     <tr><td>{@code /post/list/page}、{@code /post/detail}</td>
 *     <td><b>游客可访问</b>（已加入白名单，见 {@code SaTokenMvcConfig}）</td></tr>
 *     <tr><td>{@code /post/add}</td><td>登录用户（是否成员由 Service 查 {@code studio_member} 判定）</td></tr>
 *     <tr><td>{@code /post/my}</td><td>登录用户</td></tr>
 * </table>
 *
 * <p><b>为什么发帖只挂 {@code @SaCheckLogin} 而不挂 {@code @SaCheckRole("member")}</b>：
 * 「成员」不是角色标签，而是管理员在 {@code studio_member} 表里的档案。
 * 角色可能与档案不同步（有人被移出名录但角色没改），按角色放行会绕开真正的准入规则。
 * 所以这里只保证「已登录」，成员身份由 Service 认表判定——与考勤模块同一套路。
 *
 * <p><b>白名单必须写精确路径</b>：只放行 {@code /post/list/page} 与 {@code /post/detail}，
 * <b>绝不写成 {@code /post/**}</b>——那样会把将来新增的帖子管理端接口（审核、删除）一起放行。
 *
 * <p>接口地址前缀 {@code /api}，完整路径形如 {@code http://localhost:8080/api/post/list/page}。
 */
@RestController
@RequestMapping("/post")
@Tag(name = "帖子模块", description = "游客浏览已通过帖子；工作室成员发帖")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /**
     * 发布帖子（成员）
     *
     * <p>返回的是帖子 ID；内容进入<b>待审</b>，不会立刻出现在公开列表——
     * 作者要在「我的发帖」里看审核状态（先审后发的设计）。
     *
     * @param request 发布请求（标题、正文必填）
     * @return 新帖子 ID
     */
    @PostMapping("/add")
    @SaCheckLogin
    @Operation(summary = "【成员】发布帖子", description = "正文为 Markdown，图片需先用编辑器上传；发布后进入待审")
    public BaseResponse<Long> addPost(@RequestBody @Valid PostAddRequest request) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.addPost(request));
    }

    /**
     * 公开帖子列表（游客可访问）
     *
     * <p>只返回<b>已通过</b>的帖子，置顶优先、时间倒序。
     *
     * @param current  页码，默认 1
     * @param pageSize 每页条数，默认 10（上限 50）
     * @return 分页结果
     */
    @GetMapping("/list/page")
    @Operation(summary = "帖子列表（游客可访问）", description = "仅已通过；置顶优先 + 时间倒序")
    public BaseResponse<Page<PostFrontVO>> listApprovedByPage(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postService.listApprovedByPage(current, pageSize));
    }

    /**
     * 帖子详情（游客可访问），并累加浏览量
     *
     * @param id 帖子 ID
     * @return 详情（含 Markdown 正文）
     */
    @GetMapping("/detail")
    @Operation(summary = "帖子详情（游客可访问）", description = "仅已通过；待审或已驳回对公众等同于不存在")
    public BaseResponse<PostFrontDetailVO> getApprovedDetail(@RequestParam("id") long id) {
        // 用 long 接参：非数字字符串会由全局异常处理器兜成 A0401；这里再挡一次明显的非法值
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        return ResultUtils.success(postService.getApprovedDetail(id));
    }

    /**
     * 我的发帖（含待审与已驳回）
     *
     * <p>先审后发的关键接口：作者在这里看自己内容的审核状态，
     * 已驳回时 {@code rejectReason} 会给出理由。
     *
     * @param current  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @GetMapping("/my")
    @SaCheckLogin
    @Operation(summary = "我的发帖", description = "含待审与已驳回；驳回时带理由")
    public BaseResponse<Page<PostVO>> listMyByPage(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return ResultUtils.success(postService.listMyByPage(current, pageSize));
    }
}
