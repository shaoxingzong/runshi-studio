package com.bhu.runshistudioweb.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.config.AttendanceProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.manager.ClientIpManager;
import com.bhu.runshistudioweb.mapper.StudioAttendanceMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.AttendanceMeVO;
import com.bhu.runshistudioweb.service.impl.AttendanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考勤服务验收测试（纯单测：不启 Spring、不连数据库）
 *
 * author: shaoshing
 *
 * <p>依赖全部替换成 Mock / 真实轻量实现：
 * <ul>
 *     <li>两个 Mapper 用 Mockito 替身的——本类要验证的是<b> Service 的编排逻辑</b>
 *     （准入、防抖、落库参数、三次查询的合并），而不是 SQL 能不能跑通；</li>
 *     <li>{@link ClientIpManager} 用<b>真实实例</b>：内外网判定是本次的核心业务规则，
 *     用真实实现才能真正覆盖到「192.168.1.5 算内网」这条链路；</li>
 *     <li>{@code StpUtil} 是静态调用，用 {@code mockStatic} 替换登录态。</li>
 * </ul>
 *
 * <p>最关键的两条用例是<b>准入</b>（非成员必须被拒）与<b>脱敏</b>（VO 结构上不允许出现 ip）：
 * 前者是产品明确要求的安全边界，后者一旦被破坏就是隐私泄露，所以都用断言钉死。
 */
class AttendanceServiceTest {

    private static final long USER_ID = 1001L;
    private static final long OTHER_USER_ID = 1002L;

    /** 配置的工作室网段，与 ClientIpManagerTest 的取值保持一致便于交叉理解 */
    private static final String LAN_CIDR = "192.168.1.0/24";

    private StudioAttendanceMapper attendanceMapper;
    private StudioMemberMapper memberMapper;
    private StudioMemberService memberService;
    private AttendanceServiceImpl service;

    @BeforeEach
    void setUp() {
        attendanceMapper = Mockito.mock(StudioAttendanceMapper.class);
        memberMapper = Mockito.mock(StudioMemberMapper.class);
        memberService = Mockito.mock(StudioMemberService.class);

        AttendanceProperties properties = new AttendanceProperties();
        properties.setLanCidrs(List.of(LAN_CIDR));
        properties.setTrustedProxies(List.of("127.0.0.1"));
        properties.setMinIntervalSeconds(60);

        // 四个业务依赖走构造器注入，顺序与字段声明一致（studioMemberService / studioMemberMapper /
        // clientIpManager / attendanceProperties）；baseMapper 属于父类 ServiceImpl，
        // 拿不到构造器入口，仍只能反射注入
        service = new AttendanceServiceImpl(memberService, memberMapper, new ClientIpManager(properties), properties);
        ReflectionTestUtils.setField(service, "baseMapper", attendanceMapper);
    }

    // ==================== 准入：登录态 ====================

    @Test
    @DisplayName("未登录签到：A0201 未登录，根本不会走到成员校验")
    void checkInRejectedWhenNotLoggedIn() {
        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(false);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.checkIn("192.168.1.5", null, null, "ua"));

            assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), e.getCode());
            Mockito.verify(memberService, Mockito.never()).getActiveMemberByUserId(Mockito.any());
        }
    }

    // ==================== 准入：必须是成员（产品核心规则） ====================

    @Test
    @DisplayName("普通注册用户签到：A0301 无权限（成员身份以 studio_member 表为准）")
    void checkInRejectedForNonMember() {
        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            // 关键：成员表里查不到这个人 → 不是工作室成员
            Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(null);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.checkIn("192.168.1.5", null, null, "ua"));

            assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
            Mockito.verify(attendanceMapper, Mockito.never()).insert(Mockito.any(StudioAttendance.class));
        }
    }

    @Test
    @DisplayName("普通注册用户看出勤看板：同样 A0301，连自己都看不到")
    void boardRejectedForNonMember() {
        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(null);

            BusinessException e = assertThrows(BusinessException.class, () -> service.getTodayBoard());

            assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
            Mockito.verify(memberMapper, Mockito.never()).selectList(Mockito.any());
        }
    }

    // ==================== 签到 ====================

    @Test
    @DisplayName("成员签到成功：内网地址判为 inLan=true，次数取自查询")
    void memberCheckInSucceedsWithLanJudgement() {
        Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(member(USER_ID, "张三"));
        // 两次 count：① 防抖（无近期记录）② 本月累计
        Mockito.when(attendanceMapper.selectCount(Mockito.any())).thenReturn(0L, 3L);
        Mockito.when(attendanceMapper.insert(Mockito.any(StudioAttendance.class))).thenReturn(1);

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            AttendanceCheckInVO vo = service.checkIn("192.168.1.5", null, null, "Mozilla/5.0");

            assertEquals(USER_ID, vo.getUserId());
            assertTrue(vo.getInLan(), "192.168.1.5 应命中工作室网段");
            assertNotNull(vo.getCheckInAt());
            assertEquals(3, vo.getMonthCount());
        }
    }

    @Test
    @DisplayName("成员在外网签到：inLan=false（住址/手机流量不被当成在工作室）")
    void memberCheckInOutsideLan() {
        Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(member(USER_ID, "张三"));
        Mockito.when(attendanceMapper.selectCount(Mockito.any())).thenReturn(0L, 1L);
        Mockito.when(attendanceMapper.insert(Mockito.any(StudioAttendance.class))).thenReturn(1);

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            AttendanceCheckInVO vo = service.checkIn("8.8.8.8", null, null, "Mozilla/5.0");

            assertFalse(vo.getInLan());
        }
    }

    @Test
    @DisplayName("签到过于频繁：被防抖拦下且不写库（record-keeping 不受影响）")
    void repeatedCheckInIsRejected() {
        Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(member(USER_ID, "张三"));
        // 防抖查询命中「刚刚签过」
        Mockito.when(attendanceMapper.selectCount(Mockito.any())).thenReturn(1L);

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.checkIn("192.168.1.5", null, null, "ua"));

            assertEquals(ErrorCode.PARAMS_ERROR.getCode(), e.getCode());
            Mockito.verify(attendanceMapper, Mockito.never()).insert(Mockito.any(StudioAttendance.class));
        }
    }

    // ==================== 出勤看板 ====================

    @Test
    @DisplayName("看板：已打卡成员带时间与月次数，未打卡成员 checkedIn=false 且同样在列")
    void boardMixesCheckedInAndAbsentMembers() {
        Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(member(USER_ID, "张三"));
        Mockito.when(memberMapper.selectList(Mockito.any())).thenReturn(
                List.of(member(USER_ID, "张三"), member(OTHER_USER_ID, "李四")));

        LocalDateTime firstAt = LocalDateTime.of(2026, 10, 7, 9, 30);
        // 两次 selectMaps：① 今日聚合（只有张三）② 本月分组计数
        Mockito.when(attendanceMapper.selectMaps(Mockito.any()))
                .thenReturn(
                        List.of(Map.<String, Object>of(
                                "user_id", USER_ID, "first_check_in", firstAt, "any_lan", 1)),
                        List.of(Map.<String, Object>of("user_id", USER_ID, "cnt", 5L)));

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            List<AttendanceBoardVO> board = service.getTodayBoard();

            assertEquals(2, board.size(), "未打卡成员也必须出现在看板里");

            // 已打卡的排在前面
            AttendanceBoardVO checked = board.get(0);
            assertEquals("张三", checked.getUserName());
            assertTrue(checked.getCheckedIn());
            assertEquals(firstAt, checked.getCheckInAt());
            assertEquals(5, checked.getMonthCount());
            assertTrue(checked.getInLan());

            AttendanceBoardVO absent = board.get(1);
            assertEquals("李四", absent.getUserName());
            assertFalse(absent.getCheckedIn());
            assertNull(absent.getCheckInAt());
            assertEquals(0, absent.getMonthCount());
        }
    }

    // ==================== 隐私红线 ====================

    @Test
    @DisplayName("隐私红线：考勤 VO 结构上不允许出现 ip / userAgent / userAccount")
    void vosNeverDeclareSensitiveFields() {
        List<Class<?>> voTypes = List.of(
                AttendanceBoardVO.class, AttendanceMeVO.class, AttendanceCheckInVO.class);
        List<String> forbidden = List.of("ip", "userAgent", "userAccount");

        for (Class<?> type : voTypes) {
            for (String fieldName : forbidden) {
                assertThrows(NoSuchFieldException.class, () -> type.getDeclaredField(fieldName),
                        type.getSimpleName() + " 出现了敏感字段 " + fieldName
                                + " —— 一旦存在， copyProperties 就会把它带给其他成员");
            }
        }
    }

    // ==================== 本人历史 ====================

    @Test
    @DisplayName("本人历史：返回自己的记录，且 VO 不带 IP / UA")
    void myHistoryReturnsOwnRecords() {
        Mockito.when(memberService.getActiveMemberByUserId(USER_ID)).thenReturn(member(USER_ID, "张三"));

        StudioAttendance record = new StudioAttendance();
        record.setUserId(USER_ID);
        record.setAttendanceDate(LocalDate.of(2026, 10, 7));
        record.setCheckInAt(LocalDateTime.of(2026, 10, 7, 9, 30));
        record.setInLan(1);
        // 实体里带着敏感的审计字段，下面验证它们不会流进 VO
        record.setIp("192.168.1.5");
        record.setUserAgent("Mozilla/5.0");

        Mockito.doAnswer(invocation -> {
            Page<StudioAttendance> page = invocation.getArgument(0);
            page.setRecords(List.of(record));
            page.setTotal(1);
            return page;
        }).when(attendanceMapper).selectPage(Mockito.any(), Mockito.any());

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            List<AttendanceMeVO> history = service.getMyHistory();

            assertEquals(1, history.size());
            AttendanceMeVO vo = history.get(0);
            assertEquals(USER_ID, vo.getUserId());
            assertEquals(LocalDate.of(2026, 10, 7), vo.getAttendanceDate());
            assertTrue(vo.getInLan());
        }
    }

    // ==================== 辅助 ====================

    /**
     * 构造一个已绑定账号的成员档案
     */
    private static StudioMember member(Long userId, String name) {
        StudioMember member = new StudioMember();
        member.setUserId(userId);
        member.setName(name);
        return member;
    }
}
