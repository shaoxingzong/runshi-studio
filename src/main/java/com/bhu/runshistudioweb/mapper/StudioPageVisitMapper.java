package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 页面访问记录 Mapper
 *
 * author: shaoshing
 *
 * <p>本 Mapper <b>不写任何自定义 SQL</b>：埋点用 MP 的 insert，
 * 统计走 {@code JdbcTemplate}（聚合查询 MP 表达不了，见
 * {@code TrafficServiceImpl} 的类注释）。
 */
@Mapper
public interface StudioPageVisitMapper extends BaseMapper<StudioPageVisit> {
}
