package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMonthStatVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceRecordVO;
import com.bhu.runshistudioweb.service.AttendanceStatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端考勤统计接口
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/attendance")
@SaCheckRole(UserRoleConstant.ADMIN)
@Tag(name = "考勤管理", description = "管理员按月查看全员签到天数与次数")
@RequiredArgsConstructor
public class AdminAttendanceController {

    private final AttendanceStatService attendanceStatService;

    /**
     * 某月全员签到汇总
     *
     * @param month 月份 yyyy-MM；不传按当前月（东八区）
     * @return 每人一行：签到天数、签到次数、内网次数、首次/末次签到
     */
    @GetMapping("/summary")
    @Operation(summary = "【管理员】月度签到汇总", description = "默认当月；含在队但整月未签到的成员（0 天 0 次）")
    public BaseResponse<List<AttendanceMonthStatVO>> summary(
            @RequestParam(value = "month", required = false) String month) {
        return ResultUtils.success(attendanceStatService.listMonthStats(month));
    }

    /**
     * 某成员某月的原始签到记录（时间 + IP）
     *
     * @param userId 成员绑定的账号 ID
     * @param month 月份 yyyy-MM；不传按当前月
     * @return 按签到时刻倒序，一条签到一行
     */
    @GetMapping("/records")
    @Operation(summary = "【管理员】成员签到记录", description = "某成员某月每次签到的时间与来源 IP")
    public BaseResponse<List<AttendanceRecordVO>> records(
            @RequestParam("userId") long userId,
            @RequestParam(value = "month", required = false) String month) {
        // 负数交给 Service 挡（那里还要做月份解析），这里只挡 0/负值的明显非法输入
        ThrowUtils.throwIf(userId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        return ResultUtils.success(attendanceStatService.listMemberRecords(userId, month));
    }
}
