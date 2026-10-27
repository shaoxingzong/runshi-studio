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
 * 准入规则：只有在队的工作室成员能签到、能看出勤看板。判定权威是 studio_member 表
 * （存在 user_id 命中当前账号且 member_status = 0 的记录），不是 sys_user.user_role——
 * 角色只是权限标签，档案才是业务事实，两者可能不同步。
 *
 * 非成员调用本接口任何方法都抛 A0301，连「看自己」的入口也不给：
 * 空列表看起来像功能坏了，比一句明确的「你不是成员」更糟。
 *
 * 继承 {@code IService<StudioAttendance>} 的 CRUD 方法会绕过准入校验，
 * 业务操作一律走下面三个显式声明的方法。
 *
 * @author shaoshing
 */
public interface AttendanceService extends IService<StudioAttendance> {

    /**
     * 当前登录成员签到
     *
     * 三个网络入参由 Controller 从请求上下文取出后传入，本层刻意不认识 HTTP。
     * 请求头只在 remoteAddr 是可信代理时才采信——无条件采信等于让任何人自称「在工作室」。
     *
     * 坑：本方法不幂等，靠防抖时间阈值挡误触连点，而不是给表加「用户 + 日期」唯一索引。
     * 唯一索引会让「上午来一次、下午再来一次」的第二次签到直接失败，
     * 而考勤要记录的是「来过几次」，不是「今天来没来」。
     *
     * @param remoteAddr TCP 对端地址（可能带端口）；null 时按「外网」处理并落占位串，不抛异常
     * @param xRealIp / xForwardedFor 请求头原始值，可为 null；仅当 remoteAddr 可信时才采信
     * @param userAgent 可为 null，超长会被截断到列宽 512
     * @return 本次签到结论（时刻 / 是否内网 / 本月累计次数），永不为 null
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、签到过于频繁（A0401）、
     *         入库失败（B0001）
     */
    AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent);

    /**
     * 今日出勤看板（仅成员可见）
     *
     * 必须把「全部在队成员」与「今天的签到记录」合并：只查签到记录会让没来的成员直接消失，
     * 用户看到「今天只来了 3 个人」却无法判断剩下的人是真没来还是不在名单里。
     * 内部走三次聚合后在内存合并，次数与成员规模无关，不会退化成逐人 count 的 N+1。
     *
     * 坑：inLan 的口径是「当天只要有一次内网记录就算内网」，不是「最后一次在哪」；
     * 未打卡成员的 checkInAt 为 null，比较器不处理 null 会直接 NPE。
     *
     * @return 今日看板列表，每人一条；未打卡成员同样在列表里（checkedIn=false、checkInAt=null），
     *         因此列表长度等于「在队且已绑账号的成员数」，不等于今日签到人数
     * @throws BusinessException 未登录（A0201）或调用者非在队成员（A0301）
     */
    List<AttendanceBoardVO> getTodayBoard();

    /**
     * 本人签到历史（仅成员可见）
     *
     * 只返回调用者自己的记录：「看别人签了几次」属于成员间的横向比较，产品上没有这个诉求。
     * 用分页限制条数而不是「查全部再截取」，否则整表历史都会被读进内存。
     *
     * 坑：排序必须带次级键（记录 ID）。check_in_at 是秒级精度，同秒两条记录时间相同，
     * 只按它排序会让先后在多次查询间漂移，表现为翻页重复或漏条。
     *
     * @return 本人历史列表，按签到时刻倒序、最多最近若干条；无记录时返回空列表，不返回 null
     * @throws BusinessException 未登录（A0201）或调用者非在队成员（A0301）
     */
    List<AttendanceMeVO> getMyHistory();
}
