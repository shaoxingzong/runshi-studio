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
 * 准入以 studio_member 表为准：
 * 「是不是工作室成员」由管理员往成员表里录档案来决定，不是账号角色说了算。
 * 因此签到、查看板、看历史的第一步都是查「按账号取在队成员」，
 * 查不到就抛 A0301 无权限——普通注册用户既不能签到，也看不到任何人的出勤。
 *
 * 为什么本类不认识 HTTP：IP 与 UA 由 Controller 从请求里取好后传进来，
 * 与 ClientIpManager 是同一条纪律。好处是「网络判定」这件事
 * 可以在纯单测里直接验证，不必先 Mock 出一个请求对象；
 * 代价是「必须在请求线程里取好再传」这条约束落在了 Controller 身上。
 *
 * 看板的性能纪律：三次聚合查询后在内存合并，
 * 绝不写成 for (成员 m : 列表) { countByUser(m.id) }——那是标准 N+1
 * （查 1 次列表，再为每行各查 1 次）。成员规模目前只有几十人，
 * 将来若涨到几百人，这三次查询依然成立，而 N+1 会随人数线性恶化。
 *
 * 时区纪律：所有「今天」「本月」都取 AttendanceConstant.ZONE_ID，
 * 不用 LocalDate.now() 的默认时区——容器默认多半是 UTC，
 * 用默认时区会让签到时间整体偏 8 小时，而且接口不报任何错。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl extends ServiceImpl<StudioAttendanceMapper, StudioAttendance>
        implements AttendanceService {

    /**
     * in_lan 列的两个取值
     *
     * 与 DDL 注释一致（0-外网，1-内网）。用 int 常量而不是布尔：
     * 列本身是 tinyint，直接与数字比较可以省掉一次装箱与转换。
     */
    private static final int IN_LAN = 1;
    private static final int OUT_LAN = 0;

    /**
     * 拿不到客户端 IP 时的占位值
     *
     * ip 列是 NOT NULL，而 resolveClientIp 在极端情况下（非常规传输层）
     * 会返回 null。此时让签到失败是不划算的——考勤记录本身比 IP 的准确性重要，
     * 且这种情况极少见。写占位串而不是空字符串，是为了让它事后能被一眼认出来：
     * 空串与「真的没有 IP」长得一样，而 "unknown" 一眼就知道是解析失败。
     */
    private static final String IP_UNKNOWN = "unknown";

    /**
     * 本人历史的返回上限
     *
     * 考勤表随时间单调增长，「我的历史」不设限迟早会把整表拖进内存。
     * 取最近若干条足够支撑「最近出勤情况」这个产品诉求；
     * 真要做「翻更早的历史」，应该改成游标分页，而不是把这个数字调大。
     */
    private static final long HISTORY_LIMIT = 30L;

    /**
     * 看板排序：已打卡的在前（按当天首次签到时刻正序），未打卡的在后（按姓名稳定排序）
     *
     * 为什么这样排：看板的第一眼应该落在「今天谁来了、几点来的」，
     * 未打卡的人是待确认项，排在后面但不隐藏。
     * 未打卡的用 LocalDateTime.MAX 占位参与比较，避免比较器遇 null 抛 NPE——
     * 这是刻意用哨兵值而不是在比较器里写 if (a == null) 分支：
     * 后者要在两处都写，漏一处就是线上空指针。
     */
    private static final Comparator<AttendanceBoardVO> BOARD_ORDER =
            Comparator.<AttendanceBoardVO, Integer>comparing(v -> Boolean.TRUE.equals(v.getCheckedIn()) ? 0 : 1)
                    .thenComparing(v -> v.getCheckInAt() == null ? LocalDateTime.MAX : v.getCheckInAt())
                    .thenComparing(AttendanceBoardVO::getUserName);

    private final StudioMemberService studioMemberService;

    /**
     * 直接注入 Mapper 而不是走 Service：这里要的是「按条件取实体列表」，
     * 而 StudioMemberService 对外只提供 VO 视图（成员档案已不对 C 端展示）。
     * 跨模块注入 Mapper 在项目里已有先例（StudioMemberServiceImpl 注入 SysUserMapper），
     * 好处是能一次查出实体而不是为两个字段去改别人的接口契约。
     */
    private final StudioMemberMapper studioMemberMapper;

    /**
     * 客户端 IP 解析与内外网判定
     *
     * 信任链的全部逻辑都在它里面：只有 TCP 对端是可信代理时才采信
     * X-Real-IP / X-Forwarded-For。本类只负责把三个原始串递进去，
     * 不参与「该不该信这个头」的判断——这条边界不能模糊，
     * 否则一旦有人在这里顺手读一个头，信任链就出现了绕过口。
     */
    private final ClientIpManager clientIpManager;

    /**
     * 考勤配置（防抖间隔、局域网网段、可信代理）
     *
     * 网段与代理列表在 ClientIpManager 里使用，本类只用防抖间隔。
     * 注入配置类而不是散写 @Value：一组相关配置集中在一处，
     * 改字段名时 IDE 能重构，也不会出现「配置改了但读的地方没改」。
     */
    private final AttendanceProperties attendanceProperties;

    // ==================== 对外方法 ====================

    /**
     * 签到：写入一条带「内网 / 外网」判定的考勤记录
     *
     * <p>为什么这样设计：签到的核心信息不是「谁来签了」，而是「他在不在工作室」，
     * 所以真正要做的事是拿到一个可信的客户端 IP 再与配置的局域网网段比对。
     * 但 IP 的可信度取决于它从哪来：TCP 对端地址由内核提供、客户端改不了；
     * 而 X-Real-IP / X-Forwarded-For 是请求头，客户端可以随便写。
     * 因此规则只有一条——只有 TCP 对端本身是可信代理时，才采信它带来的头，
     * 否则一律忽略（这段判断全部在 ClientIpManager 里，本方法只负责传参）。
     * 去掉了这道判断，任何人在家打开浏览器开发者工具加一个
     * {@code X-Real-IP: 192.168.1.5} 就能让自己「在工作室签到」，考勤的区分能力直接归零。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态并取用户 ID：不知道「谁」就没有签到的主体；</li>
     *     <li>校验在队成员身份。认 studio_member 表而不认账号角色——
     *     角色只是权限标签，成员身份是管理员维护的档案，两者可能不同步；</li>
     *     <li>取当前时间并派生出「今天」。这两个值必须同源，否则跨零点的请求
     *     会出现「签到时间落在昨天、考勤日期记成今天」这种自相矛盾的数据；</li>
     *     <li>防抖校验。放在解析 IP 与写库之前，被拦下的请求不该产生任何副作用，
     *     也不该浪费一次网段比对；</li>
     *     <li>解析可信客户端 IP 并判定内外网；</li>
     *     <li>组装记录并落库，其中 IP 为空时写占位串、UA 超长时截断；</li>
     *     <li>回填本月累计次数并返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：本表除了主键没有任何唯一索引，同一天重复签到在设计上是允许的
     * （靠 minIntervalSeconds 防连点，而不是靠索引禁止），所以这里不需要
     * catch DuplicateKeyException。若将来给这张表加了业务唯一索引（如「用户 + 日期」），
     * 就必须补上并发撞键的处理，否则双击会直接报错给用户。
     * 另外时间必须取 AttendanceConstant.ZONE_ID 而不是系统默认时区：
     * 容器默认多半是 UTC，用默认时区会让签到时间整体偏 8 小时，且接口不报任何错。
     *
     * @param remoteAddr TCP 对端地址，可能带端口（如 192.168.1.5:54321），可为 null
     * @param xRealIp X-Real-IP 头的原始值，可为 null；只有对端是可信代理时才会被采信
     * @param xForwardedFor X-Forwarded-For 头的原始值，可为 null；同样只在可信代理下采信
     * @param userAgent User-Agent 原始值，可为 null；超过列宽会被截断到 512
     * @return 本次签到结论：签到时刻、是否内网、本月累计次数（含本次）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 非在队成员（A0301）、距上次签到过近（A0401）、入库失败（B0001）时抛出
     */
    @Override
    public AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        assertIsActiveMember(userId, "仅工作室成员可以签到");

        // 时间与日期必须由同一个 now 派生：若分别取 LocalDateTime.now() 与 LocalDate.now()，
        // 跨零点的那一瞬间会出现「签到时刻在昨天、考勤日期是今天」，按天统计就会错位
        LocalDateTime now = LocalDateTime.now(AttendanceConstant.ZONE_ID);
        LocalDate today = now.toLocalDate();

        // 防抖放在写库之前：被拦下的请求不该产生任何副作用
        assertNotTooSoon(userId, now);

        // 内外网判定全部交给 ClientIpManager：本类不认识 HTTP，只把三个原始串递进去。
        // 它内部会先判断 TCP 对端是不是可信代理，再决定要不要读那两个请求头
        String clientIp = clientIpManager.resolveClientIp(remoteAddr, xRealIp, xForwardedFor);
        boolean inLan = clientIpManager.isInLan(clientIp);

        StudioAttendance record = new StudioAttendance();
        record.setUserId(userId);
        record.setCheckInAt(now);
        // attendance_date 由同一份 now 派生，保证与 check_in_at 落在同一天（两列同源）
        record.setAttendanceDate(today);
        // 用 0 / 1 而不是布尔：与 DDL 的 tinyint 列保持一致，避免依赖驱动的类型转换
        record.setInLan(inLan ? IN_LAN : OUT_LAN);
        // ip 列是 NOT NULL：解析失败时写占位串而不是空串，让它事后能被一眼认出来
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
        // 单查一次本月次数：签到接口一次只处理一个人，这里不存在 N+1 的风险
        vo.setMonthCount(countThisMonth(userId, today));
        return vo;
    }

    /**
     * 今日出勤看板：列出全部在队成员的今日出勤情况
     *
     * <p>为什么这样设计：看板要回答的是「今天谁来了、谁没来」，因此成员列表是主表，
     * 签到数据只是补充信息。实现上刻意用「三次查询 + 内存合并」而不是
     * 「遍历成员逐个查签到」：后者是标准的 N+1（先查 1 次列表，再为每行各查 1 次），
     * 几十个成员就是几十次往返，而成员数增长时它会线性恶化。
     * 现在的三次查询分别是「成员列表」「今日签到聚合」「本月次数聚合」，
     * 与成员数无关，涨到几百人也依然成立。
     * 未打卡的成员也要出现在看板上（checkedIn=false）：看板的价值一半在「谁没来」。
     *
     * <p>执行步骤（与代码里的 ①②③④ 标注一一对应）：
     * <ol>
     *     <li>取在队且已绑定账号的成员——没绑定账号的档案没有签到主体，不进看板；</li>
     *     <li>一条聚合查出今天每个用户的「最早签到时刻」与「是否有内网记录」；</li>
     *     <li>一条聚合查出全体成员的本月次数；</li>
     *     <li>遍历成员列表一次，把三份数据拼成 VO，最后按固定规则排序。</li>
     * </ol>
     *
     * <p>容易踩的坑：聚合结果里的列类型不能想当然。MIN(check_in_at) 在不同驱动下
     * 可能是 LocalDateTime 也可能是 Timestamp，COUNT(*) 可能是 Long 也可能是 BigInteger，
     * 所以必须经过 toLocalDateTime / toLong 这类转换方法，硬转会得到
     * 「本机正常、换环境 ClassCastException」的结果。另外两个聚合查询都用字符串列名，
     * 这不是偷懒——MIN / MAX 这类聚合函数无法用 Lambda 方法引用表达。
     *
     * @return 今日看板列表，已打卡的在前、未打卡的在后，组内按签到时刻与姓名排序；
     * 没有任何在队成员时返回空列表，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 调用者不是在队成员（A0301）时抛出
     */
    @Override
    public List<AttendanceBoardVO> getTodayBoard() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long currentUserId = StpUtil.getLoginIdAsLong();
        // 看板展示的是全员的出勤，属于成员内部信息：非成员连「看」的入口都不给
        assertIsActiveMember(currentUserId, "仅工作室成员可查看出勤情况");

        LocalDate today = LocalDate.now(AttendanceConstant.ZONE_ID);

        // ① 在队且已绑定登录账号的成员（未绑定的档案没有签到主体，不进看板）
        List<StudioMember> members = listActiveMembersWithAccount();

        // ② 今日签到：按 user_id 聚出「当天最早一次」与「是否有内网记录」。
        //    一次查询覆盖全部成员，绝不写成「遍历成员逐个 count」——那是 N+1
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
                // user_id 为 NULL 的行说明数据已经脏了（该列在业务上必填），
                // 跳过而不是塞进 Map：放进去只会得到一个永远匹配不上的条目
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
                // 理论上不会进来（查询已过滤 user_id 为空），
                // 但这里再判一次，避免以后有人改了 listActiveMembersWithAccount 的条件就 NPE
                continue;
            }
            LocalDateTime firstAt = firstCheckInByUser.get(memberUserId);
            AttendanceBoardVO vo = new AttendanceBoardVO();
            vo.setUserId(memberUserId);
            vo.setUserName(member.getName());
            vo.setCheckInAt(firstAt);
            // checkedIn 由「有没有最早签到时刻」推出来，而不是再查一次数据库：
            // 一个字段能推导出来时就不要再取一次，否则两处数据可能对不上
            vo.setCheckedIn(firstAt != null);
            // 没有记录时补 0：GROUP BY 不会返回「0 条」的行
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
     * 本人签到历史（最近若干条，时间倒序）
     *
     * <p>为什么这样设计：考勤表会随时间单调增长，而「我的历史」这个产品诉求
     * 只需要支撑「最近出勤情况」，所以必须有条数上限。这里用 Page 取前 N 条，
     * 而不是 list 出来再在内存里截断——后者会把整张历史读进内存再丢掉，
     * 数据量一上来就是纯粹的浪费，而且这种写法在代码审查时很难被注意到。
     * 只查自己的记录（eq user_id）是硬性要求：少了这个条件，任何成员都能看到
     * 全员的签到明细，那是实实在在的信息泄露。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态并取用户 ID；</li>
     *     <li>校验在队成员身份。非成员连「看自己」的入口也不给——
     *     他本来就没有签到数据，给了只会返回空列表，反而让人以为是系统坏了；</li>
     *     <li>按「本人 + 签到时刻倒序 + 雪花 ID 倒序」分页取前 N 条；</li>
     *     <li>逐条转成 VO，其中 in_lan 的空值按外网处理。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序必须有次级键。check_in_at 是秒级精度，
     * 同一秒内的两条记录时间完全相同，只按它排序时数据库返回的先后顺序并不稳定，
     * 表现为「同一条记录在两页里都出现，或者两页都没有」。
     * 雪花 ID（由「时间戳 + 机器位 + 序列号」拼出的 19 位长整型，单调递增）
     * 正好可以当这个稳定的次级键。
     *
     * @return 本人的签到历史，按签到时刻从新到旧，最多 30 条；
     * 没有记录时返回空列表，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 调用者不是在队成员（A0301）时抛出
     */
    @Override
    public List<AttendanceMeVO> getMyHistory() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        assertIsActiveMember(userId, "仅工作室成员可查看签到历史");

        LambdaQueryWrapper<StudioAttendance> wrapper = new LambdaQueryWrapper<>();
        // eq userId 不能省：少了它就是把全员的签到明细返回给当前用户
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
            // in_lan 允许为空（历史数据、人工改库都可能留下空值），空值按外网处理：
            // 判断方向刻意选「不确定时偏保守」，把未知地址当内网等于给所有人发通行证
            vo.setInLan(IN_LAN == (record.getInLan() == null ? OUT_LAN : record.getInLan()));
            history.add(vo);
        }
        return history;
    }

    // ==================== 私有工具 ====================

    /**
     * 校验调用者是在队的工作室成员
     *
     * <p>为什么这样设计：三个对外方法都要做同一件事，而准入规则将来一定会变
     * （例如允许「已毕业但保留权限」的成员查看历史）。把它收在一个方法里，
     * 规则变化时只改一处，不会出现「签到放行了、看板忘了改」这种半新半旧的状态。
     * 判定依据是 studio_member 表里「user_id 等于当前账号且在队」的记录，
     * 而不是 sys_user.user_role——角色是权限标签，成员身份是管理员维护的档案，
     * 两者可能不同步（有人被移出名录但角色没改），按角色放行会绕开真正的准入规则。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>调成员服务查「按账号查在队成员」，查不到返回 null；</li>
     *     <li>null 就抛 A0301 无权限，提示文案由调用方传入。</li>
     * </ol>
     *
     * <p>容易踩的坑：提示文案做成参数而不是写死，是因为三个入口的用户预期不同——
     * 签到被拒时用户想知道「为什么我不能签到」，查看看板被拒时则更可能是误入，
     * 同一句提示会让其中一种场景显得莫名其妙。另外本方法只校验「是不是成员」，
     * 不校验登录态：登录态必须由调用方先校验，否则这里拿到的是一个假的 userId。
     *
     * @param userId 当前登录账号 ID，调用方必须已确认登录；传 0 或负数会查不到成员并抛 A0301
     * @param noAuthText 非成员时的提示文案，不能为 null（为空串时用户会看到一个没有原因的报错）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 调用者不是在队成员时抛出（A0301）
     */
    private void assertIsActiveMember(long userId, String noAuthText) {
        StudioMember member = studioMemberService.getActiveMemberByUserId(userId);
        ThrowUtils.throwIf(member == null, ErrorCode.NO_AUTH_ERROR, noAuthText);
    }

    /**
     * 防抖：距上一次签到太近则拒绝
     *
     * <p>为什么这样设计：它挡的是误触与连点（手机点两下、刷新后重提），不是恶意刷。
     * 恶意刷由「必须有成员身份」这一层天然限制：刷出来的每条记录都挂在真实账号上、可审计。
     * 之所以放在写库之前，是因为被拒绝的请求不该留下任何副作用——
     * 先写再检查，就得回头删记录，那是另一套复杂度。
     * 间隔阈值做成配置项（studio.attendance.min-interval-seconds）而不是常量：
     * 不同团队对「多久算连点」的容忍度不同，改配置比改代码发布快得多。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>读配置。配置为 null 或非正数时直接放行——把阈值配成 0 的语义就是「关闭防抖」，
     *     此时若继续往下查库，等于每次签到都白查一次；</li>
     *     <li>统计「该用户在 now 减去间隔之后的签到条数」；</li>
     *     <li>条数大于 0 就拒绝。</li>
     * </ol>
     *
     * <p>容易踩的坑：并发双击可能穿透本守卫（两个请求同时查到「很久没签过」），
     * 这是刻意接受的：为它加分布式锁的代价（Redis 依赖 + 锁失败时的行为定义）远大于收益——
     * 多出来的只是一条重复记录，对「今天是否到场」的结论毫无影响。
     * 另外比较用的是 ge（大于等于）而不是 gt：阈值边界上应当算「太近」，
     * 否则用户把间隔卡在正好 60 秒时能连签两次。
     *
     * @param userId 当前登录账号 ID，调用方已确认其为在队成员
     * @param now 本次签到时刻，必须与随后落库的 checkInAt 是同一个值，
     *            否则判断用的时间与实际写入的时间会差一点
     * @throws com.bhu.runshistudioweb.exception.BusinessException 距上次签到不足间隔时抛出（A0401）
     */
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

    /**
     * 取在队且已绑定登录账号的成员列表
     *
     * <p>为什么这样设计：看板要遍历成员，而「能签到的成员」有两个前提——
     * 在队、且已绑定账号。两个条件都写进 SQL，而不是查出来再在内存里过滤：
     * 后者会让分页与计数口径不一致，而且过滤逻辑散在循环里更容易漏。
     * 这里直接注入 Mapper 而不是走成员 Service：需要的是实体列表，
     * 而成员 Service 对外只提供 VO 视图（成员档案已不对 C 端展示），
     * 为了拿两个字段去改它的接口契约并不划算。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>加「user_id 不为 NULL」条件——未绑定的档案没有签到主体，
     *     往往是「已毕业但保留在名录里」或「尚未开通账号」；</li>
     *     <li>加「成员状态 = 在队」条件；</li>
     *     <li>查出实体列表返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：少一个条件都会让看板出错，但表现不同。少了「user_id 不为空」，
     * 循环里会拿到 null 用户 ID，最终是空指针或一条 userId 为 null 的记录；
     * 少了「在队」，已离队的人会永远出现在今日看板上，且永远显示「未打卡」，
     * 久而久之没人再看这个看板。
     *
     * @return 在队且已绑定账号的成员实体列表；一个都没有时返回空列表，不会返回 null
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
     * <p>为什么这样设计：签到时需要立刻告诉用户「本月第几次」，所以必须有一个单人版。
     * 与看板用的批量版（countThisMonthGroupByUser）是同一个逻辑的两种形态：
     * 单人版多一次往返但实现直观，批量版一次查完但需要内存合并。
     * 这里选单人版是因为签到接口本来就只处理一个人，用批量版反而要多走一次 Map 查找。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按「本人 + 考勤日期落在本月首日与末日之间」构造条件。
     *     用日期列而不是签到时刻列：attendance_date 就是按天归类的冗余列，
     *     对它的比较能用上索引，也不必处理「时刻落在月末最后一天 23:59:59」这类边界；</li>
     *     <li>count 后转 int。</li>
     * </ol>
     *
     * <p>容易踩的坑：用 withDayOfMonth(1) 与 withDayOfMonth(lengthOfMonth()) 推出首尾，
     * 而不是写死 30 或 31——二月的天数不同，写死会漏算月末那一天。
     * 另外 count 返回 long，这里用 toIntExact 而不是直接强转：
     * 成员一个月签到次数不可能溢出 int，万一出现脏数据被重复计数，
     * 让它直接抛异常比静默截断成一个错误的数字更好。
     *
     * @param userId 成员绑定的登录账号 ID，调用方已确认为在队成员
     * @param anyDay 月内任意一天，用于推出该月的首尾（传今天即可）
     * @return 本月签到次数；本月一次都没有时返回 0
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
     * <p>为什么这样设计：与 countThisMonth 是一个逻辑的两种形态——单人版用于签到后
     * 回填结果，这个批量版用于看板。看板必须一次查完，否则每个成员各查一次就是 N+1
     * （先查 1 次成员列表，再为每个成员各查 1 次），几十个成员就是几十次往返。
     * 分组之后的结果与成员数无关，涨到几百人也还是一条 SQL。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>构造 GROUP BY user_id 的聚合查询，只取 user_id 与 COUNT(*)；</li>
     *     <li>逐行读结果，把 user_id 与计数转成 Long 后放进 Map；</li>
     *     <li>计数转换用 toIntExact，与单人版保持同一套防御。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里没有 eq(user_id)，因为它要的是全员数据——
     * 但也正因为如此，结果里可能包含已离队成员的记录（他们的签到不该被删）。
     * 这不会造成问题：看板是按成员列表去查 Map，多余条目自然取不到。
     * 另外聚合列的别名 cnt 必须与取值时的字符串完全一致，
     * 写错不会报错，只会让所有计数都变成 0。
     *
     * @param anyDay 月内任意一天，用于推出该月的首尾
     * @return userId → 本月签到次数；本月没有任何签到时返回空 Map，不会返回 null
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
                // cnt 为 null 时补 0：聚合列理论上不会为空，但拿不到计数时
                // 补 0 比抛异常更合适——看板少显示一个数字，好过整页打不开
                result.put(uid, cnt == null ? 0 : Math.toIntExact(cnt));
            }
        }
        return result;
    }

    /**
     * UA 截断到列宽
     *
     * <p>为什么这样设计：UA 是客户端完全可控的字符串，脚本可以传几 MB 的 UA。
     * 不截断就是一条稳定的报错来源：长数据入库失败 → 签到失败 →
     * 用户看到「签到失败，数据库异常」，而真正的原因只是一个请求头太长。
     * 列宽写在常量里（AttendanceConstant.USER_AGENT_MAX_LENGTH）而不是配置项，
     * 因为它是数据库契约的一部分，运维调大就会让 MySQL 静默截断或直接报错。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>null 或未超长时原样返回。null 保持 null 而不是转成空串——
     *     「没有 UA」与「UA 是空串」在排查时含义不同；</li>
     *     <li>超长则按列宽截断，不做省略号之类的修饰：列宽是硬约束，修饰反而多占字符。</li>
     * </ol>
     *
     * <p>容易踩的坑：substring 按 char 计数，若 UA 里含 emoji 这类占用两个 char 的字符，
     * 截断点可能落在一个字符的中间，产生一个孤立的代理项（半个 emoji）。
     * 这里接受这个瑕疵：UA 只是排查线索，不参与任何业务判断，
     * 为它引入按码点截断的复杂度不值得。
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
     * <p>为什么这样设计：聚合列在 JDBC 层没有统一类型。COUNT(*) 可能返回 Long、
     * 也可能返回 BigInteger（不同驱动、不同版本），这取决于连接参数与驱动实现。
     * 因此只能按它们共同的父类 Number 来取，不能只判断 Long。
     * 只认 Long 的后果特别隐蔽：换数据源后所有计数会突然变成 null，
     * 而代码不会报错，只表现为「看板上所有数字都变成 0」。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>判断是不是 Number；是就取 longValue，不是就返回 null；</li>
     *     <li>返回 null 而不是 0，把「取不到」与「真的是 0」区分开，
     *     由调用方决定怎么兜底——有的场景（如 user_id）必须跳过，有的场景可以补 0。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把这个方法与 VO 里的 Integer 混用。VO 的计数用 Integer
     * 是为了让前端拿到数字（项目的全局配置会把 Long 序列化成字符串），
     * 而这里是内部的中间值，用 Long 才不会在转 int 之前就丢掉高位。
     *
     * @param value 聚合结果，可为 null
     * @return 对应的 Long 值；null 或非数字类型时返回 null，调用方必须判空
     */
    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 聚合查询的时间结果转 LocalDateTime
     *
     * <p>为什么这样设计：MIN(check_in_at) 这类聚合结果的 Java 类型随驱动而变，
     * 可能是 LocalDateTime（新驱动直接支持 JSR-310），也可能是 Timestamp（旧式映射）。
     * 两种都要接得住，否则排班表的时间会静默变成 null——
     * 而 null 在界面上表现为「今天没人签到」，正好与「有人签到」相反。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先按 LocalDateTime 判断，这是新版驱动最常见的结果类型；</li>
     *     <li>再按 Timestamp 判断并转换；</li>
     *     <li>两种都不是则返回 null，由调用方决定怎么处理。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要用 Timestamp.valueOf(value.toString()) 这种字符串往返的写法。
     * 它依赖 Timestamp 的 toString 格式，且会把时区信息丢掉一次再补回来，
     * 在跨时区部署时会得到偏差几小时的时间——而这类错误只在大洋彼岸的服务器上出现。
     *
     * @param value 聚合结果，可为 null
     * @return 对应的时间；null 或无法识别的类型时返回 null，调用方必须判空
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
     * 把 tinyint 结果转成布尔
     *
     * <p>为什么这样设计：MAX(in_lan) 在 SQL 里得到的是 0 / 1 这样的数字，
     * 而业务语义是「当天有没有一次内网签到」。转换收在一处，
     * 避免在调用点写 value != null &amp;&amp; ((Number) value).intValue() == 1 这种一长串判断。
     * null 按 false 处理，与 isInLan 的失败方向保持一致：不确定时按外网算。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>判断是不是 Number 且不为 0；</li>
     *     <li>其余情况（null、非数字）一律返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要用 Boolean.parseBoolean(value.toString())——
     * 它只认 "true" 这个字符串，"1" 会被解析成 false，于是所有内网签到都会显示成外网，
     * 而且不报任何错。
     *
     * @param value 聚合结果，可为 null
     * @return 非 0 返回 true，其余情况返回 false
     */
    private static boolean toBoolean(Object value) {
        return value instanceof Number number && number.intValue() != 0;
    }
}
