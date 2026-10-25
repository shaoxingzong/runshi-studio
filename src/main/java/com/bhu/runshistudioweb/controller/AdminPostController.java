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
 * 全部要求 admin 角色：这里的每一个动作都在改别人的内容
 * （决定公开与否、删掉谁的内容、置顶谁的帖子），必须严格限权。
 *
 * 鉴权级别为什么是 {@code @SaCheckRole(ADMIN)} 而不是 {@code @SaCheckLogin}：
 * 「已登录」只回答「你是谁」，回答不了「你能不能动别人的稿子」。
 * 注解里的取值来自 UserRoleConstant.ADMIN——Sa-Token 的注解只接受编译期常量，
 * 不能写枚举的方法调用，所以角色字面量单独放在常量类里。
 * 角色是实时查库判定的，因此把某人降级后，他下一次请求就立刻失去管理权限。
 *
 * 路径为什么挂在 /admin/post 而不是 /post：
 * 白名单（SaTokenMvcConfig 里登记「允许匿名访问」的路径清单）放行了
 * /post/list/page、/post/detail、/post/comment/list 三条匿名路径，用的是精确匹配；
 * 管理端接口另起 /admin 前缀，与匿名接口在路径上就分开，
 * 避免将来有人为了省事把白名单写成 /post/** 而把审核接口一起放出去。
 *
 * 两类接口的分工：
 * - 审核（/pending、/audit、评论的两个对应接口）：面向待审队列，批量处置；
 * - 管理（/list/page、/delete、/pin、/update）：面向全部内容，查阅、删除、置顶、代改。
 *
 * 返回的 VO 分三档，不能互相顶替：
 * - PostVO：列表项，带审核状态与驳回理由，但不带 Markdown 正文；
 * - PostAdminDetailVO：单条详情，比列表多正文，供编辑弹窗回填；
 * - PostCommentAuditVO：评论待审项，比公开评论 VO 多审核字段与机器初判结论。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/admin/post/pending。
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
     * 帖子待审队列：只列「待审」，按提交时间先进先出
     *
     * <p>为什么这样设计：先审后发之下，作者提交完看不到自己的内容，队列顺序就是他等待的时长。
     * 按创建时间升序排，先提交的先被处理；若按 id 倒序（很多人对列表的默认直觉），
     * 最早提交的那批会被不断涌入的新内容压到队尾，等于永远轮不到。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 两个分页参数由 Spring 从查询串绑定，都写了 defaultValue：缺参时直接填默认值，
     *     不会抛「缺少必填参数」，因此这里可以用基本类型 long 而不是包装类型 Long；</li>
     *     <li>② 调 Service 取一页，页码小于 1、pageSize 过大这类越界值由 Service 收敛，
     *     Controller 不重复校验——两处都判会让「以哪一处为准」变得含糊；</li>
     *     <li>③ 包成统一响应体。Controller 不做状态过滤，「只看待审」是 Service 里的查询条件。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只挂了角色注解，没有再写登录注解，这不是漏写。
     * 角色校验的前提就是已登录（未登录时 Sa-Token 先抛 NotLoginException，
     * 全局异常处理器转成 A0201），所以角色注解已经覆盖了登录校验。
     * 反过来只挂登录注解才是事故：任何登录用户都能读到全站待审内容与作者名。
     *
     * @param current 页码，从 1 开始；不传时为 1。传 0 或负数不报错，Service 当成 1
     * @param pageSize 每页条数；不传时为 10，超过上限会被收敛（防止一次拉全表）
     * @return 分页结果，记录为 PostVO（含状态与作者名，不含正文）；队列为空时 records 是空列表，不会是 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）或角色不是 admin（A0301）时抛出
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
     * <p>为什么这样设计：一次请求处理一批 id，管理员不必在页面之间来回跳。
     * 返回值是「实际处理的条数」而不是 true/false：批次里可能有人刚被别的管理员审过，
     * 这些记录不会重复生效，前端需要据此提示「提交 5 条，实际处理 3 条」。
     * 驳回理由与通过动作共用一个请求体，而不是另开一个「驳回」接口——
     * 两者只差一个动作取值，拆成两个接口就无法「批量选中、部分通过部分驳回」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（ids 非空、action 非空），
     *     请求体整体为 null 时它根本不触发，后面取值会抛空指针被兜成 B0001；</li>
     *     <li>② 交给 Service：校验动作取值与驳回理由、按「id 在批次内 + 状态 = 待审」批量更新、
     *     再逐个通知作者。整段在一个事务里，中途失败不会留下「审了一半」；</li>
     *     <li>③ 把条数包成统一响应体。不把 0 当失败——「一条都没处理」是正常结果，
     *     说明这批内容已经被处理过了，不该报错。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code @Valid} 管不到「请求体整体为 null」。少了第 ① 步，
     * 一个空 body 的请求会变成 B0001「系统错误」，而真实原因只是前端没带请求体。
     * 另外别把 Service 返回的条数当成「ids 的个数」——并发审核时两者并不相等。
     *
     * @param request 审核请求：ids 至少一个、action 只能是 approve 或 reject、
     *                驳回时 rejectReason 必填且不超过 256 字；整个对象为 null 时得到 A0401
     * @return 实际处理的条数（已被处理过的 id 不计入，因此可能小于传入的 id 个数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 动作取值非法、驳回未填理由（A0401）时抛出
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
     * <p>为什么这样设计：评论与帖子是两条独立的审核队列。合成一个接口会让管理员
     * 无法「只处理评论」——两者处置节奏不同，帖子要读全文，评论扫一眼就能判断。
     * 返回 PostCommentAuditVO 而不是公开列表用的 PostCommentVO：后者服务于游客，
     * 结构上没有审核状态与机器初判结论，队列用它就看不到「机器在犹豫什么」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 两个分页参数由 Spring 绑定，都有默认值，缺参不报错；</li>
     *     <li>② 调 Service 取待审评论一页，按提交时间升序，与帖子队列同一口径；</li>
     *     <li>③ 包成统一响应体。审核状态字段由 VO 承载，Controller 不再加工。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的路径是 /comment/pending，挂在 /admin/post 下而不是另起
     * /admin/comment。评论依附于帖子，同一模块共用前缀，白名单只要盯住 /admin 这一段
     * 就整片被挡住；若拆成两个前缀，将来漏配一处的风险就多一倍。
     *
     * @param current 页码，从 1 开始；不传时为 1
     * @param pageSize 每页条数；不传时为 10，超过上限会被收敛
     * @return 分页结果，记录为 PostCommentAuditVO（含评论正文、所属帖子与审核状态）；
     * 队列为空时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）或角色不是 admin（A0301）时抛出
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
     * <p>为什么这样设计：与帖子审核共用同一个 AuditRequest，因为两者的动作完全同构
     * （选一批 id、决定通过还是驳回、驳回填理由）。差别在副作用：评论通过后要
     * 累加所属帖子的评论数，并通知被回复的人——这些都在 Service 里完成，
     * Controller 看不到也不该看到。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：先查出真正处于待审的评论（保证幂等、并拿到按帖子分组的增量），
     *     再一条 UPDATE 处理整批，最后累加评论数、发通知；</li>
     *     <li>③ 返回实际处理条数。批次里没有待审评论时返回 0，这是正常结果不是错误。</li>
     * </ol>
     *
     * <p>容易踩的坑：评论数只在「通过」这一刻累加，发布评论时并不加。
     * 若在发布时就加，用户会看到「有 1 条评论」但点开什么都没有——
     * 因为那条评论还在待审。这个口径由 Service 保证，Controller 不要试图「顺手补一下」。
     *
     * @param request 审核请求：ids 至少一个、action 为 approve 或 reject、驳回时理由必填
     * @return 实际处理的评论条数（可能小于传入的 id 个数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 动作非法或驳回缺理由（A0401）时抛出
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
     * <p>为什么这样设计：只删顶层评论会留下 parentId 指向已删评论的孤儿回复，
     * 前端会渲染出没有上下文的内容，所以顶层与它的回复必须在同一次删除里处理完。
     * 返回删除条数而不是布尔值：前端要提示「删除 N 条（含回复）」，
     * 布尔值给不出这个信息。另外只有「已通过」的评论可以删，待审的应当走驳回——
     * 驳回会把理由送到作者眼前，直接删掉他什么都收不到。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，且判两层：请求体整体为 null，以及 id 字段为 null。
     *     为什么 id 也要判——{@code @Valid} 只在校验被触发时才生效，空 body 时整条链路都不校验；</li>
     *     <li>② 取 id 交给 Service：校验状态、写删除人、删主评论与回复、把帖子评论数减回去；</li>
     *     <li>③ 返回实际删除条数，由前端组织提示文案。</li>
     * </ol>
     *
     * <p>容易踩的坑：本类有两个同名的 DeleteRequest——帖子模块的
     * {@code model.dto.post.DeleteRequest} 与通用的 {@code model.dto.common.DeleteRequest}。
     * 这里 import 的是前者，两者字段相同但语义不同，换错 import 编译器不会报错，
     * 只会在将来某一方加字段时悄悄错位。
     *
     * @param request 删除请求：id 必填且必须为正整数；整体为 null 或 id 为 null 时得到 A0401
     * @return 实际删除的条数（含其下的回复），前端可据此提示「删除 N 条」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 评论不存在（A0402）、评论不在已通过状态（A0401）时抛出
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
     * 帖子全量列表（三种状态均可查，支持按状态 / 标题 / 作者筛选）
     *
     * <p>为什么这样设计：它服务于「帖子管理」台账页，与待审队列是两种用途——
     * 队列只有一种状态、不需要筛选；台账要能把已通过、已驳回的内容都翻出来，
     * 否则「审核」就成了唯一能碰到内容的入口，治理无从谈起。
     * 参数用 GET + 对象绑定（不是 POST + 请求体）：查询不改变服务端状态，
     * 用 GET 才能把筛选条件直接贴进链接里排查问题。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 按字段名把查询串绑定成 PostAdminQueryRequest。一个参数都不传时
     *     对象仍然存在、字段全为 null，但这里仍兜一层，避免 Service 拆箱子时 NPE；</li>
     *     <li>② 交给 Service：校验状态取值、把作者关键词解析成 userId 集合、
     *     再按创建时间倒序分页。分页越界与排序字段白名单也在 Service 里；</li>
     *     <li>③ 包成统一响应体。列表刻意不带正文，一页十条全文会把响应体撑大几十倍。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法刻意不加 {@code @Valid}。这些参数走 GET 传递，
     * 校验失败抛的是 BindException，全局异常处理器没有为它写处理器，
     * 会落到兜底分支变成 B0001「系统错误」；而「页码传错」本来也不该让整个查询失败。
     * 需要报错的只有「状态取值不在枚举内」，由 Service 显式抛 A0401 并给出可读提示。
     *
     * @param request 查询条件：status 精确、titleKeyword 与 authorKeyword 模糊、分页；
     *                允许整体为 null 或字段全空（等价于查全部状态的第一页）
     * @return 分页结果，记录为 PostVO（含作者展示名）；筛选不到内容时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 状态取值非法（A0401）时抛出
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】帖子全量列表", description = "三种状态均可查；按状态/标题/作者筛选，最新提交在前")
    public BaseResponse<Page<PostVO>> listForAdmin(PostAdminQueryRequest request) {
        // ① @Valid 不校验「对象整体为 null」这种场景，兜一层避免 Service 里拆箱子 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 三个筛选项全是「传了才生效」，不传表示不限，而不是查该字段为 null 的记录
        return ResultUtils.success(postService.listForAdmin(request));
    }

    /**
     * 删除帖子：同事务级联删除该帖的全部评论与点赞关系
     *
     * <p>为什么这样设计：只删帖子会留下两类孤儿——评论（父帖没了，却还占着审核队列）
     * 与点赞关系（还挂在已不存在的帖子上）。三处写必须在同一事务里，否则中途失败
     * 就会留下「父帖没了、子行还在」的脏数据，而且不会有任何报错。
     * 待审帖子不能删：先审后发之下作者只知道「我提交了」，直接删掉他什么都收不到，
     * 该走驳回让理由送达作者。已通过（要下线）与已驳回（要清理）才可删。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，请求体与 id 一起判——空 body 时 {@code @Valid} 不会生效；</li>
     *     <li>② 交给 Service：写操作人痕迹、删评论、删点赞关系、最后删帖子本体。
     *     帖子必须最后删，顺序反了中途失败就会留下没有父帖的子行；</li>
     *     <li>③ 返回删除的内容行数（帖子 1 + 其下评论数）。点赞关系是物理删除，不计入。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值是 int 而不是 boolean，因为「删了几行」是有意义的：
     * 评论多的帖子会返回一个较大的数字，前端提示「删除 1 篇帖子及 12 条评论」比
     * 一句「删除成功」有用得多。不要因为「方法名叫删除」就把它当成布尔值使用。
     *
     * @param request 删除请求：id 必填且为正整数
     * @return 实际删除的内容行数（帖子 1 + 其下评论数；点赞关系不计入）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 帖子不存在（A0402）、帖子仍在待审（A0401）时抛出
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
     * <p>为什么这样设计：置顶是逐篇的运营决策（「这篇放最上面」），一次操作多篇没有产品意义，
     * 所以接口收单个 id，不搞批量。置顶与取消置顶共用一个接口、靠 pinned 取值区分，
     * 而不是拆成 /pin 与 /unpin：两者的入参结构与权限完全相同，拆开只会多一处需要维护的路径。
     * 只有已通过的帖子能置顶——公开列表只返回已通过的内容，置顶一篇待审帖在门户上没有效果，
     * 属于典型误操作，因此在服务端挡掉并给出可读提示，而不是让用户点了没反应。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（请求体为 null、id 为 null 都要挡）。pinned 的取值 0/1 由 Service 校验，
     *     不在这一层判——取值规则只留一处，将来加取值时才不会漏改；</li>
     *     <li>② 调 Service：取帖子、置顶时校验状态、更新 pinned。失败一律抛异常；</li>
     *     <li>③ 固定返回 true。本方法没有「部分成功」的语义，能走到这里就是改成了。</li>
     * </ol>
     *
     * <p>容易踩的坑：取消置顶刻意不限制帖子状态。帖子被编辑后会重置为待审、而 pinned 仍是 1，
     * 若此时不许取消置顶，管理员就卡在一个「改不回来」的状态里。恢复操作永远不该被拦住——
     * 这是本接口唯一的不对称之处，不要为了「对称好看」给它补上状态校验。
     *
     * @param request 置顶请求：id 必填且为正整数，pinned 取 1（置顶）或 0（取消置顶）
     * @return 固定为 true（失败会抛异常，不会返回 false）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 帖子不存在（A0402）、pinned 取值非法或对非已通过帖子置顶（A0401）时抛出
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
     * 编辑帖子（仅管理员），保存后非待审状态一律重置为待审重新送审
     *
     * <p>为什么这样设计：编辑权只给管理员。先审后发意味着内容公开前已过审，
     * 若作者能自由修改已通过的正文，等于给审核开了后门——改完的内容没人再看。
     * 同理，管理员改完也必须重新送审，否则「先审后发」会被编辑功能整体绕开。
     * 代价是改个别字也会让文章暂时下线，因此前端必须提示「已保存，修改内容将重新送审」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 管不到整体为 null 的请求体）；</li>
     *     <li>② 交给 Service：校验正文长度与图片白名单、重置为待审、清空上一轮审核痕迹、
     *     重新送机器初判。这一段顺序不能换——先清痕迹再校验，失败时会留下状态被改过的行；</li>
     *     <li>③ 固定返回 true。保存失败一律抛异常，没有「返回 false 表示没改成」的分支。</li>
     * </ol>
     *
     * <p>容易踩的坑：正文里的图片必须是站内路径（/uploads/...），外链图片会被拒绝。
     * 这不是多余的限制：外链图片会在对方服务器失效后变成破图，而管理员无法事后修复；
     * 同时外链也是把访客 IP 泄露给第三方的常见途径。
     *
     * @param request 编辑请求：id、标题、正文必填；摘要与封面选填，为空时由服务端兜底生成
     * @return 固定为 true（失败会抛异常）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 帖子不存在（A0402）、正文过长或含外链图片（A0401）时抛出
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
     * <p>为什么这样设计：编辑弹窗要把正文回填到编辑器里，而列表 VO 刻意不带正文，
     * 公开详情接口又不能复用——它只返回已通过的帖子，且每次调用都会给浏览量 +1，
     * 管理员点开一次编辑就污染一次内容热度。因此这里单独一个接口、单独一个 VO，
     * 三种审核状态都能取到，且不带任何副作用。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（用基本类型 long，不写 required = false）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何帖子，直接给出「id 不合法」，
     *     省掉一次数据库往返，也避免把「参数错」伪装成「帖子不存在」；</li>
     *     <li>③ 调 Service 取详情并包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用的是 long 而不是 Long，也没有 required = false。
     * 缺参时 Spring 会抛「缺少必填参数」异常，落到全局兜底变成 B0001；
     * 传非数字（例如 ?id=abc）同样被兜成 B0001 而不是 A0401。
     * 这是本方法接受的一个取舍——编辑弹窗总是带着 id 调，缺参只可能是前端写错了 URL；
     * 若某个接口的 id 来自用户输入，应改用包装类型 Long 并在方法内显式判空。
     *
     * @param id 帖子 ID，必须大于 0；传 0 或负数得到 A0401，缺参或传非数字得到 B0001
     * @return 详情（含 Markdown 正文），记录为 PostAdminDetailVO；帖子不存在时抛 A0402
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * id 不合法（A0401）、帖子不存在（A0402）时抛出
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
