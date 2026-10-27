package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_page_visit 表的数据访问层（页面访问记录）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 埋点用 MP 的 insert；统计走 JdbcTemplate，因为聚合查询 MP 表达不了（见 TrafficServiceImpl）。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPageVisitMapper extends BaseMapper<StudioPageVisit> {
}
