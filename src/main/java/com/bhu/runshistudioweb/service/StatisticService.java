package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.vo.statistic.StatisticOverviewVO;

/**
 * 统计服务接口
 *
 * 本期只提供一个「官网首页大盘」，因此只有 getOverview 一个方法。
 * 后续要做「按年份统计」「管理端运营看板」时，按同样套路在本接口加方法即可——
 * 统计类接口彼此独立，不要为了复用而把查询条件塞进一个万能方法里。
 *
 * 三条强制口径（实现类必须遵守）：
 * - 一律排除逻辑删除的数据（ deleted_at = 0）；
 * - 出现枚举外的脏值（正常不会有，写入口已拦）→ 忽略不统计，不报错；
 * - 空库或某维度为空时，返回完整的 key 集合且值为 0。
 * 第 2 条的理由：统计接口是首页的「锦上添花」，不该因为一条脏数据让整个首页挂掉；
 * 第 3 条的理由：前端图表按固定维度渲染，key 缺失会导致维度错乱或渲染报错。
 */
public interface StatisticService {

    /**
     * 查询官网首页大盘数据
     *
     * 匿名可访问，无任何入参。
     *
     * @return 大盘统计；空库时返回全 0 且两个分布的 key 集合完整，绝不返回 null
     */
    StatisticOverviewVO getOverview();
}
