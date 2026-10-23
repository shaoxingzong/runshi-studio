package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import org.apache.ibatis.annotations.Mapper;

// studio_post 数据访问层
//
// 继承 BaseMapper 即自动获得单表 CRUD，因此这里不需要写任何方法。
// 列表查询、审核队列、「我的发帖」都能用 selectPage + LambdaQueryWrapper 表达，
// 不必引入 XML。
//
// 三条索引各服务一条查询，写 SQL 时要对上号（论证见
// db/user.sql 中 studio_post 的建表语句）：
// - idx_status_pinned_time (status, pinned, created_at)：
// 公开列表 WHERE status=1 AND deleted_at=0 ORDER BY pinned DESC, created_at DESC。
// 置顶列必须夹在中间，排序才能直接吃到索引顺序；
// - idx_status_time (status, created_at)：
// 审核队列 WHERE status=0 ORDER BY created_at（先进先出）。
// 不能拿上面那条凑合——它的第二列是 pinned，优化器无法跳过中间列按 created_at 排序；
// - idx_author_time (author_id, created_at)：「我的发帖」，含待审与驳回。
//
// 两个冗余列要在这里留意： comment_count 由评论审核通过 / 删除时维护，
// summary 与 cover_image 在入库时按正文生成。
// 它们都不能在读取时现算——列表页一次 20 条，现算就是 20 次额外查询或 20 次字符串处理。
@Mapper
public interface StudioPostMapper extends BaseMapper<StudioPost> {
}
