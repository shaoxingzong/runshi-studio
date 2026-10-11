import { http } from './request';
import type { AttendanceBoardItem, AttendanceCheckIn, AttendanceRecord } from './types';

/**
 * 考勤接口（**仅工作室成员**，与发帖共用同一套身份判定）
 *
 * <p>三个接口都只挂 `@SaCheckLogin`，真正的成员准入由后端查 `studio_member` 表判定
 * （认表不认角色），非成员会拿到 A0301 无权限。
 *
 * <p><b>签到接口的入参为空</b>：内外网判定所需的 IP 与 UA 由服务端从请求头取，
 * 客户端传什么都不作数——这是刻意的设计，否则任何人都能伪造「我在工作室」。
 */
export const attendanceApi = {
    /**
     * 签到
     *
     * <p>后端有最小间隔限制（防误触连点），短时间内重复提交会被拒绝。
     * 页面上要把这种情况说清楚（比如「刚刚已经打过卡了」），
     * 只弹一句「请求失败」会让人以为系统坏了。
     */
    checkIn: () => http.post<AttendanceCheckIn>('/attendance/check-in', {}),

    /**
     * 今日出勤看板：全部在队成员，未打卡的 checkedIn 为 false
     *
     * <p>响应<b>不含 ip / userAgent / userAccount</b>（后端 VO 刻意去掉了），
     * 前端只呈现「是否内网」的布尔结论，不展示任何位置信息。
     */
    todayBoard: () => http.get<AttendanceBoardItem[]>('/attendance/board/today'),

    /** 我的签到历史（倒序） */
    myHistory: () => http.get<AttendanceRecord[]>('/attendance/me'),
};
