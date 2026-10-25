package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 页面访问记录 Mapper
 *
 * 本 Mapper 不写任何自定义 SQL：埋点用 MP 的 insert，
 * 统计走 JdbcTemplate（聚合查询 MP 表达不了，见
 * TrafficServiceImpl 的类注释）。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPageVisitMapper extends BaseMapper<StudioPageVisit> {
}
