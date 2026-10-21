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
 * author: shaoshing
 *
 * <p><b>白名单一条都不加</b>：{@code /notification/**} 全部走「默认要求登录」。
 * 通知是纯个人数据，没有任何匿名入口——与「游客可浏览帖子」的定位不同。
 *
 * <p><b>投递不在这里暴露接口</b>：{@code NotificationService#notify} 是内部能力，
 * 由业务触发点（审核结论、评论回复、身份授予）在事务内调用。对外开放「发通知」接口
 * 等于给任意登录用户一个骚扰别人的通道——那是产品不会要、安全不能给的东西。
 *
 * <p><b>未读数单独一个轻量接口</b>（{@link #unreadCount}）：门户铃铛 60s 轮询打它，
 * 与列表分页分开——轮询不该拉整页数据，未读数是 {@code COUNT(*)} 走索引，毫无压力。
 *
 * <p>接口地址前缀 {@code /api}，完整路径形如 {@code http://localhost:8080/api/notification/page}。
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
     * @param request 查询条件（isRead 过滤 + 分页；允许为空，按全部 + 第一页处理）
     * @return 分页结果，记录为脱敏后的 {@link NotificationVO}
     */
    @GetMapping("/page")
    @SaCheckLogin
    @Operation(summary = "我的通知列表", description = "可按已读/未读过滤，每页最多 50 条，按创建时间倒序")
    public BaseResponse<Page<NotificationVO>> listMyNotifications(NotificationQueryRequest request) {
        return ResultUtils.success(notificationService.listMy(request));
    }

    /**
     * 我的未读通知数（铃铛轮询专用）
     *
     * @return 未读条数
     */
    @GetMapping("/unread-count")
    @SaCheckLogin
    @Operation(summary = "未读通知数", description = "门户铃铛轮询专用：COUNT 查询走索引，不与列表分页混用")
    public BaseResponse<Long> unreadCount() {
        return ResultUtils.success(notificationService.unreadCount());
    }

    /**
     * 标记单条通知已读（幂等）
     *
     * @param request 已读请求（id 必填）
     * @return true 表示操作完成（重复已读同样返回 true，不刷新已读时间）
     */
    @PostMapping("/read")
    @SaCheckLogin
    @Operation(summary = "标记单条已读", description = "他人的通知按不存在处理（A0402）；幂等，重复调用不刷新已读时间")
    public BaseResponse<Boolean> markRead(@RequestBody @Valid NotificationReadRequest request) {
        // @Valid 只保证字段级约束，请求体整体为 null 时不会触发，兜一层避免 Service 里 NPE
        if (request == null) {
            return ResultUtils.success(true);
        }
        notificationService.markRead(request.getId());
        return ResultUtils.success(true);
    }

    /**
     * 全部标记已读
     *
     * @return 本次被置为已读的条数（重复调用第二次返回 0）
     */
    @PostMapping("/read-all")
    @SaCheckLogin
    @Operation(summary = "全部标记已读", description = "只更新未读的；返回本次实际更新的条数")
    public BaseResponse<Integer> markAllRead() {
        return ResultUtils.success(notificationService.markAllRead());
    }
}
