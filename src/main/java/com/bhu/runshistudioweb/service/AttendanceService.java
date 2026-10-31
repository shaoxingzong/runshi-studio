package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMeVO;

import java.util.List;

/**
 * 考勤签到服务接口
 *
 * @author shaoshing
 */
public interface AttendanceService extends IService<StudioAttendance> {

    /**
     * 当前登录成员签到
     *
     * @param remoteAddr TCP 对端地址（可能带端口）；null 时按「外网」处理并落占位串，不抛异常
     * @param xRealIp / xForwardedFor 请求头原始值，可为 null；仅当 remoteAddr 可信时才采信
     * @param userAgent 可为 null，超长会被截断到列宽 512
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、签到过于频繁（A0401）、入库失败（B0001）
     */
    AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent);

    /**
     * 今日出勤看板（仅成员可见）
     *
     * @return 今日看板列表，每人一条；未打卡成员同样在列表里（checkedIn=false、checkInAt=null），因此列表长度等于「在队且已绑账号的成员数」，不等于今日签到人数
     * @throws BusinessException 未登录（A0201）或调用者非在队成员（A0301）
     */
    List<AttendanceBoardVO> getTodayBoard();

    /**
     * 本人签到历史（仅成员可见）
     *
     * @return 本人历史列表，按签到时刻倒序、最多最近若干条；无记录时返回空列表，不返回 null
     * @throws BusinessException 未登录（A0201）或调用者非在队成员（A0301）
     */
    List<AttendanceMeVO> getMyHistory();
}
