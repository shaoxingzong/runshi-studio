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
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class StatisticServiceImpl implements StatisticService {

    /** COUNT(*) 在 selectMaps 结果里的别名 */
    private static final String COUNT_ALIAS = "cnt";

    /** 分组列名 */
    private static final String AWARD_LEVEL_COLUMN = "award_level";
    private static final String AWARD_TYPE_COLUMN = "award_type";

    /** 证书表 Mapper */
    private final StudioCertificateMapper studioCertificateMapper;

    /** 查询官网首页大盘：共 3 条查询，顺序即实现顺序 */
    @Override
    public StatisticOverviewVO getOverview() {
        StatisticOverviewVO overview = new StatisticOverviewVO();

        overview.setCertificateTotal(toInt(studioCertificateMapper.selectCount(null)));

        // 证书按级别分组（key 固定为级别枚举的三个取值，缺位补 0）
        QueryWrapper<StudioCertificate> levelWrapper = new QueryWrapper<>();
        levelWrapper.select(AWARD_LEVEL_COLUMN, "COUNT(*) AS " + COUNT_ALIAS)
                .groupBy(AWARD_LEVEL_COLUMN);
        overview.setCertificateByLevel(buildDistribution(
                studioCertificateMapper.selectMaps(levelWrapper),
                AWARD_LEVEL_COLUMN,
                enumValues(CertificateLevelEnum.values(), CertificateLevelEnum::getValue)));

        // 证书按类型分组（key 固定为类型枚举的四个取值，缺位补 0）
        QueryWrapper<StudioCertificate> typeWrapper = new QueryWrapper<>();
        typeWrapper.select(AWARD_TYPE_COLUMN, "COUNT(*) AS " + COUNT_ALIAS)
                .groupBy(AWARD_TYPE_COLUMN);
        overview.setCertificateByType(buildDistribution(
                studioCertificateMapper.selectMaps(typeWrapper),
                AWARD_TYPE_COLUMN,
                enumValues(CertificateTypeEnum.values(), CertificateTypeEnum::getValue)));

        return overview;
    }

    // 私有工具方法

    /** 把聚合结果转成「key → 计数」（用于分组查询） */
    private Map<String, Integer> toCountMap(List<Map<String, Object>> rows, String keyColumn) {
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(keyColumn);
            if (key == null) {
                continue;
            }
            result.put(String.valueOf(key), toInt(row.get(COUNT_ALIAS)));
        }
        return result;
    }

    /** 组装分布统计：先按枚举把 key 全部置 0，再叠加真实计数 */
    private Map<String, Integer> buildDistribution(List<Map<String, Object>> rows,
                                                   String keyColumn, String[] allKeys) {
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

    /** 把 COUNT(*) 的结果转成 Integer */
    private Integer toInt(Object value) {
        // Number 是 Long / Integer / BigInteger / BigDecimal 的共同父类，统一走它最稳
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 取枚举的全部入库值（顺序与枚举声明顺序一致） */
    private <E> String[] enumValues(E[] values, java.util.function.Function<E, String> getter) {
        return Arrays.stream(values).map(getter).toArray(String[]::new);
    }
}
