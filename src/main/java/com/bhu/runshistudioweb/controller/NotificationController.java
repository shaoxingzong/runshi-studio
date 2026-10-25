package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.model.dto.notification.NotificationQueryRequest;
import com.bhu.runshistudioweb.model.dto.notification.NotificationReadRequest;
import com.bhu.runshistudioweb.model.vo.notification.NotificationVO;
import com.bhu.runshistudioweb.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知接口（C 端，全部要求登录）
 *
 * 白名单一条都不加：/notification/** 全部走「默认要求登录」。
 * 通知是纯个人数据，没有任何匿名入口——与「游客可浏览帖子」的定位不同。
 * 这里每个方法都显式挂了 {@code @SaCheckLogin}，是纵深防御（多层各拦一次，
 * 任一层失效仍有下一层）：本路径本来就不在白名单里，全局拦截器已经会要求登录；
 * 但万一有人误把它加进白名单，注解仍能兜住——而「翻别人的通知」这类问题
 * 一旦漏出就是数据泄露。
 *
 * 投递不在这里暴露接口：NotificationService#notify 是内部能力，
 * 由业务触发点（审核结论、评论回复、身份授予）在事务内调用。对外开放「发通知」接口
 * 等于给任意登录用户一个骚扰别人的通道——那是产品不会要、安全不能给的东西。
 *
 * 未读数单独一个轻量接口（unread-count）：门户铃铛 60 秒轮询打它，
 * 与列表分页分开——轮询不该拉整页数据，未读数是 COUNT 查询、走索引，代价极低。
 *
 * 归属规则：所有接口都只操作「当前登录人」的数据，请求里没有 userId 参数。
 * 他人的通知一律按「不存在」处理（A0402），而不是「无权访问」——
 * 后者等于确认了这条通知存在，属于不必要的信息泄露。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/notification/page。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/notification")
@Tag(name = "站内通知", description = "审核结论、评论回复、身份授予的事件通知：列表、未读数、已读")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 分页查询我的通知（新的在前）
     *
     * <p>为什么这样设计：查询范围由服务端从登录态决定，不接受前端传 userId——
     * 否则任何人都能翻别人的通知。过滤条件里固定带 receiver_id 是数据隔离的底线，
     * 这条条件写在 Service 里，Controller 不做也做不了。
     * 排序按创建时间倒序：通知是「最近发生了什么」，新的必须在最上面。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 NotificationQueryRequest。它不是请求体，
     *     所以不需要 {@code @RequestBody}；一个参数都不传时字段全为 null，
     *     Service 会按「全部 + 第一页」处理；</li>
     *     <li>② 交给 Service：取当前登录人、纠正分页参数、拼「接收者 + 可选已读状态」条件、
     *     按创建时间倒序分页并转 VO；</li>
     *     <li>③ 包成统一响应体。没有通知时 records 是空列表。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid}，也不在 Controller 里判对象为 null。
     * 查询参数走 GET，校验失败抛的是 BindException，全局异常处理器没接它，
     * 会变成 B0001「系统错误」；而「页码传错」本来也不该让整个查询失败，
     * Service 会把 current 小于 1 收敛成 1、pageSize 收敛到上限。
     *
     * @param request 查询条件：isRead 过滤（0 只看未读、1 只看已读、null 全部）+ 分页；
     *                允许整体为 null
     * @return 分页结果，记录为 NotificationVO（含类型中文描述与来源业务指针）；
     * 没有通知时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）时抛出
     */
    @GetMapping("/page")
    @SaCheckLogin
    @Operation(summary = "我的通知列表", description = "可按已读/未读过滤，每页最多 50 条，按创建时间倒序")
    public BaseResponse<Page<NotificationVO>> listMyNotifications(NotificationQueryRequest request) {
        // ①②③ 归属过滤（receiver_id = 当前登录人）与分页归一化都在 Service 里，这里没有 userId 可传
        return ResultUtils.success(notificationService.listMy(request));
    }

    /**
     * 我的未读通知数（铃铛轮询专用）
     *
     * <p>为什么这样设计：单独一个 COUNT 接口，而不是让前端拉列表自己数。
     * 铃铛要周期性轮询，拉整页数据只为数个数，流量与数据库压力都不划算；
     * COUNT 查询走索引的最左前缀，代价极低。
     * 返回类型是 Long 而不是 Integer，这一点需要留意：项目的全局约定把 Long 序列化成字符串
     * （为 19 位雪花 ID 让路），因此前端拿到的是 "3" 而不是 3，需要 parseInt。
     * 这与统计模块「计数用 Integer 直接给数字」的约定不一致，属于模块间的已知差异；
     * 若要改成 Integer，必须同时改接口签名与前端取值，不能只改一边。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参。查谁的未读数由登录态决定，没有 userId 参数可传；</li>
     *     <li>② 交给 Service：拼「接收者 + 未读」两个等值条件并计数；</li>
     *     <li>③ 包成统一响应体。没有未读时返回 0，不会是 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：少一个条件都会出错——只带接收者会把已读算进来，
     * 只带未读会统计全站。两个等值条件必须成对出现，这个口径在 Service 里，
     * Controller 不要试图用「先拉列表再过滤」代替它。
     *
     * @return 未读条数；没有未读时返回 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）时抛出
     */
    @GetMapping("/unread-count")
    @SaCheckLogin
    @Operation(summary = "未读通知数", description = "门户铃铛轮询专用：COUNT 查询走索引，不与列表分页混用")
    public BaseResponse<Long> unreadCount() {
        // ① 无入参：查谁的未读数由登录态决定
        // ②③ 两个等值条件（接收者 + 未读）在 Service 里成对出现，缺一个都会算错
        return ResultUtils.success(notificationService.unreadCount());
    }

    /**
     * 标记单条通知已读（幂等）
     *
     * <p>为什么这样设计：幂等（重复调用结果一致）在这里有两层含义。
     * 归属上，他人的通知按「不存在」处理（A0402）而不是「无权访问」——
     * 后者等于确认了这条通知存在。更新语义上，只在「当前是未读」时才改成已读，
     * 重复点击匹配 0 行、已读时间保持不变，这才是真正的幂等：
     * 第一次阅读的时间才有意义，重复点击不该把它刷新成「刚刚」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，且这里刻意用「返回 true」而不是抛异常：
     *     请求体为 null 说明前端什么都没提交，从「已读」这个目标状态看，
     *     它已经是满足的；抛异常反而会让一个无副作用的操作变成错误；</li>
     *     <li>② 交给 Service：校验归属、带条件 UPDATE（未读 → 已读，并写入当前时间）；</li>
     *     <li>③ 固定返回 true。重复已读同样返回 true，前端不必区分「这次真的改了」还是「早就读过」。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要写成「先查出来、set 已读、updateById」。
     * 那样每次调用都会刷新已读时间，并发下两个请求还会都认为自己是第一次。
     * 正确做法是带「当前是未读」条件的 UPDATE，让数据库来判断这次到底改没改。
     *
     * @param request 已读请求：id 必填且为正整数；请求体整体为 null 时直接返回 true
     * @return true 表示操作完成（含「本来就已经读过」的情况）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 通知不存在或不属于当前登录人（A0402）时抛出
     */
    @PostMapping("/read")
    @SaCheckLogin
    @Operation(summary = "标记单条已读", description = "他人的通知按不存在处理（A0402）；幂等，重复调用不刷新已读时间")
    public BaseResponse<Boolean> markRead(@RequestBody @Valid NotificationReadRequest request) {
        // ① 空 body 时不报错而是直接返回 true：从「已读」这个目标状态看，它已经满足了
        if (request == null) {
            return ResultUtils.success(true);
        }
        // ②③ 归属校验与「未读才更新」的条件都在 Service 里，重复调用不会刷新已读时间
        notificationService.markRead(request.getId());
        return ResultUtils.success(true);
    }

    /**
     * 全部标记已读
     *
     * <p>为什么这样设计：只更新未读的那些，因此重复调用第二次返回 0。
     * 这个返回值是有意义的——前端据此判断「有没有发生变化」，
     * 决定要不要重新拉一次列表；若只返回 true，前端每次都得无条件刷新。
     * 为了拿到「实际更新了几条」，Service 必须走 Mapper 的 update 拿受影响行数，
     * 而不是只返回布尔值的 Service 方法。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参。更新范围是「当前登录人 + 未读」，由登录态决定；</li>
     *     <li>② 交给 Service：拼「接收者 + 未读」的更新条件，执行一条 UPDATE 并返回受影响行数；</li>
     *     <li>③ 把条数包成统一响应体。0 是正常结果（本来就没有未读），不是错误。</li>
     * </ol>
     *
     * <p>容易踩的坑：条件里必须带「未读」。少了它，已读通知的已读时间
     * 会被整体刷新成「现在」，让「最近读过的通知」排序全部错乱——
     * 而这种错乱不会报任何错，只能靠用户觉得「顺序怎么怪怪的」发现。
     *
     * @return 本次被置为已读的条数（已是已读的不计入，因此重复调用第二次返回 0）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）时抛出
     */
    @PostMapping("/read-all")
    @SaCheckLogin
    @Operation(summary = "全部标记已读", description = "只更新未读的；返回本次实际更新的条数")
    public BaseResponse<Integer> markAllRead() {
        // ① 无入参：更新范围是「当前登录人 + 未读」，由登录态决定
        // ②③ 返回受影响行数而不是布尔值：前端靠它判断要不要重新拉列表
        return ResultUtils.success(notificationService.markAllRead());
    }
}
