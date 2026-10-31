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

    /** 趋势里日期字段的格式 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 聚合查询用的模板 */
    private final JdbcTemplate jdbcTemplate;

    /** 记录一次页面访问（匿名可写） */
    @Override
    public void report(TrafficReportRequest request) {
        // 请求体整体 null：@Valid 不覆盖这种情况，兜一层避免后面拆箱子 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        StudioPageVisit visit = new StudioPageVisit();
        // 路径规范化放服务端再做一次：前端代码可能是旧缓存，或有人直接调接口
        visit.setPagePath(normalizePath(request.getPagePath()));
        visit.setVisitorId(StrUtil.trim(request.getVisitorId()));
        visit.setReferrerHost(StrUtil.trimToNull(request.getReferrerHost()));
        visit.setDeviceType(StrUtil.trimToNull(request.getDeviceType()));
        // 用户身份只从登录态取：未登录为 NULL，绝不信请求体
        visit.setUserId(StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null);
        visit.setCreatedBy(0L);

        this.save(visit);
        // debug 级别：埋点是高频旁路，用 info 会把真正的业务日志淹掉
        log.debug("页面访问已记录 | path={} | visitor={} | userId={}",
                visit.getPagePath(), visit.getVisitorId(), visit.getUserId());
    }

    /** 流量总览（PV / UV / 按天趋势 / 热门页面） */
    @Override
    public TrafficOverviewVO getOverview(int days) {
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

    // 私有工具

    /** 规范化页面路径：剥掉 query 与 hash */
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

    /** 执行一条返回单个计数的 SQL */
    private Integer count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count.intValue();
    }

    /** 时段内 UV：按 visitor_id 去重 */
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

    /** 每日趋势（近 N 天，无访问的日期补 0） */
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

    /** 热门页面 TOP N（按 PV 倒序） */
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
