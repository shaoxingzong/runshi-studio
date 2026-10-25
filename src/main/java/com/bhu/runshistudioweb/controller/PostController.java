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
 * 鉴权分工：
 * 接口 | 谁能调
 * /post/list/page、/post/detail | 游客可访问（已加入白名单，见 SaTokenMvcConfig）
 * /post/add | 登录用户（是否成员由 Service 查 studio_member 判定）
 * /post/like | 登录用户（点赞幂等，只能赞不能取消）
 * /post/my | 登录用户
 *
 * 为什么发帖只挂 @SaCheckLogin 而不挂 @SaCheckRole("member")：
 * 「成员」不是角色标签，而是管理员在 studio_member 表里的档案。
 * 角色可能与档案不同步（有人被移出名录但角色没改），按角色放行会绕开真正的准入规则。
 * 所以这里只保证「已登录」，成员身份由 Service 认表判定——与考勤模块同一套路。
 *
 * 白名单必须写精确路径：只放行 /post/list/page 与 /post/detail，
 * 绝不写成 /post/**——那样会把将来新增的帖子管理端接口（审核、删除）一起放行。
 *
 * 接口地址前缀 /api，完整路径形如 http://localhost:8080/api/post/list/page。
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
     * 发布帖子（成员）：把标题与正文交出去，返回新帖子的 ID
     *
     * <p>为什么这样设计：鉴权只挂 {@code @SaCheckLogin}，刻意不挂
     * {@code @SaCheckRole("member")}。「是不是成员」的判定权威是 studio_member 表里的
     * 在队档案，而不是 user_role 这个权限标签——两者可能不同步（有人被移出名录但角色没改），
     * 按角色放行会绕开真正的准入规则。因此 Controller 只负责挡住「没登录」，
     * 成员身份留给 Service 查表判断，这与考勤模块是同一套分工。
     * 另一个取舍是返回 ID 而不是 VO：发帖后内容进入待审，作者本来也看不到
     * 「已公开」的样子，返回一个 ID 让前端跳「我的发帖」最直接。
     * Controller 自身只做三件事——接参、调 Service、包统一响应，
     * 校验与落库全在 Service 里，这样同一个业务动作不会有两份入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空：{@code @Valid} 只在「请求体存在」时逐字段校验，
     *     请求体整体为 null 时它根本不触发，后面取值就是空指针，
     *     会被兜成 B0001「系统错误」——而这其实只是一个参数问题，
     *     所以要在进入业务之前补上这一道；</li>
     *     <li>② 调 Service 并包成统一响应体。鉴权、成员校验、正文清洗、落库为待审、
     *     异步送 AI 初判全部发生在 Service 里，顺序不能换的理由见那边的注释。</li>
     * </ol>
     *
     * <p>容易踩的坑：把「返回了 ID」理解成「内容已公开」。新帖一律以待审状态入库，
     * 公开列表里查不到它是设计如此，不是接口没写进去；要确认状态得走「我的发帖」。
     * 另一个坑是别在这里顺手加一句「是不是成员」的判断——Controller 拿不到
     * 档案数据，硬判只能退化成查角色，那正是这套分工要避免的做法。
     *
     * @param request 发布请求，不能为 null；标题与正文必填（字段级约束由 {@code @Valid} 拦下，
     *                违反时得到 A0401 与具体字段提示）；正文里的图片必须是站内路径，
     *                外链图片会被 Service 拒绝；请求体里没有 status 字段，
     *                审核状态一律由服务端决定，前端传了也不会被采用
     * @return 统一响应体，data 是新帖子 ID（雪花算法生成的 19 位 Long，
     *         全局序列化配置会把它转成字符串，前端不要按数字处理）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     *         非在队成员或角色不足（A0301）、正文过长或含外链图片（A0401）、
     *         入库失败（B0001）时抛出，最终都由全局异常处理器转成标准响应
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
     * 点赞帖子（需登录、幂等）：给一篇已通过的帖子点一次赞，返回最新点赞数
     *
     * <p>为什么这样设计：用户身份必须从登录态取（{@code StpUtil.getLoginIdAsLong()}），
     * 请求体里刻意不收 userId——一旦收，就等于把「替别人点赞」做成了一个功能，
     * 而点赞数又会进热度排序，属于可被刷的业务指标。
     * 鉴权级别只到「已登录」：点赞是普通注册用户也能做的动作，不需要成员身份，
     * 因此这里既没有成员校验，也没有角色注解。
     * 返回值选「点赞后的最新点赞数」而不是 boolean：前端拿到数字直接刷新界面即可，
     * 不必自己 +1；如果让前端做乐观 +1，那么「重复点赞不计数」的幂等语义
     * 会让界面数字与实际值越差越远，表现为点赞数来回跳。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空：{@code @Valid} 管不到「请求体整体为 null」，
     *     少了这一步，一个空 body 会变成 500（B0001），而真实原因只是少传了参数；</li>
     *     <li>② 从登录态取用户 ID，与请求体里的 postId 一起交给 Service。
     *     两个参数的来源必须分开：ID 来自会话（可信），postId 来自请求体（不可信），
     *     在 Service 里还要校验 postId 为正数并确认帖子已通过审核。</li>
     * </ol>
     *
     * <p>容易踩的坑：本模块只能赞、不能取消，所以没有 unlike / toggle 接口——
     * 前端不要用「点一下切换状态」的交互去调它，否则用户点第二下会以为取消了赞，
     * 实际数字没变。另一个坑是 {@code @Valid} 的覆盖范围：它只校验字段级约束，
     * 字段上的 {@code @NotNull} 能挡住「没传 postId」，但挡不住「整个 body 是空的」。
     *
     * @param request 点赞请求，不能为 null；postId 必填且必须为正数
     *                （缺失得到「帖子 id 不能为空」，非法值得到「帖子 id 不合法」，
     *                两者都是 A0401）
     * @return 统一响应体，data 是点赞后的最新点赞数（Long）；重复点赞时原样返回，
     *         不重复 +1，因此这个数字可能和你点之前一样——那不是失败
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     *         postId 非法（A0401）、帖子不存在或尚未通过审核（A0402，两种情况
     *         同码同提示，不对外暴露「这篇存在只是没过审」）时抛出
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
     * 先审后发的关键接口：作者在这里看自己内容的审核状态，
     * 已驳回时 rejectReason 会给出理由。
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
     * 作者修改自己被驳回的帖子：保存后内容回到待审，重新走一遍审核
     *
     * <p>为什么这样设计：这里同样只挂 {@code @SaCheckLogin}，不挂成员角色，
     * 也不在 Controller 里判断「是不是本人」「是不是已驳回」。
     * 原因有两层：一是这两条判断都需要读库（帖子作者、当前状态），
     * Controller 做就得自己注入 Mapper，等于把业务判断挪到了 HTTP 层；
     * 二是这三条边界必须与「我的未公开帖子详情」保持完全一致，
     * 分散在两个类里迟早会漂移——一边放行了、另一边还拦着，
     * 表现为「能打开改稿页但一保存就报不存在」。
     * 保存后重置为待审也是刻意的：内容变了就必须重新过审，
     * 否则「先审后发」会被编辑功能绕开，改完的正文再没人看过。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空：{@code @Valid} 只校验字段级约束，请求体整体为 null 时不会触发，
     *     后面取值会抛空指针变成 500，这里提前给出 A0401；</li>
     *     <li>② 调 Service 完成「本人 + 已驳回」校验与改稿落库。Service 返回 void，
     *     因此这里固定包一个 {@code true}——失败一律抛异常，
     *     不存在「返回 false 表示没改成」的分支。</li>
     * </ol>
     *
     * <p>容易踩的坑：Service 把「不是本人」「状态不是已驳回」「帖子不存在」三种情况
     * 统一按「帖子不存在」返回（A0402），所以前端看到 A0402 时不能断定帖子被删了，
     * 也可能是「这篇不是你的」。另一个坑是别把返回的 true 当成「内容已重新公开」：
     * 它只表示保存成功，内容此刻是待审状态，公开列表里还看不到。
     *
     * @param request 改稿请求，不能为 null；id、标题、正文必填（id 必须是正数），
     *                摘要与封面选填，为空时由 Service 兜底生成；
     *                请求体里没有 status 字段，审核状态由服务端重置，传了也不会被采用
     * @return 统一响应体，data 恒为 true；走到这一行说明保存动作已经执行完毕
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     *         帖子不存在 / 非本人 / 状态不是已驳回（均 A0402）、
     *         正文过长或含外链图片（A0401）时抛出
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
     * 改稿的前置步骤：驳回稿的正文作者本来读不到（公开详情只返回已通过），
     * 要改就得先看到自己写了什么。仅本人 + 仅待审/已驳回。
     *
     * @param id 帖子 ID
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
