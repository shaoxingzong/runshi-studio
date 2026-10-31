package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.config.AttendanceProperties;
import com.bhu.runshistudioweb.constant.AttendanceConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.ClientIpManager;
import com.bhu.runshistudioweb.mapper.StudioAttendanceMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMeVO;
import com.bhu.runshistudioweb.service.AttendanceService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 考勤签到服务实现
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl extends ServiceImpl<StudioAttendanceMapper, StudioAttendance>
        implements AttendanceService {

    /** in_lan 列的取值（0-外网） */
    private static final int OUT_LAN = 0;

    /** 拿不到客户端 IP 时的占位值 */
    private static final String IP_UNKNOWN = "unknown";

    /** 本人历史的返回上限 */
    private static final long HISTORY_LIMIT = 30L;

    /** 看板排序：已打卡的在前（按当天首次签到时刻正序），未打卡的在后（按姓名稳定排序） */
    private static final Comparator<AttendanceBoardVO> BOARD_ORDER =
            Comparator.<AttendanceBoardVO, Integer>comparing(v -> Boolean.TRUE.equals(v.getCheckedIn()) ? 0 : 1)
                    .thenComparing(v -> v.getCheckInAt() == null ? LocalDateTime.MAX : v.getCheckInAt())
                    .thenComparing(AttendanceBoardVO::getUserName);

    private final StudioMemberService studioMemberService;

    /** 直接注入 Mapper 而不是走 Service：这里要的是「按条件取实体列表」，而 StudioMemberService 对外只提供 VO 视图（成员档案已不对 C 端展示）。 */
    private final StudioMemberMapper studioMemberMapper;

    /** 客户端 IP 解析与内外网判定 */
    private final ClientIpManager clientIpManager;

    /** 考勤配置（防抖间隔、局域网网段、可信代理） */
    private final AttendanceProperties attendanceProperties;

    // 对外方法

    /** 签到：写入一条考勤记录（时间 + 客户端 IP） */
    @Override
    public AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        assertIsActiveMember(userId, "仅工作室成员可以签到");

        LocalDateTime now = LocalDateTime.now(AttendanceConstant.ZONE_ID);
        LocalDate today = now.toLocalDate();

        // 防抖放在写库之前：被拦下的请求不该产生任何副作用
        assertNotTooSoon(userId, now);

        String clientIp = clientIpManager.resolveClientIp(remoteAddr, xRealIp, xForwardedFor);

        StudioAttendance record = new StudioAttendance();
        record.setUserId(userId);
        record.setCheckInAt(now);
        // attendance_date 由同一份 now 派生，保证与 check_in_at 落在同一天（两列同源）
        record.setAttendanceDate(today);
        record.setInLan(OUT_LAN);
        // ip 列是 NOT NULL：解析失败时写占位串而不是空串，让它事后能被一眼认出来
        record.setIp(clientIp == null || clientIp.isBlank() ? IP_UNKNOWN : clientIp);
        record.setUserAgent(truncateUserAgent(userAgent));

        boolean saved = this.save(record);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "签到失败，数据库异常");

        log.info("成员签到成功 | userId={}", userId);

        AttendanceCheckInVO vo = new AttendanceCheckInVO();
        vo.setUserId(userId);
        vo.setCheckInAt(now);
        // 单查一次本月次数：签到接口一次只处理一个人，这里不存在 N+1 的风险
        vo.setMonthCount(countThisMonth(userId, today));
        return vo;
    }

    /** 今日出勤看板：列出全部在队成员的今日出勤情况 */
    @Override
    public List<AttendanceBoardVO> getTodayBoard() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long currentUserId = StpUtil.getLoginIdAsLong();
        // 看板展示的是全员的出勤，属于成员内部信息：非成员连「看」的入口都不给
        assertIsActiveMember(currentUserId, "仅工作室成员可查看出勤情况");

        LocalDate today = LocalDate.now(AttendanceConstant.ZONE_ID);

        // 在队且已绑定登录账号的成员（未绑定的档案没有签到主体，不进看板）
        List<StudioMember> members = listActiveMembersWithAccount();

        Map<Long, LocalDateTime> firstCheckInByUser = new HashMap<>();
        Map<Long, LocalDateTime> lastCheckInByUser = new HashMap<>();
        QueryWrapper<StudioAttendance> todayQuery = new QueryWrapper<>();
        todayQuery.select("user_id", "MIN(check_in_at) AS first_check_in",
                        "MAX(check_in_at) AS last_check_in")
                .eq("attendance_date", today)
                .groupBy("user_id");
        for (Map<String, Object> row : this.listMaps(todayQuery)) {
            Long uid = toLong(row.get("user_id"));
            if (uid == null) {
                continue;
            }
            LocalDateTime firstAt = toLocalDateTime(row.get("first_check_in"));
            if (firstAt != null) {
                firstCheckInByUser.put(uid, firstAt);
            }
            LocalDateTime lastAt = toLocalDateTime(row.get("last_check_in"));
            if (lastAt != null) {
                lastCheckInByUser.put(uid, lastAt);
            }
        }

        // 防抖间隔：null 或 <= 0 表示不限制（与 assertNotTooSoon 同一口径）
        Integer minIntervalSeconds = attendanceProperties.getMinIntervalSeconds();
        boolean cooldownEnabled = minIntervalSeconds != null && minIntervalSeconds > 0;

        // 本月次数：一次 group by 拿全体成员，避免逐个 count（N+1）
        Map<Long, Integer> monthCountByUser = countThisMonthGroupByUser(today);

        // 内存合并：三次查询结果都是 Map 结构，成员列表遍历一次即可拼完
        List<AttendanceBoardVO> board = new ArrayList<>(members.size());
        for (StudioMember member : members) {
            Long memberUserId = member.getUserId();
            if (memberUserId == null) {
                continue;
            }
            LocalDateTime firstAt = firstCheckInByUser.get(memberUserId);
            AttendanceBoardVO vo = new AttendanceBoardVO();
            vo.setUserId(memberUserId);
            vo.setUserName(member.getName());
            vo.setCheckInAt(firstAt);
            vo.setCheckedIn(firstAt != null);
            LocalDateTime lastAt = lastCheckInByUser.get(memberUserId);
            if (cooldownEnabled && lastAt != null) {
                vo.setNextCheckInAt(lastAt.plusSeconds(minIntervalSeconds));
            }
            // 没有记录时补 0：GROUP BY 不会返回「0 条」的行
            vo.setMonthCount(monthCountByUser.getOrDefault(memberUserId, 0));
            board.add(vo);
        }
        board.sort(BOARD_ORDER);
        return board;
    }

    /** 本人签到历史（最近若干条，时间倒序） */
    @Override
    public List<AttendanceMeVO> getMyHistory() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        assertIsActiveMember(userId, "仅工作室成员可查看签到历史");

        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        // eq userId 不能省：少了它就是把全员的签到明细返回给当前用户
        wrapper.eq(StudioAttendance::getUserId, userId)
                .orderByDesc(StudioAttendance::getCheckInAt)
                .orderByDesc(StudioAttendance::getId);

        // 用 Page 而不是 list 后截取：list 会把全部历史读进内存再丢掉，数据量上来后是浪费
        Page<StudioAttendance> page = this.page(new Page<>(1, HISTORY_LIMIT), wrapper);

        List<AttendanceMeVO> history = new ArrayList<>(page.getRecords().size());
        for (StudioAttendance record : page.getRecords()) {
            AttendanceMeVO vo = new AttendanceMeVO();
            vo.setUserId(record.getUserId());
            vo.setAttendanceDate(record.getAttendanceDate());
            vo.setCheckInAt(record.getCheckInAt());
            history.add(vo);
        }
        return history;
    }

    // 私有工具

    /** 校验调用者是在队的工作室成员 */
    private void assertIsActiveMember(long userId, String noAuthText) {
        StudioMember member = studioMemberService.getActiveMemberByUserId(userId);
        ThrowUtils.throwIf(member == null, ErrorCode.NO_AUTH_ERROR, noAuthText);
    }

    private void assertNotTooSoon(long userId, LocalDateTime now) {
        Integer minInterval = attendanceProperties.getMinIntervalSeconds();
        if (minInterval == null || minInterval <= 0) {
            // 阈值没配或配成 0：语义是「关闭防抖」，直接放行，不做任何查询
            return;
        }
        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioAttendance::getUserId, userId)
                .ge(StudioAttendance::getCheckInAt, now.minusSeconds(minInterval));
        long recent = this.count(wrapper);
        ThrowUtils.throwIf(recent > 0, ErrorCode.PARAMS_ERROR, "签到过于频繁，请稍后再试");
    }

    /** 取在队且已绑定登录账号的成员列表 */
    private List<StudioMember> listActiveMembersWithAccount() {
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(StudioMember::getUserId)
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());
        return studioMemberMapper.selectList(wrapper);
    }

    /** 单个成员本自然月的签到次数 */
    private int countThisMonth(long userId, LocalDate anyDay) {
        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioAttendance::getUserId, userId)
                .ge(StudioAttendance::getAttendanceDate, anyDay.withDayOfMonth(1))
                .le(StudioAttendance::getAttendanceDate, anyDay.withDayOfMonth(anyDay.lengthOfMonth()));
        return Math.toIntExact(this.count(wrapper));
    }

    /** 一次性统计全体成员本月签到次数 */
    private Map<Long, Integer> countThisMonthGroupByUser(LocalDate anyDay) {
        QueryWrapper<StudioAttendance> wrapper = new QueryWrapper<>();
        wrapper.select("user_id", "COUNT(*) AS cnt")
                .ge("attendance_date", anyDay.withDayOfMonth(1))
                .le("attendance_date", anyDay.withDayOfMonth(anyDay.lengthOfMonth()))
                .groupBy("user_id");

        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : this.listMaps(wrapper)) {
            Long uid = toLong(row.get("user_id"));
            Long cnt = toLong(row.get("cnt"));
            if (uid != null) {
                result.put(uid, cnt == null ? 0 : Math.toIntExact(cnt));
            }
        }
        return result;
    }

    /** UA 截断到列宽 */
    private String truncateUserAgent(String userAgent) {
        if (userAgent == null || userAgent.length() <= AttendanceConstant.USER_AGENT_MAX_LENGTH) {
            return userAgent;
        }
        return userAgent.substring(0, AttendanceConstant.USER_AGENT_MAX_LENGTH);
    }

    /** 聚合查询的数值结果转 Long */
    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 聚合查询的时间结果转 LocalDateTime */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

}
