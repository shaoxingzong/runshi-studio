package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
import org.apache.ibatis.annotations.Mapper;

// 成员-项目关联数据访问层（对应 studio_member_project）
//
// 继承 BaseMapper 即自动获得单表 CRUD；查询采用「两步查询」
// （先取关联 ID、再 IN 查主表），不需要自定义 SQL，也就不需要 XML。
//
// 本表是物理删除：没有 deleted_at 列，将来在这里加自定义查询时
// 记得它没有逻辑删除条件可用；而关联到的 studio_member / studio_project
// 两张主表是逻辑删除——它们的 deleted_at = 0 由 MP 自动追加
// （走 Mapper 的 selectList 时），手写 JOIN 时必须自己带上，否则会查出悬空数据。
//
// 索引： uk_member_proj (member_id, project_id) 负责防重（并发兜底），
// idx_proj_member (project_id, member_id) 负责反向查询。
@Mapper
public interface StudioMemberProjectMapper extends BaseMapper<StudioMemberProject> {
}
