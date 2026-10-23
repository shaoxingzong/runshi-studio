package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostReport;
import org.apache.ibatis.annotations.Mapper;

// studio_post_report 数据访问层（内容举报）
//
// 继承 BaseMapper 即自动获得单表 CRUD，因此这里不需要写任何方法。
//
// uk_target_reporter (target_type, target_id, reporter_id, deleted_at)
// 是业务约束而不只是索引：它保证同一人无法重复举报同一内容。
// 唯一键带上 deletedAt 的用意是——举报被删除后，同一人可以再次举报
// （「误举报后撤销、看清问题再举报」是真实场景），不带这个字段就会撞唯一键而永远举报不了。
//
// 软关联的代价必须在这里记住： targetId 指向帖子或评论，
// 但没有外键保证它有效。删除帖子/评论时，应用层必须一并处置其举报记录，
// 否则待处理列表里会出现永远查不到目标的悬空举报。
//
// 待处理列表走 idx_status_time (status, created_at)：
// WHERE status = 0 ORDER BY created_at。
@Mapper
public interface StudioPostReportMapper extends BaseMapper<StudioPostReport> {
}
