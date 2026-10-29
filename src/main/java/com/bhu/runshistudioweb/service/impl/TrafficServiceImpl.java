package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioPageVisitMapper;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import com.bhu.runshistudioweb.model.vo.traffic.DailyTrafficVO;
import com.bhu.runshistudioweb.model.vo.traffic.PageTrafficVO;
import com.bhu.runshistudioweb.model.vo.traffic.TrafficOverviewVO;
import com.bhu.runshistudioweb.service.TrafficService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流量统计服务实现
 *
 * 统计走 JdbcTemplate 而不是 MyBatis-Plus：这里要 COUNT(*)、COUNT(DISTINCT visitor_id)、
 * GROUP BY DATE(created_at)，MP 的 Lambda 体系表达不了 DATE() 与多列分组，硬要表达就得写自定义 XML。
 * 参数全是「天数」这种内部推导的整数，没有用户输入拼接，不存在注入面。
 *
 * 埋点失败不让用户感知：它挂在门户每个页面，统计是旁路，旁路故障不该影响主流程——
 * 增强能力可以降级，主流程不能因为旁路而中断。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficServiceImpl extends ServiceImpl<StudioPageVisitMapper, StudioPageVisit>
        implements TrafficService {

    /** 趋势天数默认值：调用方不传或传了非正数时用它 */
    private static final int DEFAULT_DAYS = 30;

    /** 趋势天数上限：再长的趋势对「看近期态势」没有意义，只会把查询拖慢 */
    private static final int MAX_DAYS = 90;

    /** 热门页面取前几名：运营只看头部，返回全量既慢又没人看 */
    private static final int TOP_PAGE_LIMIT = 10;

    /**
     * 趋势里日期字段的格式
     *
     * 用 yyyy-MM-dd 而不是时间戳：这个字段直接进前端图表的横轴，
     * 给一个可读的字符串比让前端再格式化一次更省事，也让接口响应自解释。
     * 它只用于输出，不参与任何比较——日期之间的比较一律走 LocalDate，
     * 拿格式化后的字符串去比会在跨月、跨年时出错。
     */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 聚合查询用的模板
     *
     * 只用它跑「不需要实体映射」的统计 SQL。本类同时继承了 ServiceImpl
     * （为了复用 save 这类单表方法），因此两种数据访问方式并存是刻意的：
     * 写单条记录用 MyBatis-Plus（能自动填审计字段），读聚合用 JdbcTemplate（能写任意 SQL）。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 记录一次页面访问（匿名可写）
     *
     * 这是全项目唯一「允许游客写库」的接口，因此三条约束同时成立。
     * 路径必须在服务端再规范化一次——前端代码可能还是缓存里的旧版本，也可能有人直接调接口，
     * 而 query 里经常带分享 token，原样入库等于把凭证写进一张匿名可写的表。
     * 用户身份只能从登录态取：请求体里根本没有 userId 字段，允许前端传就能伪造他人记录。
     * 埋点失败不能让用户看到报错弹窗，那比「少统计一条」严重得多。
     *
     * 坑：createdBy 显式写成 0L（含义是「系统写入」）而不是让审计填充器填。
     * 填充器现在会填入当前登录用户，而「是谁的会话产生了这条埋点」与「谁创建了这条记录」
     * 不是一回事——埋点是系统行为，不该冒充成某个人的创建记录。
     *
     * @param request 埋点请求（pagePath 与 visitorId 必填，referrerHost 与 deviceType 选填）
     * @throws BusinessException 请求体为 null 抛 A0401，数据库写入失败抛 B0001；调用方应当忽略这些失败
     */
    @Override
    public void report(TrafficReportRequest request) {
        // 请求体整体 null：@Valid 不覆盖这种情况，兜一层避免后面拆箱子 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        StudioPageVisit visit = new StudioPageVisit();
        // 路径规范化放服务端再做一次：前端代码可能是旧缓存，或有人直接调接口
        visit.setPagePath(normalizePath(request.getPagePath()));
        visit.setVisitorId(StrUtil.trim(request.getVisitorId()));
        // 选填字段用 trimToNull 而不是 trim：空串入库会让「有没有来源」变成两种写法，
        // 聚合与前端判断都得多考虑一种情况
        visit.setReferrerHost(StrUtil.trimToNull(request.getReferrerHost()));
        visit.setDeviceType(StrUtil.trimToNull(request.getDeviceType()));
        // 用户身份只从登录态取：未登录为 NULL，绝不信请求体
        visit.setUserId(StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null);
        // 显式写 0L 表示「系统写入」：填充器会记成当前登录用户，
        // 但埋点不是那个人「创建」的业务数据，写出来也省得读代码的人去翻填充器
        visit.setCreatedBy(0L);

        this.save(visit);
        // debug 级别：埋点是高频旁路，用 info 会把真正的业务日志淹掉
        log.debug("页面访问已记录 | path={} | visitor={} | userId={}",
                visit.getPagePath(), visit.getVisitorId(), visit.getUserId());
    }

    /**
     * 流量总览（PV / UV / 按天趋势 / 热门页面）
     *
     * 一共 6 条查询，都是单表 COUNT，在「月均万级」下每条都是毫秒级；合并成一条条件聚合的 SQL
     * 可读性会急剧下降，收益只有几次往返。热门页面只要前 10、趋势只要 N 天，结果集天然有界，不分页。
     *
     * 坑：区间是左闭右开（from 含、to 不含），写成 BETWEEN 会让边界时刻被算进两次或漏算；
     * 也不能直接拿入参算 from——days 传 0 时 minusDays(-1) 会得到明天，查询范围变成「未来」，
     * 结果全是 0 且不报错。
     *
     * @param days 统计天数，含义是「含今天在内的最近 N 天」；小于 1 按 30 天，大于 90 收敛到 90
     * @return 总览数据，各计数在无数据时为 0 而不是 null；daily 与 topPages 为空列表
     */
    @Override
    public TrafficOverviewVO getOverview(int days) {
        // 先收敛天数再算时间边界：顺序反过来会用一个越界的 days 去算 from，
        // 例如 days=0 时 from 会落到未来，查询范围整个落空且不报错
        int safeDays = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);

        LocalDate today = LocalDate.now();
        // 区间左闭右开：from 含、tomorrowStart 不含。这样「昨天」与「今天」不会在边界上重叠
        LocalDateTime from = today.minusDays(safeDays - 1L).atStartOfDay();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        TrafficOverviewVO vo = new TrafficOverviewVO();
        vo.setTotalPv(count("SELECT COUNT(*) FROM studio_page_visit WHERE created_at >= ?", from));
        // UV 传 null 表示「不设上界」：总数口径只关心 from 之后，没有右边界
        vo.setTotalUv(countDistinctVisitor(from, null));
        vo.setTodayPv(count("SELECT COUNT(*) FROM studio_page_visit WHERE created_at >= ? AND created_at < ?",
                todayStart, tomorrowStart));
        vo.setTodayUv(countDistinctVisitor(todayStart, tomorrowStart));
        vo.setDaily(dailyTrend(from, today, safeDays));
        vo.setTopPages(topPages(from));
        return vo;
    }

    // ==================== 私有工具 ====================

    /**
     * 规范化页面路径：剥掉 query 与 hash
     *
     * 即使前端已经剥过一次，服务端也必须再剥——埋点接口匿名可写，调用方不一定是我们的前端。
     * query 里可能带 token，原样入库等于把凭证写进一张任何脚本都能写的表；
     * 剥掉之后同一个页面的不同参数会自然聚合，「热门页面」的口径才成立。
     *
     * 坑：不要在这里做白名单校验。前端路由会随产品迭代变化，白名单一旦滞后，
     * 新页面的埋点会被静默丢弃，表现为「新功能上线后统计里没有它」。
     *
     * @param pagePath 原始路径，可为 null 或只含 query
     * @return 规范化后的路径；永远不会返回 null 或空串，最差返回 "/"
     */
    private String normalizePath(String pagePath) {
        String path = StrUtil.trimToEmpty(pagePath);
        int hashIndex = path.indexOf('#');
        if (hashIndex >= 0) {
            path = path.substring(0, hashIndex);
        }
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        // 兜底：空路径（例如只传了 "?a=b"）记成根路径，而不是塞一条无意义的记录
        return path.isEmpty() ? "/" : path;
    }

    /**
     * 执行一条返回单个计数的 SQL
     *
     * 所有计数 SQL 的形状都一样，抽出来之后调用方只需关心 SQL 本身。
     * 结果类型写 Long.class 而不是 Integer.class：COUNT(*) 在 JDBC 里天然是 BIGINT。
     * 参数走占位符绑定，即使都是内部推导的整数，也不留下会被后来者照抄的拼 SQL 写法。
     *
     * 坑：返回值是 Integer 而计数列在 VO 里也是 Integer，这是刻意的——全局 JSON 配置把 Long
     * 序列化成字符串，若这里返回 Long，前端拿到的会是 "128"，排序与加法都会按字典序算。
     *
     * @param sql 带占位符的计数 SQL，不能为 null
     * @param args 与占位符一一对应的参数；个数不匹配时 JDBC 会抛异常
     * @return 计数值；无结果行或结果为 null 时返回 0，不会返回 null
     */
    private Integer count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 时段内 UV：按 visitor_id 去重
     *
     * UV 不能用 COUNT(*) 得到，必须按访客标识去重，所以单独抽一个方法。
     * 上界做成可选（to 传 null 表示不设上界），是因为「总 UV」与「今日 UV」的差别只有这一个边界。
     * 去重依据是前端的 localStorage 随机 uuid，同一人换设备、清缓存、用无痕都会被算成多个 UV——
     * 这是轻量埋点的固有边界，要更准就得记 IP 或设备指纹，那会引入隐私与合规成本。
     *
     * 坑：COUNT(DISTINCT visitor_id) 会跳过 visitor_id 为 NULL 的行，这是符合预期的；
     * 但 visitor_id 是前端可控的字符串，伪造大量 uuid 可以抬高 UV，只能靠限流缓解。
     *
     * @param from 区间起点（含），不能为 null
     * @param to 区间终点（不含）；传 null 表示不设上界
     * @return 去重后的访客数；区间内没有数据时返回 0
     */
    private Integer countDistinctVisitor(LocalDateTime from, LocalDateTime to) {
        String sql = to == null
                ? "SELECT COUNT(DISTINCT visitor_id) FROM studio_page_visit WHERE created_at >= ?"
                : "SELECT COUNT(DISTINCT visitor_id) FROM studio_page_visit WHERE created_at >= ? AND created_at < ?";
        // 参数个数必须与上面选中的 SQL 一致：两条 SQL 的占位符数量不同，所以这里也要分支
        Long count = to == null
                ? jdbcTemplate.queryForObject(sql, Long.class, from)
                : jdbcTemplate.queryForObject(sql, Long.class, from, to);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 每日趋势（近 N 天，无访问的日期补 0）
     *
     * 先查数据库、再在内存里补齐日期，而不是让 SQL 生成日期序列：递归 CTE 或日历表成本更高。
     * 必须补 0：直接 GROUP BY 只返回有数据的日期，前端会把「没人来」和「没记到」画成同一回事。
     *
     * 坑：DATE 列要经 rs.getDate 转 LocalDate，不能拿字符串去比；循环方向必须是时间正序，
     * 写成倒序会让前端折线图的横轴反过来，肉眼很难一眼看出。
     *
     * @param from 区间起点（含），由调用方按「今天零点减去 N-1 天」算好
     * @param today 今天（服务器时区），作为补齐循环的终点
     * @param days 天数，必须大于 0；调用方已收敛过取值范围
     * @return 长度为 days 的列表，按日期正序；无访问的日期 pv 与 uv 都是 0
     */
    private List<DailyTrafficVO> dailyTrend(LocalDateTime from, LocalDate today, int days) {
        // 先按天聚合出有数据的部分
        Map<LocalDate, long[]> pvUvByDay = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT DATE(created_at) AS d, COUNT(*) AS pv, COUNT(DISTINCT visitor_id) AS uv "
                        + "FROM studio_page_visit WHERE created_at >= ? GROUP BY DATE(created_at)",
                rs -> {
                    // 用 getDate 取 DATE 列再转 LocalDate：让驱动负责解析，
                    // 避免拿字符串去比较（格式随驱动配置而变）
                    LocalDate day = rs.getDate("d").toLocalDate();
                    pvUvByDay.put(day, new long[] {rs.getLong("pv"), rs.getLong("uv")});
                },
                from);

        // 再按完整日期区间补齐（正序：最早的一天在最前面，与折线图横轴方向一致）
        List<DailyTrafficVO> result = new ArrayList<>(days);
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            // 取不到说明那天没有任何访问：补 0 而不是跳过，否则图表会出现断档
            long[] pvUv = pvUvByDay.getOrDefault(day, new long[] {0L, 0L});
            DailyTrafficVO vo = new DailyTrafficVO();
            vo.setDate(day.format(DAY_FORMAT));
            vo.setPv((int) pvUv[0]);
            vo.setUv((int) pvUv[1]);
            result.add(vo);
        }
        return result;
    }

    /**
     * 热门页面 TOP N（按 PV 倒序）
     *
     * 只取前 N 名而不是返回全部页面：运营真正关心的是头部，LIMIT 写进 SQL 让数据库只返回需要的行。
     * LIMIT 的值是编译期常量，拼进 SQL 没有注入面。
     *
     * 坑：GROUP BY 之后只能按聚合结果或分组列排序，按 created_at 排会得到「每组里随便挑一行的时间」，
     * 结果毫无意义且不报错；页面 UV 与总 UV 不可相加，同一个人访问两个页面会被两边各算一次。
     *
     * @param from 区间起点（含），由调用方算好
     * @return 最多 TOP_PAGE_LIMIT 条记录，按 PV 从高到低；区间内没有访问时返回空列表
     */
    private List<PageTrafficVO> topPages(LocalDateTime from) {
        return jdbcTemplate.query(
                "SELECT page_path, COUNT(*) AS pv, COUNT(DISTINCT visitor_id) AS uv "
                        + "FROM studio_page_visit WHERE created_at >= ? "
                        // LIMIT 拼常量而不是占位符：它是编译期固定值，不来自用户输入，没有注入面
                        + "GROUP BY page_path ORDER BY pv DESC LIMIT " + TOP_PAGE_LIMIT,
                (rs, rowNum) -> {
                    PageTrafficVO vo = new PageTrafficVO();
                    vo.setPagePath(rs.getString("page_path"));
                    vo.setPv(rs.getInt("pv"));
                    vo.setUv(rs.getInt("uv"));
                    return vo;
                },
                from);
    }
}
