package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_like 数据访问层
 *
 * 继承 BaseMapper 即自动获得单表 CRUD，因此这里不需要写任何方法：
 * - 查重：selectCount(post_id = ? AND user_id = ?)；
 * - 「当前用户赞了这批里的哪几篇」：selectList(in(post_id).eq(user_id))
 * ——一次查询，避免给列表每篇各查一次（那是 N+1）。
 *
 * 两条索引各服务一类查询（论证见 db/user.sql 中 studio_post_like 的建表语句）：
 * - uk_post_user (post_id, user_id)：查重与「一人一赞」的唯一性根基；
 * 同时覆盖「某篇的点赞人数」这类反查；
 * - idx_user_created (user_id, created_at)：「我赞过哪些帖子」按时间倒序，
 * 为将来做「我的点赞列表」预留（本期没有该接口，先不删）。
 */
@Mapper
public interface StudioPostLikeMapper extends BaseMapper<StudioPostLike> {
}
