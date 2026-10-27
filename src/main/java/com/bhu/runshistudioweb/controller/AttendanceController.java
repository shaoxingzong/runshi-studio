package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMeVO;
import com.bhu.runshistudioweb.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考勤接口（仅工作室成员可用）
 *
 * 三个接口都只挂 {@code @SaCheckLogin}，成员身份由 Service 查 studio_member 在队档案判定，
 * 不是成员抛 A0301。不挂 {@code @SaCheckRole("member")}：角色标签可能与档案不同步，
 * 按角色放行会绕开真正的准入规则。白名单里没有任何 /attendance 路径。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/attendance")
@Tag(name = "考勤模块", description = "工作室成员签到、今日出勤看板与个人历史")
@RequiredArgsConstructor
public class AttendanceController {

    /** Nginx 惯用配置 proxy_set_header X-Real-IP $remote_addr; 对应的头名 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 逐跳追加的代理链，形如 客户端, 代理1, 代理2 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String HEADER_USER_AGENT = "User-Agent";

    private final AttendanceService attendanceService;

    /**
     * 成员签到
     *
     * 签到主体与网络信息全由服务端决定，客户端传参影响不了「算不算内网」；因此本方法没有任何
     * 请求参数，加一个 userId 就等于允许替别人签到。四个原始值须在请求线程内取出再交给 Service。
     *
     * @param request 只用于读取 IP 与 UA，不读取业务参数
     * @return 签到时刻、是否判为内网、本月累计次数（含本次）
     */
    @PostMapping("/check-in")
    @SaCheckLogin
    @Operation(summary = "【成员】签到", description = "一天内多次签到受最小间隔限制；是否内网按服务端网段判定")
    public BaseResponse<AttendanceCheckInVO> checkIn(HttpServletRequest request) {
        // ①② 这四个值必须在当前请求线程内取好再传下去：
        // request 的生命周期只到本次请求结束，异步线程里再用它就是错的
        // ③ 包成统一响应体：签到结论够前端直接刷新页面，不必再调一次看板
        return ResultUtils.success(attendanceService.checkIn(
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR),
                request.getHeader(HEADER_USER_AGENT)));
    }

    /**
     * 今日出勤看板
     *
     * 未打卡的成员也要列出（checkedIn=false），否则管理员看不出「谁没来」。
     * 响应不含 IP、UA 与账号：看板是成员互见的页面，多一个字段就多一处泄露面。
     *
     * @return 今日看板列表；没有在队成员时返回空列表
     */
    @GetMapping("/board/today")
    @SaCheckLogin
    @Operation(summary = "【成员】今日出勤看板", description = "谁打卡了、几点、本月几次；未打卡成员也列出")
    public BaseResponse<List<AttendanceBoardVO>> getTodayBoard() {
        // ① 无入参：看板固定是「今天」，日期不由请求方决定
        // ②③ 成员校验与聚合查询都在 Service 内，Controller 只负责转发与包装
        return ResultUtils.success(attendanceService.getTodayBoard());
    }

    /**
     * 我的签到历史
     *
     * 没有 userId 参数：「查谁的」由登录态决定，否则「看别人的签到历史」就成了功能。
     * 条数上限在 Service 里——考勤表持续增长，不加限制迟早把整表拉进内存。
     *
     * @return 本人最近的签到记录，按时刻倒序；无记录时返回空列表
     */
    @GetMapping("/me")
    @SaCheckLogin
    @Operation(summary = "【成员】我的签到历史", description = "只返回本人记录，最近若干条")
    public BaseResponse<List<AttendanceMeVO>> getMyHistory() {
        // ① 无入参，也不接受分页参数：条数上限在 Service 里，前端不需要也无法指定
        // ②③ 「查谁的」由登录态决定，因此这里没有任何用户参数可传；结果直接包成响应体
        return ResultUtils.success(attendanceService.getMyHistory());
    }
}
