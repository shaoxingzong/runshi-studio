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
 * 准入规则（本模块最重要的产品决策）：
 * 只有在队的工作室成员能签到，也只有他们能看出勤看板；普通注册用户两者都不能。
 * 「是否算成员」的判定权威是 studio_member 表（存在 user_id = 当前登录用户
 * 且 member_status = 0 的记录），不是 sys_user.user_role。
 * 因为「成员」是管理员后台标记出来的身份（往成员表里录一条档案），
 * 而角色只是一个权限标签；两者可能不同步，必须认表。
 *
 * 非成员调用本接口任何方法都会抛 A0301 无权限，
 * 连「看自己」的入口也不给——他没有签到数据，给了只会是空列表。
 *
 * 约定：参数不合法 / 业务不允许时一律抛 BusinessException，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应。
 *
 * @author shaoshing
 */
public interface AttendanceService extends IService<StudioAttendance> {

    /**
     * 当前登录成员签到
     *
     * 三个入参都由 Controller 从请求上下文取出后传入：
     * 本接口不认识 HTTP（与 ClientIpManager 同一条分层纪律），
     * 保持这样既能在纯单测里直接调用，也避免了 Service 层依赖请求对象。
     *
     * 执行顺序（任何一步失败都不会写库）：
     * - 校验登录态；
     * - 校验是否在队成员，不是则 A0301；
     * - 按防抖阈值检查「距上一次签到是否过短」，过短则拒绝（挡误触连点）；
     * - 解析客户端 IP 并判定内外网；
     * - 写入一条签到记录。
     *
     * @param remoteAddr TCP 对端地址（可能带端口）
     * @param xRealIp X-Real-IP 头原始值，可为 null
     * @param xForwardedFor X-Forwarded-For 头原始值，可为 null
     * @param userAgent User-Agent 头原始值，可为 null（超长会被截断）
     * @return 本次签到结论（时刻 / 是否内网 / 本月累计次数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、非成员、
     * 签到过于频繁、入库失败时抛出
     */
    AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent);

    /**
     * 今日出勤看板（仅成员可见）
     *
     * 返回全部在队成员的今日出勤情况，未打卡的成员 checkedIn=false。
     * 内部走三次聚合查询后在内存合并，不是「查成员列表后逐人 count」（那会退化成 N+1）。
     *
     * @return 今日看板列表；成员列表为空时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或调用者非成员时抛出
     */
    List<AttendanceBoardVO> getTodayBoard();

    /**
     * 本人签到历史（仅成员可见）
     *
     * 只返回调用者自己的记录，按签到时刻倒序，并限制在最近若干条
     * ——考勤表随时间持续增长，不加限制迟早会把整表历史拉进内存。
     *
     * @return 本人历史列表；无记录时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或调用者非成员时抛出
     */
    List<AttendanceMeVO> getMyHistory();
}
