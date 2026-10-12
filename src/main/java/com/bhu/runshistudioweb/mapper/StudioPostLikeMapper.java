package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_like 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，因此这里不需要写任何方法：
 * <ul>
 *     <li>查重：{@code selectCount(post_id = ? AND user_id = ?)}；</li>
 *     <li>「当前用户赞了这批里的哪几篇」：{@code selectList(in(post_id).eq(user_id))}
 *     ——一次查询，避免给列表每篇各查一次（那是 N+1）。</li>
 * </ul>
 *
 * <p><b>两条索引各服务一类查询</b>（论证见 {@code db/user.sql} 中 {@code studio_post_like} 的建表语句）：
 * <ul>
 *     <li>{@code uk_post_user (post_id, user_id)}：查重与「一人一赞」的唯一性根基；
 *     同时覆盖「某篇的点赞人数」这类反查；</li>
 *     <li>{@code idx_user_created (user_id, created_at)}：「我赞过哪些帖子」按时间倒序，
 *     为将来做「我的点赞列表」预留（本期没有该接口，先不删）。</li>
 * </ul>
 *
 * <p>注意：{@code @MapperScan} 已在 MyBatisPlusConfig 中批量扫描本包，
 * 这里的 {@code @Mapper} 属于重复声明，与其余 Mapper 保持一致保留。
 */
@Mapper
public interface StudioPostLikeMapper extends BaseMapper<StudioPostLike> {
}
