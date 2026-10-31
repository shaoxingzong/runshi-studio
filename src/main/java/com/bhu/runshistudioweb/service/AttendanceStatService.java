package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMonthStatVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceRecordVO;

import java.util.List;

/**
 * 管理端考勤统计
 *
 * @author shaoshing
 */
public interface AttendanceStatService {

    /**
     * 某月全员签到汇总（按账号聚合）
     *
     * @param month 月份，格式 yyyy-MM；为空时按当前月（Asia/Shanghai）
     * @return 汇总列表；无数据时返回空列表，不返回 null
     */
    List<AttendanceMonthStatVO> listMonthStats(String month);

    /**
     * 某成员某月的原始签到记录（含 IP）
     *
     * @param userId 成员绑定的账号 ID，必须为正数
     * @param month 月份，格式 yyyy-MM；为空时按当前月
     * @return 按签到时刻倒序的记录；无记录返回空列表
     */
    List<AttendanceRecordVO> listMemberRecords(long userId, String month);
}
