package com.bhu.runshistudioweb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.model.vo.statistic.StatisticOverviewVO;
import com.bhu.runshistudioweb.service.StatisticService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计服务实现（官网首页大盘）
 *
 * 三条强制口径（改本类前先读）：
 * - 一律排除逻辑删除数据：studio_certificate 带
 * @TableLogic deletedAt，MyBatis-Plus 会自动把 deleted_at = 0
 * 追加到查询条件里（包括 selectCount 与 selectMaps）。
 * 这个条件是"看不见"的——它不在我们写的代码里，只在最终 SQL 里，
 * 所以改统计口径时必须去 SQL 日志里确认它还在，否则统计会静默多算；
 * - 枚举外的脏值忽略不统计，不报错：正常不会有（写入口已拦），
 * 但统计接口不该因为一条脏数据让整个首页挂掉；
 * - 空库 / 某维度为空时返回完整 key 集合且值为 0：
 * GROUP BY 不会返回「0 条」的行，必须在代码里按枚举补齐，
 * 否则前端图表的维度会错乱。
 *
 * 为什么只注入 Mapper 而不是业务 Service：聚合查询（COUNT / GROUP BY）
 * 属于数据访问层职责，不需要经过业务层；而且业务 Service 里没有现成的聚合方法，
 * 为了统计再去加接口反而污染它们的契约。
 *
 * 为什么用 QueryWrapper 而不是 LambdaQueryWrapper：
 * COUNT(*) AS cnt 与 GROUP BY 无法用 Lambda 的方法引用表达
 * （Lambda 只能引用实体字段，不能写聚合表达式），只能用字符串列名。
 * 这些列名是硬编码常量（见本类常量），不来自用户输入，因此不存在 SQL 注入。
 *
 * 明确不做（避免加码）：
 * - 不加 Redis 缓存：数据量几百行、首页 QPS 很低；真要加，得先回答失效策略
 * （成员/证书增删改时什么时候删缓存），那是一个独立任务；
 * - 不做写操作、不做任何入参；
 * - 不为「看起来走了索引」硬凑 SQL：本表量级下 COUNT(*) 与
 * GROUP BY 全表扫描完全可接受（索引论证只用来解释当前路径为什么够用，
 * 不是要求现在就去优化）。
 *
 * 覆盖面：当前只统计证书维度。
 * 要增加项目维度时，在 VO 加字段、在本类加对应的 COUNT / GROUP BY 即可，
 * 接口路径与响应结构都不用改；管理端若需要人数统计，应另开一个要求 admin 角色的接口，
 * 不要把「对外展示」与「内部运营」两类口径混在同一个匿名接口里。
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class StatisticServiceImpl implements StatisticService {

    /**
     * COUNT(*) 在 selectMaps 结果里的别名
     *
     * 统一用常量：避免「这里写 cnt、那里写 count」导致取值时拿到 null 又静默按 0 处理。
     * 这种拼写漂移不会报错，只会让图表恒为 0，属于最难查的一类问题。
     */
    private static final String COUNT_ALIAS = "cnt";

    /**
     * 分组列名
     *
     * 硬编码常量，不是用户输入，因此把它们拼进 SQL 不存在注入面——
     * 这也是本类敢于用字符串列名（而不是 Lambda 方法引用）的前提。
     */
    private static final String AWARD_LEVEL_COLUMN = "award_level";
    private static final String AWARD_TYPE_COLUMN = "award_type";

    /**
     * 证书表 Mapper
     *
     * 注入 Mapper 而不是证书业务 Service：本类只做聚合读取，不需要任何业务规则，
     * 而业务 Service 也没有现成的聚合方法——为了统计去给它加方法，
     * 等于让一个面向业务的契约背上统计口径。
     */
    private final StudioCertificateMapper studioCertificateMapper;

    /**
     * 查询官网首页大盘：共 3 条查询，顺序即实现顺序
     *
     * <p>为什么这样设计：首页大盘是「每次有人打开官网就要跑一次」的接口，
     * 而它只需要四个数字与两个分布，因此刻意用 1 次 COUNT + 2 次 GROUP BY 解决，
     * 不引入缓存也不引入汇总表。缓存需要回答「成员或证书增删改时什么时候失效」，
     * 汇总表则要在每个写入口额外维护一份数据——这两笔成本都远大于当前的查询开销。
     * 三条查询彼此独立、互不依赖结果，所以顺序可以任意；这里按「总数 → 级别 → 类型」
     * 排列，只是为了与返回对象的字段顺序一致，读代码时不用来回跳。
     * 刻意不含成员维度（总人数 / 在读 / 已毕业）：对外暴露人数同样属于展示成员信息，
     * 而成员档案的可见范围由成员模块自己把关，统计接口不该成为绕过它的旁路。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>证书总数：无条件 COUNT，逻辑删除条件由框架自动追加（见行内注释）；</li>
     *     <li>按级别分组：key 集合取自枚举，缺位补 0——GROUP BY 不会返回「0 条」的行，
     *     不补齐的话前端图表的维度会少一块；</li>
     *     <li>按类型分组：与上一步同构，只是换了分组列与枚举。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里看起来「什么都没过滤」，很容易让人以为会统计到已删除的数据。
     * 实际上 deleted_at = 0 这个条件不在本类的代码里，而在最终执行的 SQL 里
     * （由实体上的 @TableLogic 驱动，selectCount 与 selectMaps 都会带上）。
     * 因此改动统计口径时必须去 SQL 日志里确认这个条件还在，
     * 否则一旦它失效，统计会静默多算，且接口不会报任何错。
     *
     * @return 大盘统计，永不为 null；两个分布的 key 集合恒等于对应枚举的全部取值，
     * 库里一条数据都没有时返回全 0 而不是空 Map，调用方无需判空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不主动抛业务异常：
     * 没有入参，也就不存在参数校验。数据库不可用时异常会一路上抛，
     * 由全局异常处理器兜成系统错误（B0001），对外只提示「系统错误」，细节只进日志
     */
    @Override
    public StatisticOverviewVO getOverview() {
        StatisticOverviewVO overview = new StatisticOverviewVO();

        // ① 证书总数：selectCount(null) 表示无附加条件，
        // 但 MP 仍会自动拼上 deleted_at = 0（逻辑删除条件由 @TableLogic 驱动，与入参无关）
        overview.setCertificateTotal(toInt(studioCertificateMapper.selectCount(null)));

        // ② 证书按级别分组（key 固定为级别枚举的三个取值，缺位补 0）
        QueryWrapper<StudioCertificate> levelWrapper = new QueryWrapper<>();
        // select 的第一个参数是分组列，第二个是聚合表达式：COUNT(*) 必须写成字符串，
        // Lambda 形式只能引用实体字段，表达不了聚合函数
        levelWrapper.select(AWARD_LEVEL_COLUMN, "COUNT(*) AS " + COUNT_ALIAS)
                .groupBy(AWARD_LEVEL_COLUMN);
        overview.setCertificateByLevel(buildDistribution(
                studioCertificateMapper.selectMaps(levelWrapper),
                AWARD_LEVEL_COLUMN,
                enumValues(CertificateLevelEnum.values(), CertificateLevelEnum::getValue)));

        // ③ 证书按类型分组（key 固定为类型枚举的四个取值，缺位补 0）
        QueryWrapper<StudioCertificate> typeWrapper = new QueryWrapper<>();
        typeWrapper.select(AWARD_TYPE_COLUMN, "COUNT(*) AS " + COUNT_ALIAS)
                .groupBy(AWARD_TYPE_COLUMN);
        overview.setCertificateByType(buildDistribution(
                studioCertificateMapper.selectMaps(typeWrapper),
                AWARD_TYPE_COLUMN,
                enumValues(CertificateTypeEnum.values(), CertificateTypeEnum::getValue)));

        return overview;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 把聚合结果转成「key → 计数」（用于分组查询）
     *
     * <p>为什么这样设计：selectMaps 返回的是「列名 → 值」的裸 Map 列表，
     * 直接交给上层就意味着每个调用方都要自己判空、自己转类型。
     * 在这里一次性收敛成 Map&lt;String, Integer&gt;，上层只关心「某个取值有几个」。
     * 用 String 作 key 而不是泛型或枚举：分组列的值来自数据库，
     * 数据库里可能存着枚举之外的脏值，用 String 接住才能既不丢数据也不抛异常。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>遍历每一行聚合结果，取出分组列的值；</li>
     *     <li>该列为 NULL 时跳过。COUNT(*) 会为 NULL 单独分一组，
     *     这一组在业务上没有名字，统计进来只会得到一个「key 是 null」的条目；</li>
     *     <li>用 String.valueOf 归一化 key，再用 toInt 归一化计数后放入结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：同一列在不同驱动下可能以不同 Java 类型返回
     * （String、byte[] 都可能），所以 key 必须经 String.valueOf 统一，
     * 不能直接强转 String。计数值同理，硬转 Long 会在换环境时抛 ClassCastException，
     * 必须走 toInt 里的 Number 分支。
     *
     * @param rows selectMaps 的结果，可能为 null（无数据时部分驱动返回 null 而不是空列表）
     * @param keyColumn 作为 key 的列名，必须是 select 里出现过的列或它的别名
     * @return 计数映射，key 为分组列的字符串值；key 为 null 的行会被跳过，
     * 因此返回的条目数可能少于入参行数。无数据时返回空 Map，不会返回 null
     */
    private Map<String, Integer> toCountMap(List<Map<String, Object>> rows, String keyColumn) {
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(keyColumn);
            if (key == null) {
                // 该列本身为 NULL 的行不作统计（枚举外的脏值由 buildDistribution 丢弃，
                // 两者的区别：这里是「列没有值」，那里是「有值但不在枚举内」）
                continue;
            }
            result.put(String.valueOf(key), toInt(row.get(COUNT_ALIAS)));
        }
        return result;
    }

    /**
     * 组装分布统计：先按枚举把 key 全部置 0，再叠加真实计数
     *
     * <p>为什么这样设计：GROUP BY 只会返回「至少有一条数据」的分组，
     * 某个级别一张证书都没有时，SQL 结果里根本没有这个 key。
     * 前端图表是按固定维度渲染的，少一个 key 会让柱子数量、颜色映射整体错位，
     * 而错位的图表比没有图表更误导人。所以这里反过来做：
     * 先用枚举把「应该有哪几个 key」全部铺成 0，再用真实计数覆盖。
     * 另一个替代方案是在前端补齐，但那等于让每个调用方各写一遍补零逻辑，
     * 漏一处就是一次图表错乱，因此把这件事放在服务端一次做对。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按枚举顺序把每个 key 放进 LinkedHashMap 并置 0；</li>
     *     <li>把聚合结果转成 Map（这一步顺带丢掉「分组列为 NULL」的行）；</li>
     *     <li>再按枚举遍历一次，只覆盖枚举内的 key。</li>
     * </ol>
     *
     * <p>容易踩的坑：容器必须是有序的。用 HashMap 时 key 的顺序取决于哈希值，
     * 每次刷新页面图例顺序都可能变，看起来像「图表在随机跳动」。
     * 另外最后一步只遍历 allKeys 而不是遍历 counted，
     * 这样才能把枚举外的脏值自然丢弃——遍历 counted 会把脏值也写进结果，
     * 前端拿到一个不认识的 key 只能忽略或报错。
     *
     * @param rows selectMaps 的结果，可能为 null
     * @param keyColumn 作为 key 的列名
     * @param allKeys 该维度全部合法取值（来自枚举，决定 key 的顺序与完整性），不能为 null
     * @return 分布映射，key 集合等于 allKeys 且顺序与之一致，缺位为 0；
     * 不会返回 null，也不会出现 allKeys 之外的 key
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不抛业务异常：
     * allKeys 为 null 时会抛空指针，但那属于调用方的编码错误，
     * 在启动或首次调用时就会暴露，不必包装成业务异常
     */
    private Map<String, Integer> buildDistribution(List<Map<String, Object>> rows,
                                                   String keyColumn, String[] allKeys) {
        // LinkedHashMap 保序：让响应里的 key 顺序与枚举声明顺序一致，
        // 前端图表的图例顺序才稳定（HashMap 不保证顺序，会导致每次刷新图例乱跳）
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (String key : allKeys) {
            distribution.put(key, 0);
        }

        Map<String, Integer> counted = toCountMap(rows, keyColumn);
        // 只叠加枚举内的 key：枚举外的脏值在这一步被自然丢弃，不报错也不中断统计
        for (String key : allKeys) {
            Integer count = counted.get(key);
            if (count != null) {
                distribution.put(key, count);
            }
        }
        return distribution;
    }

    /**
     * 把 COUNT(*) 的结果转成 Integer
     *
     * <p>为什么这样设计：聚合结果在 JDBC 层没有统一类型。同一个 COUNT(*)，
     * MySQL 驱动可能给 Long、可能给 BigInteger，某些封装下还会给 BigDecimal，
     * 这取决于驱动版本与连接参数。因此只能按它们共同的父类 Number 来取，
     * 不能用具体的包装类。返回 Integer 而不是 Long 是 VO 的约定：
     * 项目的全局 JSON 配置把 Long 序列化成字符串（为 19 位雪花 ID 让路），
     * 而计数必须让前端拿到数字，用 Long 会让前端收到 "12" 这种字符串。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>判断是不是 Number。是就取 intValue，null 与非数字类型统一返回 0；</li>
     *     <li>没有第二步——本方法刻意不做范围校验：工作室的数据量级不可能让计数溢出 int，
     *     真溢出了也说明统计口径出了大问题，此时返回一个荒谬的数字反而更容易被发现。</li>
     * </ol>
     *
     * <p>容易踩的坑：绝不硬转 (Long)。硬转在本机（驱动返回 Long）能跑通，
     * 换环境返回 BigInteger 时就抛 ClassCastException——属于「本机过、上线炸」的典型，
     * 而且报错位置在统计接口里，排查时很容易怀疑到 SQL 而不是这一行强转。
     *
     * @param value 聚合结果，可能是 Long / Integer / BigInteger / BigDecimal，也可能为 null
     * @return int 值；null 或非数字类型时返回 0（统计接口不该因为一条脏数据让首页挂掉）
     */
    private Integer toInt(Object value) {
        // Number 是 Long / Integer / BigInteger / BigDecimal 的共同父类，统一走它最稳
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 取枚举的全部入库值（顺序与枚举声明顺序一致）
     *
     * <p>为什么这样设计：分布的 key 集合必须来自枚举，而不是来自数据库里的实际值——
     * 用实际值就退化成「有几条数据就有几个 key」，某个取值恰好为 0 时它会消失，
     * 前端图表随之缺一块。把枚举值抽成数组交给 buildDistribution，
     * 「这个维度应该有哪几个 key」这件事就只有一个来源。
     * 顺序保持枚举声明顺序，是为了让图例顺序稳定且符合产品定义的优先级。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>把枚举数组转成流；</li>
     *     <li>逐个套用取值函数，拿到入库用的字符串；</li>
     *     <li>收集成 String 数组。这里不用 toList 再转数组，
     *     是为了让调用方直接拿到定长数组，少一层 List 的装箱与复制。</li>
     * </ol>
     *
     * <p>容易踩的坑：传进来的 getter 必须是「入库值」而不是中文描述。
     * 用 getDesc 会让 key 变成「国家级」，与数据库里存的 national 对不上，
     * 结果是所有计数都叠加不上去，图表全为 0——而且不报任何错。
     *
     * @param values 枚举数组，通常是 XxxEnum.values()，不能为 null（为空数组时返回空数组）
     * @param getter 取值函数，必须是返回入库值的那个 getter
     * @param <E> 枚举类型
     * @return 全部入库值数组，长度与入参一致、顺序与枚举声明顺序一致
     */
    private <E> String[] enumValues(E[] values, java.util.function.Function<E, String> getter) {
        return Arrays.stream(values).map(getter).toArray(String[]::new);
    }
}
