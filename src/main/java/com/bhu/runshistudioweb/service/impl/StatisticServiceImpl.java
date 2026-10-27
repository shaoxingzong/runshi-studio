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
 * 三条强制口径：一律排除逻辑删除数据（@TableLogic 自动追加，条件只在最终 SQL 里）；
 * 枚举外的脏值忽略不统计；空库或某维度为空时返回完整 key 集合且值为 0。
 *
 * 只注入 Mapper：聚合查询属于数据访问层职责，业务 Service 没有现成的聚合方法。
 * 用 QueryWrapper 而不是 LambdaQueryWrapper：COUNT(*) 与 GROUP BY 无法用方法引用表达，
 * 列名全是硬编码常量，不存在注入面。不加缓存也不建汇总表：数据量几百行、首页 QPS 很低。
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
     * 首页大盘每次打开官网都要跑，因此用 1 次 COUNT + 2 次 GROUP BY 解决，不引入缓存或汇总表。
     * 三条查询彼此独立，按「总数 → 级别 → 类型」排列只是为了与返回对象的字段顺序一致。
     * 刻意不含成员维度：对外暴露人数同样属于展示成员信息，统计接口不该成为绕过成员模块的旁路。
     *
     * 坑：这里看起来「什么都没过滤」，其实 deleted_at = 0 由 @TableLogic 驱动追加，
     * 改统计口径时必须去 SQL 日志里确认它还在，否则统计会静默多算且不报错。
     *
     * @return 大盘统计，永不为 null；两个分布的 key 集合恒等于对应枚举的全部取值，
     * 库里一条数据都没有时返回全 0 而不是空 Map
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
     * selectMaps 返回的是「列名 → 值」的裸 Map，在这里一次性收敛成 Map&lt;String, Integer&gt;，
     * 上层只关心「某个取值有几个」。key 用 String 是为了接住枚举之外的脏值：既不丢数据也不抛异常。
     *
     * 坑：同一列在不同驱动下可能以 String 或 byte[] 返回，key 必须经 String.valueOf 统一，
     * 不能直接强转；计数值同理，硬转 Long 换环境就会抛 ClassCastException。
     *
     * @param rows selectMaps 的结果，可能为 null（无数据时部分驱动返回 null 而不是空列表）
     * @param keyColumn 作为 key 的列名，必须是 select 里出现过的列或它的别名
     * @return 计数映射；key 为 null 的行被跳过，因此条目数可能少于入参行数，无数据时返回空 Map
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
     * GROUP BY 只返回「至少有一条数据」的分组，某个级别一张证书都没有时 SQL 结果里根本没这个 key，
     * 前端图表是按固定维度渲染的，少一个 key 会让柱子与颜色映射整体错位。
     * 在前端补齐等于让每个调用方各写一遍补零逻辑，所以放在服务端一次做对。
     *
     * 坑：容器必须是有序的，用 HashMap 时图例顺序会随哈希值变化，看起来像「图表在随机跳动」；
     * 最后一步只遍历 allKeys，才能把枚举外的脏值自然丢弃。
     *
     * @param rows selectMaps 的结果，可能为 null
     * @param keyColumn 作为 key 的列名
     * @param allKeys 该维度全部合法取值（来自枚举，决定 key 的顺序与完整性），不能为 null
     * @return 分布映射，key 集合等于 allKeys 且顺序一致，缺位为 0
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
     * 同一个 COUNT(*) 可能给 Long、BigInteger 甚至 BigDecimal（随驱动版本与连接参数），
     * 只能按共同父类 Number 取。返回 Integer 是 VO 的约定：全局 JSON 配置把 Long 序列化成字符串，
     * 而计数必须让前端拿到数字。
     *
     * 坑：绝不硬转 (Long)。本机（驱动返回 Long）能跑通，换环境返回 BigInteger 就抛
     * ClassCastException，而且报错位置在统计接口里，很容易怀疑到 SQL 而不是这一行强转。
     *
     * @param value 聚合结果，可能是 Long / Integer / BigInteger / BigDecimal，也可能为 null
     * @return int 值；null 或非数字类型时返回 0
     */
    private Integer toInt(Object value) {
        // Number 是 Long / Integer / BigInteger / BigDecimal 的共同父类，统一走它最稳
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 取枚举的全部入库值（顺序与枚举声明顺序一致）
     *
     * 分布的 key 集合必须来自枚举而不是库里的实际值：用实际值就退化成「有几条数据就有几个 key」，
     * 某个取值为 0 时它会消失，前端图表随之缺一块。顺序保持声明顺序，图例才稳定。
     *
     * 坑：传进来的 getter 必须是「入库值」而不是中文描述。用 getDesc 会让 key 变成「国家级」，
     * 与库里存的 national 对不上，结果是所有计数都叠加不上去，图表全为 0 且不报错。
     *
     * @param values 枚举数组，通常是 XxxEnum.values()，不能为 null（为空数组时返回空数组）
     * @param getter 取值函数，必须是返回入库值的那个 getter
     * @return 全部入库值数组，长度与入参一致、顺序与枚举声明顺序一致
     */
    private <E> String[] enumValues(E[] values, java.util.function.Function<E, String> getter) {
        return Arrays.stream(values).map(getter).toArray(String[]::new);
    }
}
