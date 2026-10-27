package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_project 表的数据访问层（项目案例）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：「队长是谁」的权威源是本表 leader_id 列而非关联表；idx_status_sort_time 与本表
 * 默认排序同向，按状态筛选时能直接吃到有序结果。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioProjectMapper extends BaseMapper<StudioProject> {
}
