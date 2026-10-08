package com.bhu.runshistudioweb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.model.vo.StatisticOverviewVO;
import com.bhu.runshistudioweb.service.StatisticService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计服务实现（官网首页大盘，DESIGN.md 场景 A 落地）
 *
 * author: shaoshing
 *
 * <p><b>三条强制口径（改本类前先读）</b>：
 * <ol>
 *     <li><b>一律排除逻辑删除数据</b>：studio_certificate 带
 *     {@code @TableLogic deletedAt}，MyBatis-Plus 会自动把 {@code deleted_at = 0}
 *     追加到查询条件里（包括 {@code selectCount} 与 {@code selectMaps}）。
 *     <b>这个条件是"看不见"的</b>——它不在我们写的代码里，只在最终 SQL 里，
 *     所以改统计口径时必须去 SQL 日志里确认它还在，否则统计会静默多算；</li>
 *     <li><b>枚举外的脏值忽略不统计，不报错</b>：正常不会有（写入口已拦），
 *     但统计接口不该因为一条脏数据让整个首页挂掉；</li>
 *     <li><b>空库 / 某维度为空时返回完整 key 集合且值为 0</b>：
 *     {@code GROUP BY} 不会返回「0 条」的行，必须在代码里按枚举补齐，
 *     否则前端图表的维度会错乱。</li>
 * </ol>
 *
 * <p><b>为什么只注入 Mapper 而不是业务 Service</b>：聚合查询（COUNT / GROUP BY）
 * 属于数据访问层职责，不需要经过业务层；而且业务 Service 里没有现成的聚合方法，
 * 为了统计再去加接口反而污染它们的契约。
 *
 * <p><b>为什么用 {@link QueryWrapper} 而不是 LambdaQueryWrapper</b>：
 * {@code COUNT(*) AS cnt} 与 {@code GROUP BY} 无法用 Lambda 的方法引用表达
 * （Lambda 只能引用实体字段，不能写聚合表达式），只能用字符串列名。
 * 这些列名是<b>硬编码常量</b>（见本类常量），不来自用户输入，因此不存在 SQL 注入。
 *
 * <p><b>明确不做</b>（避免加码）：
 * <ul>
 *     <li>不加 Redis 缓存：数据量几百行、首页 QPS 很低；真要加，得先回答失效策略
 *     （成员/证书增删改时什么时候删缓存），那是一个独立任务；</li>
 *     <li>不做写操作、不做任何入参；</li>
 *     <li>不为「看起来走了索引」硬凑 SQL：本表量级下 {@code COUNT(*)} 与
 *     {@code GROUP BY} 全表扫描完全可接受（DESIGN 场景 A 的索引论证只是解释
 *     当前路径为什么够用，不是要求我们优化）。</li>
 * </ul>
 *
 * <p><b>本期范围：只覆盖证书维度</b>。
 * 项目模块（{@code studio_project}）落地后在 VO 加字段、这里加对应查询即可，
 * 接口路径与响应结构都不用改；管理端若将来需要人数统计，应另开一个要求 admin 的接口。
 */
@Service
@RequiredArgsConstructor
public class StatisticServiceImpl implements StatisticService {

    /**
     * COUNT(*) 在 {@code selectMaps} 结果里的别名
     *
     * <p>统一用常量：避免「这里写 cnt、那里写 count」导致取值时拿到 null 又静默按 0 处理。
     */
    private static final String COUNT_ALIAS = "cnt";

    /** 分组列名：硬编码常量，不是用户输入，不存在注入风险 */
    private static final String AWARD_LEVEL_COLUMN = "award_level";
    private static final String AWARD_TYPE_COLUMN = "award_type";

    private final StudioCertificateMapper studioCertificateMapper;

    /**
     * 查询官网首页大盘：共 3 条查询，顺序即实现顺序
     *
     * <p>成员维度（总数 / 在读 / 已毕业）已随「团队成员不对外展示」移除。
     *
     * @return 大盘统计，永不为 null
     */
    @Override
    public StatisticOverviewVO getOverview() {
        StatisticOverviewVO overview = new StatisticOverviewVO();

        // ① 证书总数：selectCount(null) 表示无附加条件，
        // 但 MP 仍会自动拼上 deleted_at = 0（逻辑删除条件由 @TableLogic 驱动，与入参无关）
        overview.setCertificateTotal(toInt(studioCertificateMapper.selectCount(null)));

        // ② 证书按级别分组（key 固定为级别枚举的三个取值，缺位补 0）
        QueryWrapper<StudioCertificate> levelWrapper = new QueryWrapper<>();
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
     * @param rows      {@code selectMaps} 的结果
     * @param keyColumn 作为 key 的列名
     * @return 计数映射；key 为 null 的行（脏数据未填该列）会被跳过
     */
    private Map<String, Integer> toCountMap(List<Map<String, Object>> rows, String keyColumn) {
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(keyColumn);
            if (key == null) {
                // 该列本身为 NULL 的行不作统计（口径 1 的边界情况：列有值但不在枚举内，见 buildDistribution）
                continue;
            }
            result.put(String.valueOf(key), toInt(row.get(COUNT_ALIAS)));
        }
        return result;
    }

    /**
     * 组装分布统计：<b>先按枚举把 key 全部置 0，再叠加真实计数</b>
     *
     * @param rows      {@code selectMaps} 的结果
     * @param keyColumn 作为 key 的列名
     * @param allKeys   该维度全部合法取值（来自枚举，决定 key 的顺序与完整性）
     * @return 分布映射，key 集合等于 {@code allKeys}，缺位为 0
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
        // 只叠加枚举内的 key：枚举外的脏值在这一步被自然丢弃（口径 2，不报错）
        for (String key : allKeys) {
            Integer count = counted.get(key);
            if (count != null) {
                distribution.put(key, count);
            }
        }
        return distribution;
    }

    /**
     * 把 {@code COUNT(*)} 的结果转成 Integer
     *
     * @param value 聚合结果，可能是 {@code Long} 或 {@code BigDecimal / BigInteger}
     *              （<b>不同驱动、不同 MySQL 版本下类型并不一致</b>），也可能为 null
     * @return int 值；null 或非数字时返回 0
     */
    private Integer toInt(Object value) {
        // 关键：绝不硬转 (Long)。硬转在本机（MySQL 驱动返回 Long）能跑通，
        // 换环境返回 BigInteger 时就抛 ClassCastException——属于「本机过、上线炸」的典型。
        // Number 是 Long / Integer / BigInteger / BigDecimal 的共同父类，统一走它最稳
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 取枚举的全部入库值（顺序与枚举声明顺序一致）
     *
     * @param values   枚举数组
     * @param getter   取值函数
     * @param <E>      枚举类型
     * @return 全部入库值数组
     */
    private <E> String[] enumValues(E[] values, java.util.function.Function<E, String> getter) {
        return Arrays.stream(values).map(getter).toArray(String[]::new);
    }
}
