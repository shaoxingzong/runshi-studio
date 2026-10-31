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
     * @param request 只用于读取 IP 与 UA，不读取业务参数
     */
    @PostMapping("/check-in")
    @SaCheckLogin
    @Operation(summary = "【成员】签到", description = "一天内多次签到受最小间隔限制；是否内网按服务端网段判定")
    public BaseResponse<AttendanceCheckInVO> checkIn(HttpServletRequest request) {
        return ResultUtils.success(attendanceService.checkIn(
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR),
                request.getHeader(HEADER_USER_AGENT)));
    }

    /**
     * 今日出勤看板
     *
     * @return 今日看板列表；没有在队成员时返回空列表
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
     * @return 本人最近的签到记录，按时刻倒序；无记录时返回空列表
     */
    @GetMapping("/me")
    @SaCheckLogin
    @Operation(summary = "【成员】我的签到历史", description = "只返回本人记录，最近若干条")
    public BaseResponse<List<AttendanceMeVO>> getMyHistory() {
        return ResultUtils.success(attendanceService.getMyHistory());
    }
}
