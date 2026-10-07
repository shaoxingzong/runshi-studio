package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.model.vo.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.AttendanceMeVO;
import com.bhu.runshistudioweb.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考勤接口（**仅工作室成员可用**）
 *
 * author: shaoshing
 *
 * <p><b>准入规则</b>：三个接口都先要求登录（{@code @SaCheckLogin}），
 * 再由 {@code AttendanceServiceImpl} 校验「是不是在队成员」，
 * 不是则抛 {@code A0301 无权限}。普通注册用户三个接口一律进不去。
 *
 * <p><b>这里只挂 {@code @SaCheckLogin} 而不挂 {@code @SaCheckRole("member")}</b>，原因是刻意的：
 * 「成员」不是一个角色标签，而是管理员在 {@code studio_member} 表里录的一条档案。
 * 角色可能与档案不同步（比如有人被移出名录但角色没改），
 * 若按角色放行，就绕开了真正的准入规则。所以权限判定统一认表，不认角色。
 *
 * <p>Controller 的职责与 {@link UserController} 一致：接参、调 Service、包成
 * {@link BaseResponse}；不写业务分支。唯一的额外工作是<b>在请求线程里把 IP 与 UA 取出来</b>——
 * Service 层刻意不认识 HTTP（这样「给定这些头该判内网还是外网」可脱离容器直接单测）。
 *
 * <p>接口地址前缀 {@code /api}，完整路径形如 {@code http://localhost:8080/api/attendance/check-in}。
 */
@RestController
@RequestMapping("/attendance")
@Tag(name = "考勤模块", description = "工作室成员签到、今日出勤看板与个人历史")
public class AttendanceController {

    /** Nginx 惯用配置 {@code proxy_set_header X-Real-IP $remote_addr;} 对应的头名 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 逐跳追加的代理链，形如 {@code 客户端, 代理1, 代理2} */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String HEADER_USER_AGENT = "User-Agent";

    @Resource
    private AttendanceService attendanceService;

    /**
     * 成员签到
     *
     * <p>无需传参：签到主体是当前登录账号，网络信息由服务端从请求里取。
     * 也就是说客户端<b>无法</b>通过传参影响「算不算内网」——判定完全依赖 TCP 对端是否可信。
     *
     * @param request 当前请求（只用于取 IP 与 UA）
     * @return 本次签到结论：时刻 / 是否内网 / 本月累计次数
     */
    @PostMapping("/check-in")
    @SaCheckLogin
    @Operation(summary = "【成员】签到", description = "一天内多次签到受最小间隔限制；是否内网按服务端网段判定")
    public BaseResponse<AttendanceCheckInVO> checkIn(HttpServletRequest request) {
        // 这几个值必须在当前请求线程内取好：它们是 ThreadLocal / socket 层的东西，
        // 放进异步分支就取不到了（AiChatServiceImpl 里有同样的教训）
        return ResultUtils.success(attendanceService.checkIn(
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR),
                request.getHeader(HEADER_USER_AGENT)));
    }

    /**
     * 今日出勤看板
     *
     * <p>返回全部在队成员的今日出勤，未打卡的 {@code checkedIn=false}。
     * 响应<b>不含 IP、UA、账号</b>等任何位置或身份信息，只有姓名、时间、次数与内外网标记。
     *
     * @return 今日看板列表
     */
    @GetMapping("/board/today")
    @SaCheckLogin
    @Operation(summary = "【成员】今日出勤看板", description = "谁打卡了、几点、本月几次；未打卡成员也列出")
    public BaseResponse<List<AttendanceBoardVO>> getTodayBoard() {
        return ResultUtils.success(attendanceService.getTodayBoard());
    }

    /**
     * 我的签到历史
     *
     * @return 本人最近的签到记录（倒序，有上限）
     */
    @GetMapping("/me")
    @SaCheckLogin
    @Operation(summary = "【成员】我的签到历史", description = "只返回本人记录，最近若干条")
    public BaseResponse<List<AttendanceMeVO>> getMyHistory() {
        return ResultUtils.success(attendanceService.getMyHistory());
    }
}
