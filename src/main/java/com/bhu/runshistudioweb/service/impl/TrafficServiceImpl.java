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
 * 为什么统计走 JdbcTemplate 而不是 MyBatis-Plus 的 LambdaQueryWrapper：
 * 本期要的是 COUNT(*)、COUNT(DISTINCT visitor_id)、
 * GROUP BY DATE(created_at) 这类聚合——MP 的 Lambda 体系表达不了
 * DATE() 函数与多列分组，硬要表达就得写自定义 XML。
 * 而这些 SQL 的参数全部是「天数」这种内部推导的整数，没有用户输入拼接，
 * 不存在注入面。单表聚合 + 索引，数据量级（月均万级）下无需分页。
 *
 * 为什么埋点失败不让用户感知：它挂在门户每个页面。统计是旁路，
 * 旁路故障不该影响主流程——这是与「RAG 检索失败降级」同一条纪律。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficServiceImpl extends ServiceImpl<StudioPageVisitMapper, StudioPageVisit>
        implements TrafficService {

    /** 趋势天数默认值 */
    private static final int DEFAULT_DAYS = 30;

    /** 趋势天数上限：再长的趋势对「看近期态势」没有意义，只会把查询拖慢 */
    private static final int MAX_DAYS = 90;

    /** 热门页面取前几名 */
    private static final int TOP_PAGE_LIMIT = 10;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void report(TrafficReportRequest request) {
        // 请求体整体 null：@Valid 不覆盖这种情况，兜一层避免后面拆箱子 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        StudioPageVisit visit = new StudioPageVisit();
        // 路径规范化放服务端再做一次：前端可能被旧版本缓存，或有人直接调接口
        visit.setPagePath(normalizePath(request.getPagePath()));
        visit.setVisitorId(StrUtil.trim(request.getVisitorId()));
        visit.setReferrerHost(StrUtil.trimToNull(request.getReferrerHost()));
        visit.setDeviceType(StrUtil.trimToNull(request.getDeviceType()));
        // 用户身份只从登录态取：未登录为 NULL，绝不信请求体
        visit.setUserId(StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null);
        visit.setCreatedBy(0L);

        this.save(visit);
        log.debug("页面访问已记录 | path={} | visitor={} | userId={}",
                visit.getPagePath(), visit.getVisitorId(), visit.getUserId());
    }

    @Override
    public TrafficOverviewVO getOverview(int days) {
        int safeDays = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);

        LocalDate today = LocalDate.now();
        LocalDateTime from = today.minusDays(safeDays - 1L).atStartOfDay();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        TrafficOverviewVO vo = new TrafficOverviewVO();
        vo.setTotalPv(count("SELECT COUNT(*) FROM studio_page_visit WHERE created_at >= ?", from));
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
     * 即使前端已剥，服务端也要再剥一次——埋点接口是匿名可写的，
     * 调用方不一定是我们的前端。query 里可能带 token，绝不能进库。
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

    private Integer count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count.intValue();
    }

    /** 时段内 UV：按 visitor_id 去重 */
    private Integer countDistinctVisitor(LocalDateTime from, LocalDateTime to) {
        String sql = to == null
                ? "SELECT COUNT(DISTINCT visitor_id) FROM studio_page_visit WHERE created_at >= ?"
                : "SELECT COUNT(DISTINCT visitor_id) FROM studio_page_visit WHERE created_at >= ? AND created_at < ?";
        Long count = to == null
                ? jdbcTemplate.queryForObject(sql, Long.class, from)
                : jdbcTemplate.queryForObject(sql, Long.class, from, to);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 每日趋势（近 N 天，无访问的日期补 0）
     *
     * 为什么要补 0：直接 GROUP BY 只会返回有数据的日期，
     * 前端图表会把「没人来」和「没数据」画成同一回事（断档），
     * 而这两者的运营含义完全相反。
     */
    private List<DailyTrafficVO> dailyTrend(LocalDateTime from, LocalDate today, int days) {
        // 先按天聚合出有数据的部分
        Map<LocalDate, long[]> pvUvByDay = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT DATE(created_at) AS d, COUNT(*) AS pv, COUNT(DISTINCT visitor_id) AS uv "
                        + "FROM studio_page_visit WHERE created_at >= ? GROUP BY DATE(created_at)",
                rs -> {
                    LocalDate day = rs.getDate("d").toLocalDate();
                    pvUvByDay.put(day, new long[] {rs.getLong("pv"), rs.getLong("uv")});
                },
                from);

        // 再按完整日期区间补齐（倒序：最近的一天在最前面）
        List<DailyTrafficVO> result = new ArrayList<>(days);
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            long[] pvUv = pvUvByDay.getOrDefault(day, new long[] {0L, 0L});
            DailyTrafficVO vo = new DailyTrafficVO();
            vo.setDate(day.format(DAY_FORMAT));
            vo.setPv((int) pvUv[0]);
            vo.setUv((int) pvUv[1]);
            result.add(vo);
        }
        return result;
    }

    /** 热门页面 TOP N（按 PV 倒序） */
    private List<PageTrafficVO> topPages(LocalDateTime from) {
        return jdbcTemplate.query(
                "SELECT page_path, COUNT(*) AS pv, COUNT(DISTINCT visitor_id) AS uv "
                        + "FROM studio_page_visit WHERE created_at >= ? "
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
