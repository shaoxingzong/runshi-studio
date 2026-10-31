import { http } from './request';
import type { AttendanceBoardItem, AttendanceCheckIn, AttendanceRecord } from './types';

/** 考勤接口（仅工作室成员，与发帖共用同一套身份判定） */
export const attendanceApi = {
    checkIn: () => http.post<AttendanceCheckIn>('/attendance/check-in', {}),

    todayBoard: () => http.get<AttendanceBoardItem[]>('/attendance/board/today'),

    // 我的签到历史（倒序）
    myHistory: () => http.get<AttendanceRecord[]>('/attendance/me'),
};
