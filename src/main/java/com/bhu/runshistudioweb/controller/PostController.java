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

/**
 * 帖子接口
 *
 * 鉴权分工：/post/list/page 与 /post/detail 游客可访问（白名单精确登记）；/post/add、
 * /post/like、/post/my、/post/update-mine、/post/my/detail 要求登录。
 *
 * 发帖只挂 {@code @SaCheckLogin}：成员身份以 studio_member 档案为准，不认角色标签。
 *
 * @author shaoshing
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
     * 只挂 {@code @SaCheckLogin}：成员身份由 Service 认 studio_member 档案判定，不认角色标签。
     * 返回 ID 而不是 VO：内容进入待审，作者本来也看不到「已公开」的样子。
     *
     * @param request 标题与正文必填；正文图片须为站内路径，status 一律由服务端决定
     * @return 新帖子 ID（19 位，JSON 里是字符串），内容进入待审
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
     * 点赞帖子（需登录、幂等）：返回最新点赞数
     *
     * 用户 ID 从登录态取，请求体里没有 userId，否则「替别人点赞」就成了功能。
     * 只到「已登录」这一级：点赞是普通注册用户也能做的动作，不需要成员身份。
     *
     * @param request postId 必填且必须为正数
     * @return 点赞后的最新点赞数；重复点赞原样返回，不重复 +1
     */
    @PostMapping("/like")
    @SaCheckLogin
    @Operation(summary = "【登录用户】点赞帖子", description = "幂等：重复点赞不重复计数；只能赞，不能取消")
    public BaseResponse<Long> likePost(@RequestBody @Valid PostLikeRequest request) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(postService.likePost(StpUtil.getLoginIdAsLong(), request.getPostId()));
    }

    /**
     * 公开帖子列表（游客可访问）
     *
     * 只返回已通过的帖子，置顶优先、时间倒序。
     *
     * @param current 页码，默认 1
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
     * 待审或已驳回的帖子对公众等同于不存在。
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
     * 先审后发的关键接口：作者在这里看审核状态，已驳回时带 rejectReason。
     *
     * @param current 页码
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

    /**
     * 作者修改自己被驳回的帖子：保存后内容回到待审
     *
     * 只挂 {@code @SaCheckLogin}，不在这里判「是不是本人」「是不是已驳回」——两条判断都要读库，
     * 且必须与「我的未公开帖子详情」同一口径，分散在两个类里迟早漂移。
     *
     * @param request id、标题、正文必填；摘要与封面为空时由 Service 兜底生成
     * @return 恒为 true，失败一律抛异常
     */
    @PostMapping("/update-mine")
    @SaCheckLogin
    @Operation(summary = "【作者】修改被驳回的自己的帖子", description = "仅本人 + 仅已驳回；保存后重置为待审重新送审")
    public BaseResponse<Boolean> updateMyRejectedPost(@RequestBody @Valid PostUpdateRequest request) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        postService.updateMyRejectedPost(request);
        return ResultUtils.success(true);
    }

    /**
     * 作者读自己「未公开」帖子的详情（含正文，不累加浏览量）
     *
     * 改稿的前置步骤：公开详情只返回已通过，驳回稿的正文作者本来读不到。
     *
     * @param id 帖子 ID，必须大于 0
     * @return 详情（含正文）；非本人或状态不符按「不存在」返回
     */
    @GetMapping("/my/detail")
    @SaCheckLogin
    @Operation(summary = "【作者】我的未公开帖子详情", description = "仅本人 + 仅待审/已驳回；含正文，不累加浏览量")
    public BaseResponse<PostSelfDetailVO> getMyDetail(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        return ResultUtils.success(postService.getMyPostDetail(id));
    }
}
