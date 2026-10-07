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
import com.bhu.runshistudioweb.model.vo.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.AttendanceMeVO;
import com.bhu.runshistudioweb.service.AttendanceService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
 * author: shaoshing
 *
 * <p><b>准入以 studio_member 表为准</b>（这是产品明确要求的规则）：
 * 「是不是工作室成员」由管理员往成员表里录档案来决定，不是账号角色说了算。
 * 因此签到与查看板的第一步都是 {@code getActiveMemberByUserId}，
 * 查不到就抛 {@code A0301 无权限}——普通注册用户既不能签到，也看不到任何人的出勤。
 *
 * <p><b>为什么本类不认识 HTTP</b>：IP 与 UA 由 Controller 从请求里取好后传进来，
 * 与 {@link ClientIpManager} 是同一条纪律。好处是「网络判定」这件事
 * 可以在纯单测里直接验证，不必先 Mock 出一个请求对象。
 *
 * <p><b>看板的性能纪律</b>：三次聚合查询后在内存合并，
 * 绝不写成 {@code for (成员 m : 列表) { countByUser(m.id) }}——那是标准 N+1。
 * 成员规模目前只有几十人，将来若涨到几百人，这三次查询依然成立，而 N+1 会线性恶化。
 */
@Slf4j
@Service
public class AttendanceServiceImpl extends ServiceImpl<StudioAttendanceMapper, StudioAttendance>
        implements AttendanceService {

    /** in_lan 列的两个取值，与 DDL 注释一致（0-外网，1-内网） */
    private static final int IN_LAN = 1;
    private static final int OUT_LAN = 0;

    /**
     * 拿不到客户端 IP 时的占位值
     *
     * <p>{@code ip} 列是 NOT NULL，而 {@code resolveClientIp} 在极端情况下（非常规传输层）
     * 会返回 null。此时让<b>签到失败</b>是不划算的——考勤记录本身比 IP 的准确性重要，
     * 且这种情况极少见。写占位串而不是空字符串，是为了让它事后能被一眼认出来。
     */
    private static final String IP_UNKNOWN = "unknown";

    /**
     * 本人历史的返回上限
     *
     * <p>考勤表随时间单调增长，「我的历史」不设限迟早会把整表拖进内存。
     * 取最近若干条足够支撑「最近出勤情况」这个产品诉求。
     */
    private static final long HISTORY_LIMIT = 30L;

    /**
     * 看板排序：已打卡的在前（按当天首次签到时刻正序），未打卡的在后（按姓名稳定排序）
     *
     * <p>未打卡的用 {@code LocalDateTime.MAX} 占位参与比较，避免比较器遇 null 抛 NPE。
     */
    private static final Comparator<AttendanceBoardVO> BOARD_ORDER =
            Comparator.<AttendanceBoardVO, Integer>comparing(v -> Boolean.TRUE.equals(v.getCheckedIn()) ? 0 : 1)
                    .thenComparing(v -> v.getCheckInAt() == null ? LocalDateTime.MAX : v.getCheckInAt())
                    .thenComparing(AttendanceBoardVO::getUserName);

    @Resource
    private StudioMemberService studioMemberService;

    /**
     * 直接注入 Mapper 而不是走 Service：这里要的是「按条件取实体列表」，
     * 而 {@code StudioMemberService} 对外只提供 VO 视图（成员档案已不对 C 端展示）。
     * 跨模块注入 Mapper 在项目里已有先例（StudioMemberServiceImpl 注入 SysUserMapper）
     */
    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private ClientIpManager clientIpManager;

    @Resource
    private AttendanceProperties attendanceProperties;

    // ==================== 对外方法 ====================

    /**
     * 签到（接口契约见 AttendanceService）
     *
     * <p><b>本表唯一不需要 catch DuplicateKeyException 的写操作</b>：
     * studio_attendance 除了主键<b>没有任何唯一索引</b>——同一天重复签到在设计上是允许的
     * （靠 {@code minIntervalSeconds} 防连点，而不是靠索引禁止）。
     * 因此不存在「并发签到撞唯一键」的竞态，没必要像注册那样兜；
     * 若将来给这张表加了业务唯一索引（如 uk_user_date），这里就必须补上 catch。
     */
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
        boolean inLan = clientIpManager.isInLan(clientIp);

        StudioAttendance record = new StudioAttendance();
        record.setUserId(userId);
        record.setCheckInAt(now);
        // attendance_date 由同一份 now 派生，保证与 check_in_at 落在同一天（两列同源）
        record.setAttendanceDate(today);
        record.setInLan(inLan ? IN_LAN : OUT_LAN);
        record.setIp(clientIp == null || clientIp.isBlank() ? IP_UNKNOWN : clientIp);
        record.setUserAgent(truncateUserAgent(userAgent));

        boolean saved = this.save(record);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "签到失败，数据库异常");

        // 日志只记 userId 与判定结果，不打印 IP / UA：这两个字段属于位置与设备信息，
        // 写进日志等于把它们暴露给所有能看日志的人（包括日志采集服务）
        log.info("成员签到成功 | userId={} | inLan={}", userId, inLan);

        AttendanceCheckInVO vo = new AttendanceCheckInVO();
        vo.setUserId(userId);
        vo.setCheckInAt(now);
        vo.setInLan(inLan);
        vo.setMonthCount(countThisMonth(userId, today));
        return vo;
    }

    /**
     * 今日出勤看板（接口契约见 AttendanceService）
     */
    @Override
    public List<AttendanceBoardVO> getTodayBoard() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long currentUserId = StpUtil.getLoginIdAsLong();
        assertIsActiveMember(currentUserId, "仅工作室成员可查看出勤情况");

        LocalDate today = LocalDate.now(AttendanceConstant.ZONE_ID);

        // ① 在队且已绑定登录账号的成员（未绑定的档案没有签到主体，不进看板）
        List<StudioMember> members = listActiveMembersWithAccount();

        // ② 今日签到：按 user_id 聚出「当天最早一次」与「是否有内网记录」
        Map<Long, LocalDateTime> firstCheckInByUser = new HashMap<>();
        Map<Long, Boolean> anyLanByUser = new HashMap<>();
        QueryWrapper<StudioAttendance> todayQuery = new QueryWrapper<>();
        // 用字符串列名而不是 Lambda：这一句包含 MIN/MAX 聚合，Lambda 版表达不了。
        // 列名全是硬编码常量、日期走参数绑定，不存在拼接用户输入的注入面
        todayQuery.select("user_id", "MIN(check_in_at) AS first_check_in", "MAX(in_lan) AS any_lan")
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
            anyLanByUser.put(uid, toBoolean(row.get("any_lan")));
        }

        // ③ 本月次数：一次 group by 拿全体成员，避免逐个 count（N+1）
        Map<Long, Integer> monthCountByUser = countThisMonthGroupByUser(today);

        // ④ 内存合并：三次查询结果都是 Map 结构，成员列表遍历一次即可拼完
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
            vo.setMonthCount(monthCountByUser.getOrDefault(memberUserId, 0));
            // 当天多次签到里只要有一次在内网，就按内网展示：
            // 「这天在工作室出现过」比「最后一次在哪」更符合看板的语义
            vo.setInLan(Boolean.TRUE.equals(anyLanByUser.get(memberUserId)));
            board.add(vo);
        }
        board.sort(BOARD_ORDER);
        return board;
    }

    /**
     * 本人签到历史（接口契约见 AttendanceService）
     */
    @Override
    public List<AttendanceMeVO> getMyHistory() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        assertIsActiveMember(userId, "仅工作室成员可查看签到历史");

        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioAttendance::getUserId, userId)
                .orderByDesc(StudioAttendance::getCheckInAt)
                // 次级排序键不能省：check_in_at 是秒级精度，同一秒内两条记录的时间完全相同，
                // 只按它排序会让同一秒的记录次序在多次查询间漂移（页数翻动时出现重复/丢失）。
                // 雪花 ID 单调递增，正好作为稳定的次级键
                .orderByDesc(StudioAttendance::getId);

        // 用 Page 而不是 list 后截取：list 会把全部历史读进内存再丢掉，数据量上来后是浪费
        Page<StudioAttendance> page = this.page(new Page<>(1, HISTORY_LIMIT), wrapper);

        List<AttendanceMeVO> history = new ArrayList<>(page.getRecords().size());
        for (StudioAttendance record : page.getRecords()) {
            AttendanceMeVO vo = new AttendanceMeVO();
            vo.setUserId(record.getUserId());
            vo.setAttendanceDate(record.getAttendanceDate());
            vo.setCheckInAt(record.getCheckInAt());
            vo.setInLan(IN_LAN == (record.getInLan() == null ? OUT_LAN : record.getInLan()));
            history.add(vo);
        }
        return history;
    }

    // ==================== 私有工具 ====================

    /**
     * 校验调用者是在队的工作室成员
     *
     * @param userId     当前登录账号 ID
     * @param noAuthText 非成员时的提示文案（签到与查看用不同措辞，便于用户理解）
     */
    private void assertIsActiveMember(long userId, String noAuthText) {
        StudioMember member = studioMemberService.getActiveMemberByUserId(userId);
        ThrowUtils.throwIf(member == null, ErrorCode.NO_AUTH_ERROR, noAuthText);
    }

    /**
     * 防抖：距上一次签到太近则拒绝
     *
     * <p>它挡的是误触与连点（手机点两下、刷新后重提），<b>不是</b>恶意刷。
     * 恶意刷由「必须有成员身份」这一层天然限制：刷出来的每条记录都挂在真实账号上、可审计。
     *
     * <p>并发双击可能穿透本守卫（两个请求同时查到「很久没签过」），这是刻意接受的：
     * 为它加分布式锁的代价（Redis 依赖 + 锁失败时的行为定义）远大于收益——
     * 多出来的只是一条重复记录，对「今天是否到场」的结论毫无影响。
     */
    private void assertNotTooSoon(long userId, LocalDateTime now) {
        Integer minInterval = attendanceProperties.getMinIntervalSeconds();
        if (minInterval == null || minInterval <= 0) {
            return;
        }
        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioAttendance::getUserId, userId)
                .ge(StudioAttendance::getCheckInAt, now.minusSeconds(minInterval));
        long recent = this.count(wrapper);
        ThrowUtils.throwIf(recent > 0, ErrorCode.PARAMS_ERROR, "签到过于频繁，请稍后再试");
    }

    /**
     * 取在队且已绑定登录账号的成员列表
     *
     * @return 成员档案列表（含 userId 与 name）
     */
    private List<StudioMember> listActiveMembersWithAccount() {
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        // user_id 不为 NULL：只有绑定了账号的成员才有签到的主体——
        // 未绑定的档案往往是「已毕业但保留在名录里」或「尚未开通账号」
        wrapper.isNotNull(StudioMember::getUserId)
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());
        return studioMemberMapper.selectList(wrapper);
    }

    /**
     * 单个成员本自然月的签到次数
     *
     * @param userId  成员绑定的登录账号 ID
     * @param anyDay  月内任意一天（用于推出该月首尾）
     * @return 次数
     */
    private int countThisMonth(long userId, LocalDate anyDay) {
        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioAttendance::getUserId, userId)
                .ge(StudioAttendance::getAttendanceDate, anyDay.withDayOfMonth(1))
                .le(StudioAttendance::getAttendanceDate, anyDay.withDayOfMonth(anyDay.lengthOfMonth()));
        // count 返回 long，成员一个月签到次数不可能溢出 int；用 toIntExact 是为了
        // 万一出现了脏数据（同一条被重复计），让它直接抛异常而不是静默截断
        return Math.toIntExact(this.count(wrapper));
    }

    /**
     * 一次性统计全体成员本月签到次数
     *
     * <p>与 {@link #countThisMonth} 是一个逻辑的两种形态：单人版用于签到后回填结果，
     * 这个批量版用于看板——后者必须一次查完，否则每个成员各查一次就是 N+1。
     *
     * @param anyDay 月内任意一天
     * @return userId → 次数
     */
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

    /**
     * UA 截断到列宽
     *
     * <p>UA 是客户端完全可控的字符串，不截断就是一条稳定的报错来源
     * （脚本传几 MB 的 UA → 入库失败 → 签到失败）。
     *
     * @param userAgent 原始 UA，可为 null
     * @return 截断后的 UA；入参为 null 时返回 null
     */
    private String truncateUserAgent(String userAgent) {
        if (userAgent == null || userAgent.length() <= AttendanceConstant.USER_AGENT_MAX_LENGTH) {
            return userAgent;
        }
        return userAgent.substring(0, AttendanceConstant.USER_AGENT_MAX_LENGTH);
    }

    /**
     * 聚合查询的数值结果转 Long
     *
     * <p>走 {@code Number} 而不是只认 {@code Long}：不同驱动/聚合场景下
     * COUNT 可能返回 {@code BigInteger}、SUM 可能返回 {@code BigDecimal}，
     * 只判断 Long 会在换数据源后突然变成全部 null（且不报错）。
     */
    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 聚合查询的时间结果转 LocalDateTime
     *
     * <p>MIN(check_in_at) 这类结果的类型随驱动而变（{@code LocalDateTime} 或 {@code Timestamp}），
     * 两种都要接得住，否则排班表的时间会静默变成 null。
     */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    /**
     * 把 tinyint 结果转成布尔（0 → false，非 0 → true，null → false）
     */
    private static boolean toBoolean(Object value) {
        return value instanceof Number number && number.intValue() != 0;
    }
}
