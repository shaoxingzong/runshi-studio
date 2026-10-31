package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.vo.statistic.StatisticOverviewVO;

/**
 * 统计服务接口
 *
 * @author shaoshing
 */
public interface StatisticService {

    /**
     * 查询官网首页大盘数据
     *
     * @return 大盘统计，永不为 null；空库时返回全 0 且分布 key 完整（顺序与枚举一致）
     */
    StatisticOverviewVO getOverview();
}
