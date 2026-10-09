package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，因此这里不需要写任何方法。
 * 列表查询、审核队列、「我的发帖」都能用 {@code selectPage} + {@code LambdaQueryWrapper} 表达，
 * 不必引入 XML。
 *
 * <p><b>三条索引各服务一条查询，写 SQL 时要对上号</b>（论证见
 * {@code db/migration/20261009_add_post.sql}）：
 * <ul>
 *     <li>{@code idx_status_pinned_time (status, pinned, created_at)}：
 *     公开列表 {@code WHERE status=1 AND deleted_at=0 ORDER BY pinned DESC, created_at DESC}。
 *     置顶列必须夹在中间，排序才能直接吃到索引顺序；</li>
 *     <li>{@code idx_status_time (status, created_at)}：
 *     审核队列 {@code WHERE status=0 ORDER BY created_at}（先进先出）。
 *     <b>不能拿上面那条凑合</b>——它的第二列是 pinned，优化器无法跳过中间列按 created_at 排序；</li>
 *     <li>{@code idx_author_time (author_id, created_at)}：「我的发帖」，含待审与驳回。</li>
 * </ul>
 *
 * <p><b>两个冗余列要在这里留意</b>：{@code comment_count} 由评论审核通过 / 删除时维护，
 * {@code summary} 与 {@code cover_image} 在入库时按正文生成。
 * 它们都<b>不能</b>在读取时现算——列表页一次 20 条，现算就是 20 次额外查询或 20 次字符串处理。
 *
 * <p>注意：{@code @MapperScan} 已在 MyBatisPlusConfig 中批量扫描本包，
 * 这里的 {@code @Mapper} 属于重复声明，与其余 Mapper 保持一致保留。
 */
@Mapper
public interface StudioPostMapper extends BaseMapper<StudioPost> {
}
