package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import com.bhu.runshistudioweb.model.vo.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.AttendanceMeVO;

import java.util.List;

/**
 * 考勤签到服务接口
 *
 * author: shaoshing
 *
 * <p><b>准入规则（本模块最重要的产品决策）</b>：
 * <blockquote>
 * 只有<b>在队的工作室成员</b>能签到，也只有他们能看出勤看板；普通注册用户两者都不能。
 * </blockquote>
 * 「是否算成员」的判定权威是 {@code studio_member} 表（存在 {@code user_id = 当前登录用户}
 * 且 {@code member_status = 0} 的记录），<b>不是</b> {@code sys_user.user_role}。
 * 因为「成员」是管理员后台标记出来的身份（往成员表里录一条档案），
 * 而角色只是一个权限标签；两者可能不同步，必须认表。
 *
 * <p>非成员调用本接口任何方法都会抛 {@code A0301 无权限}，
 * 连「看自己」的入口也不给——他没有签到数据，给了只会是空列表。
 *
 * <p>约定：参数不合法 / 业务不允许时一律抛 {@code BusinessException}，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应。
 */
public interface AttendanceService extends IService<StudioAttendance> {

    /**
     * 当前登录成员签到
     *
     * <p>三个入参都由 Controller 从请求上下文取出后传入：
     * 本接口<b>不认识 HTTP</b>（与 {@code ClientIpManager} 同一条分层纪律），
     * 保持这样既能在纯单测里直接调用，也避免了 Service 层依赖请求对象。
     *
     * <p>执行顺序（任何一步失败都不会写库）：
     * <ol>
     *     <li>校验登录态；</li>
     *     <li>校验是否<b>在队成员</b>，不是则 A0301；</li>
     *     <li>按防抖阈值检查「距上一次签到是否过短」，过短则拒绝（挡误触连点）；</li>
     *     <li>解析客户端 IP 并判定内外网；</li>
     *     <li>写入一条签到记录。</li>
     * </ol>
     *
     * @param remoteAddr     TCP 对端地址（可能带端口）
     * @param xRealIp        {@code X-Real-IP} 头原始值，可为 null
     * @param xForwardedFor  {@code X-Forwarded-For} 头原始值，可为 null
     * @param userAgent      {@code User-Agent} 头原始值，可为 null（超长会被截断）
     * @return 本次签到结论（时刻 / 是否内网 / 本月累计次数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、非成员、
     *         签到过于频繁、入库失败时抛出
     */
    AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent);

    /**
     * 今日出勤看板（仅成员可见）
     *
     * <p>返回<b>全部在队成员</b>的今日出勤情况，未打卡的成员 {@code checkedIn=false}。
     * 内部走三次聚合查询后在内存合并，不是「查成员列表后逐人 count」（那会退化成 N+1）。
     *
     * @return 今日看板列表；成员列表为空时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或调用者非成员时抛出
     */
    List<AttendanceBoardVO> getTodayBoard();

    /**
     * 本人签到历史（仅成员可见）
     *
     * <p>只返回调用者自己的记录，按签到时刻倒序，并限制在最近若干条
     * ——考勤表随时间持续增长，不加限制迟早会把整表历史拉进内存。
     *
     * @return 本人历史列表；无记录时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或调用者非成员时抛出
     */
    List<AttendanceMeVO> getMyHistory();
}
