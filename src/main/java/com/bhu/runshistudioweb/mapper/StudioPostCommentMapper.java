package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_comment 表的数据访问层（楼中楼评论）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：某帖的全部评论必须走 idx_post_status_id 一次查完，再在内存里按 parentId 挂成树，
 * 逐条查回复就是 N+1；删顶层评论时条件要写成 WHERE id = ? OR parent_id = ?。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostCommentMapper extends BaseMapper<StudioPostComment> {
}
