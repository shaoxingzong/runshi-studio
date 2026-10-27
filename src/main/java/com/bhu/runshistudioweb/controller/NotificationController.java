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
 * 白名单一条都不加：通知是纯个人数据。每个方法显式挂 {@code @SaCheckLogin} 是纵深防御——
 * 路径本来就不在白名单，但万一有人误加进去，注解仍能兜住。
 *
 * 不开放「发通知」接口：notify 是内部能力，对外开放等于给任意登录用户一个骚扰别人的通道。
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
     * 查询范围由服务端从登录态决定，不接受前端传 userId，否则任何人都能翻别人的通知。
     * 不加 {@code @Valid}：GET 校验失败抛 BindException 会变成 B0001，而页码传错不该让查询失败。
     *
     * @param request isRead 过滤（0 未读 / 1 已读 / null 全部）+ 分页，允许整体为 null
     * @return 分页结果，没有通知时 records 是空列表
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
     * 单独一个 COUNT 接口而不是让前端拉列表自己数：轮询拉整页只为数个数，代价不成比例。
     * 返回 Long 会被全局序列化成字符串，前端要 parseInt——与统计模块「计数用 Integer」不一致。
     *
     * @return 未读条数；没有未读时返回 0
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
     * 他人的通知按「不存在」（A0402）处理，不暴露存在性；只在当前是未读时才更新，
     * 重复点击不刷新已读时间。请求体为 null 时直接返回 true：目标状态本来就已满足。
     *
     * @param request id 必填且为正整数；整体为 null 时直接返回 true
     * @return true 表示操作完成（含「本来就已经读过」）
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
     * 条件必须带「未读」：少了它会把已读时间整体刷成现在，让「最近读过」的排序全部错乱且不报错。
     *
     * @return 本次被置为已读的条数，重复调用第二次返回 0
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
