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
 * 这里要的是 COUNT(*)、COUNT(DISTINCT visitor_id)、
 * GROUP BY DATE(created_at) 这类聚合——MP 的 Lambda 体系表达不了
 * DATE() 函数与多列分组，硬要表达就得写自定义 XML。
 * 而这些 SQL 的参数全部是「天数」这种内部推导的整数，没有用户输入拼接，
 * 不存在注入面。单表聚合 + 索引，数据量级（月均万级）下无需分页。
 *
 * 为什么埋点失败不让用户感知：它挂在门户每个页面。统计是旁路，
 * 旁路故障不该影响主流程——与问答链路「检索失败仍照常回答」是同一条纪律：
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
     * <p>为什么这样设计：这是全项目唯一「允许游客写库」的接口，因此三条约束同时成立。
     * 第一，路径必须在服务端再规范化一次——前端提交前已经剥掉了 query 与 hash，
     * 但前端代码可能还是浏览器缓存里的那一份，也可能有人绕开页面直接调接口，
     * 而 query 里经常带分享 token，原样入库等于把凭证写进一张匿名可写的表。
     * 第二，用户身份只能从登录态取——请求体里根本没有 userId 字段，
     * 若允许前端传，任何人都能伪造他人的访问记录。
     * 第三，本方法要足够宽容：埋点挂在门户的每个页面上，它失败一次就会让用户
     * 看到一个报错弹窗，这比「少统计一条」严重得多。
     * 之所以不做成异步（丢进队列后立刻返回），是因为单条 INSERT 已经足够快，
     * 为它引入消息队列或线程池，运维成本远大于收益。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验请求体非空——@Valid 只校验字段级约束，整个对象为 null 时不会触发，
     *     不兜这一层，后面的 getPagePath() 会直接抛空指针变成 500；</li>
     *     <li>规范化路径：剥掉 query 与 hash，空路径兜成根路径；</li>
     *     <li>填访客标识、来源域名、设备类型，三者都做 trim；</li>
     *     <li>用户身份从登录态取，未登录留 null——这是「匿名可写」的落点，
     *     user_id 列允许为空正是为此；</li>
     *     <li>落库并打一条 debug 日志。</li>
     * </ol>
     *
     * <p>容易踩的坑：createdBy 显式写成 0L，而不是让审计填充器去填。
     * 原因是填充器拿不到登录态，本来也只会填 0L（含义是「系统写入」）；
     * 这里显式写出来，是为了让读代码的人一眼看出「这条记录的创建人不是某个用户」，
     * 而不是以为漏填了。另外不要把本方法改成抛异常给前端——
     * 埋点失败的正确处理是让前端静默，业务码不该出现在用户的浏览路径上。
     *
     * @param request 埋点请求（pagePath 与 visitorId 必填，referrerHost 与 deviceType 选填）；
     *                整个对象为 null 时抛 A0401。pagePath 的超长与格式由 DTO 上的注解拦截，
     *                本方法只负责再剥一次 query
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     * 数据库写入失败（B0001）时抛出；调用方（前端）应当忽略这些失败
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
        // 显式写 0L 表示「系统写入」：审计填充器本来也只能填 0L（它拿不到登录态），
        // 写出来是为了让读代码的人不必去翻填充器的实现
        visit.setCreatedBy(0L);

        this.save(visit);
        // debug 级别：埋点是高频旁路，用 info 会把真正的业务日志淹掉
        log.debug("页面访问已记录 | path={} | visitor={} | userId={}",
                visit.getPagePath(), visit.getVisitorId(), visit.getUserId());
    }

    /**
     * 流量总览（PV / UV / 按天趋势 / 热门页面）
     *
     * <p>为什么这样设计：四个数字（总 PV、总 UV、今日 PV、今日 UV）各用一条聚合 SQL，
     * 趋势与热门页面各一条，一共 6 条查询。看起来条数不少，但它们都是单表 COUNT，
     * 在「月均万级」的数据量下每条都是毫秒级；反过来把它们合并成一条
     * 用条件聚合（CASE WHEN）的 SQL，可读性会急剧下降，而收益只有几次往返。
     * 这里刻意不做分页：热门页面只要前 10 名，趋势只要 N 天，
     * 结果集大小天然有界。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先收敛天数：0 或负数按默认值、超过上限截断。必须先做这一步，
     *     因为下面的 from 是由它算出来的，越界的天数会让查询扫全表；</li>
     *     <li>算出三个时间边界。用「今天零点」而不是 now 减若干小时，
     *     是为了让「今日」严格对齐自然日，不受调用时刻影响；</li>
     *     <li>依次填总 PV、总 UV、今日 PV、今日 UV；</li>
     *     <li>填趋势与热门页面（各自一条查询）。</li>
     * </ol>
     *
     * <p>容易踩的坑：区间是左闭右开——from 含、to 不含。
     * 若写成 BETWEEN 或 &lt;= 明天零点，昨天 23:59:59.999 与今天 00:00:00.000
     * 这种边界会被算进两次或漏算，而统计口径的偏差一旦被人拿去汇报就很难纠正。
     * 另外 safeDays 不能直接拿入参去算 from：days 传 0 时 minusDays(-1) 会得到明天，
     * 查询范围直接变成「未来」，结果全是 0 且不报错。
     *
     * @param days 统计天数，含义是「含今天在内的最近 N 天」；小于 1 时按 30 天处理，
     *             大于 90 时收敛到 90（再长的趋势对「看近期态势」没有意义，只会拖慢查询）
     * @return 总览数据，各计数在无数据时为 0 而不是 null；daily 与 topPages 为空列表，
     * 不会为 null，调用方无需判空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不主动抛业务异常：
     * days 的越界值被静默收敛，不做参数校验（这是刻意的——它是管理员看的只读统计，
     * 传错天数不该让页面报错）。数据库不可用时异常一路上抛，
     * 由全局异常处理器兜成系统错误（B0001）
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
     * <p>为什么这样设计：即使前端已经剥过一次，服务端也必须再剥——
     * 埋点接口是匿名可写的，调用方不一定是我们的前端。query 里可能带 token
     * （分享链接、邮件营销参数都会带），一旦原样入库，等于把凭证写进了一张
     * 任何脚本都能写、管理员都能看的表。剥掉 query 之后，同一个页面的不同参数
     * 会自然聚合到一起，「热门页面」的统计口径才成立。
     * 只剥不校验：路径的格式与长度由 DTO 上的 @NotBlank / @Size 负责，
     * 本方法只做「从完整 URL 里取出路径」这一件事，两者职责不重叠。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>trim 并兜空：null 转成空串，避免后续 indexOf 抛空指针；</li>
     *     <li>先剥 hash 再剥 query。顺序不能反：形如 {@code /p?x=1#/detail?y=2}
     *     这种带 hash 路由的地址，若先按 ? 切，会把 {@code /p} 留下没问题，
     *     但反过来遇到 {@code /p#/detail?y=2} 时先切 ? 会残留一段 hash 内容；</li>
     *     <li>兜底：剥完为空（例如调用方只传了 "?a=b"）时记成根路径。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这里做「白名单校验」（例如必须以 / 开头、必须属于已知路由）。
     * 前端路由会随产品迭代变化，白名单一旦滞后，新页面的埋点会被静默丢弃，
     * 表现为「新功能上线后统计里没有它」，而且不会有任何报错。
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
     * <p>为什么这样设计：所有计数 SQL 的形状都一样——一个占位符、一个 Long 结果，
     * 差别只在 SQL 文本与参数。抽成这一个方法后，调用方只需要关心 SQL 本身。
     * 结果类型写 Long.class 而不是 Integer.class：COUNT(*) 在 JDBC 里天然是 BIGINT，
     * 声明成 Integer 会让驱动做一次可能失败的转换。
     * 参数走可变参数的占位符绑定，不拼字符串——即使本类的参数都是内部推导出来的整数，
     * 也不留下「拼 SQL」这种会被后来者照抄的写法。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用 queryForObject 执行并拿到 Long；</li>
     *     <li>null 兜成 0 再转 int。COUNT 理论上不会返回 null，
     *     但 queryForObject 在没有结果行时确实会返回 null，兜一层免得拆箱 NPE。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法的返回值是 Integer，而计数列在 VO 里也是 Integer——
     * 这是刻意的。项目的全局 JSON 配置把 Long 序列化成字符串（为 19 位雪花 ID 让路），
     * 若这里返回 Long，前端拿到的会是 "128" 这样的字符串，排序与加法都会按字典序算。
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
     * <p>为什么这样设计：UV（独立访客数）不能用 COUNT(*) 得到，必须按访客标识去重，
     * 所以单独抽一个方法，避免在 getOverview 里写两条几乎一样的长 SQL。
     * 上界做成可选（to 传 null 表示不设上界），是因为「总 UV」与「今日 UV」
     * 的差别只有这一个边界，为它们写两个方法只会让 SQL 文本重复两遍。
     * 去重依据是前端的 localStorage 随机 uuid，因此同一人换设备、清缓存、
     * 用无痕模式都会被算成多个 UV——这是轻量埋点的固有边界，
     * 要更准就得记 IP 或设备指纹，那会引入隐私与合规成本。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按 to 是否为 null 选一条 SQL 文本；</li>
     *     <li>按同样的分支决定传几个参数——参数个数必须与占位符个数严格一致，
     *     少传一个会抛 Parameter index out of range；</li>
     *     <li>null 兜 0 后转 int。</li>
     * </ol>
     *
     * <p>容易踩的坑：COUNT(DISTINCT visitor_id) 会跳过 visitor_id 为 NULL 的行，
     * 这是符合预期的（没有访客标识就无法算作一个访客）；但如果将来把 visitor_id
     * 改成可空，UV 会系统性地偏小，而不是报错。另外 visitor_id 是前端可控的字符串，
     * 伪造大量不同的 uuid 可以抬高 UV——本接口匿名可写，这个风险无法在 SQL 层消除，
     * 只能靠限流（见埋点接口的访问频率限制）。
     *
     * @param from 区间起点（含），不能为 null
     * @param to 区间终点（不含）；传 null 表示不设上界，即统计 from 之后的全部
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
     * <p>为什么这样设计：先查数据库、再在内存里补齐日期，而不是让 SQL 生成日期序列。
     * MySQL 里用递归 CTE 或日历表确实能一次查出连续日期，但那需要 8.0 以上版本
     * 或额外维护一张日历表，成本远大于「在 Java 里循环 N 次」。
     * 为什么必须补 0：直接 GROUP BY 只会返回有数据的日期，
     * 前端图表会把「没人来」和「没数据」画成同一回事（断档），
     * 而这两者的运营含义完全相反——一个是「那天真的没人访问」，另一个是「我们没记到」。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用 ResultSetExtractor 逐行读聚合结果，塞进「日期 → [pv, uv]」的 Map。
     *     用数组而不是建一个小类：这里只有两个数字且立刻被消费，建类属于过度设计；</li>
     *     <li>从最早的一天往今天循环，逐个从 Map 取；取不到就用 {0, 0} 兜底；</li>
     *     <li>按 yyyy-MM-dd 格式化后装进 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：数据库返回的 DATE 列要经 rs.getDate 转 LocalDate，
     * 不能拿字符串去和 LocalDate 比——驱动返回的格式随配置而变，
     * 而 LocalDate 的相等比较是稳定的。另外循环方向是从 days-1 递减到 0
     * （即时间正序），若写成递增会得到倒序的日期序列，
     * 前端折线图的横轴就反了，而这种错误肉眼很难一眼看出。
     *
     * @param from 区间起点（含），由调用方按「今天零点减去 N-1 天」算好
     * @param today 今天（服务器时区），作为补齐循环的终点
     * @param days 天数，必须大于 0；调用方已收敛过取值范围
     * @return 长度为 days 的列表，按日期正序（最早在前），每天一项；
     * 无访问的日期 pv 与 uv 都是 0，不会返回 null
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
     * <p>为什么这样设计：只取前 N 名而不是返回全部页面，因为页面数量会随站点增长，
     * 而运营真正关心的是头部；LIMIT 直接写进 SQL，让数据库只返回需要的行，
     * 而不是查全量再在内存里截断。
     * LIMIT 的值是常量而不是参数：它是编译期确定的固定值，
     * 拼进 SQL 没有注入面（这也解释了为什么这里可以直接字符串拼接）。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按 page_path 分组，同时算出 PV 与 UV；</li>
     *     <li>按 PV 倒序并 LIMIT；</li>
     *     <li>用 RowMapper 逐行映射成 VO，省掉中间实体对象。</li>
     * </ol>
     *
     * <p>容易踩的坑：GROUP BY 之后只能按聚合结果或分组列排序，
     * 按 created_at 排会得到「每组里随便挑一行的时间」，结果毫无意义且不报错。
     * 另外 UV 在同一页面内的口径是「有多少个不同访客来过这个页面」，
     * 与总 UV 不可相加——同一个人访问两个页面会被两边各算一次。
     *
     * @param from 区间起点（含），由调用方算好
     * @return 最多 TOP_PAGE_LIMIT 条记录，按 PV 从高到低；
     * 区间内没有任何访问时返回空列表，不会返回 null
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
