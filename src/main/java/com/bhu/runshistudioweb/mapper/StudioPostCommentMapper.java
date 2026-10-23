package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import org.apache.ibatis.annotations.Mapper;

// studio_post_comment 数据访问层（楼中楼评论）
//
// 继承 BaseMapper 即自动获得单表 CRUD，因此这里不需要写任何方法。
//
// 最高频的查询是「某帖的全部评论」，必须走
// idx_post_status_id (post_id, status, id)：
// WHERE post_id = ? AND status = 1 AND deleted_at = 0 ORDER BY id。
// 一次查完整个帖子的评论，再由 Service 在内存里按 parentId 挂成树。
//
// 一定要一次查完，不要按父评论逐条查：
// for (顶层评论 c : 列表) { 查 c 的回复 } 是标准 N+1，
// 一个热门帖有 50 条顶层评论就是 50 次查询。
//
// parentId 刻意没有索引：它只参与内存分组，不进 WHERE 条件，
// 建了也用不上，只会增加写成本。
//
// 删除是级联的：删一条顶层评论时，条件写成
// WHERE id = ? OR parent_id = ?，让它的回复一并逻辑删除，
// 不留「父已删、子还在」的孤儿回复。
@Mapper
public interface StudioPostCommentMapper extends BaseMapper<StudioPostComment> {
}
