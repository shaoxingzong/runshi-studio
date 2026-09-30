package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目案例数据访问层（对应 studio_project）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，这里<b>不需要写任何方法</b>：
 * 分页、筛选、排序全部由 Service 用 {@code LambdaQueryWrapper} 拼装。
 *
 * <p><b>两点索引提醒（写查询时对照 db/DESIGN.md）</b>：
 * <ul>
 *     <li>{@code idx_status_sort_time (status, sort_order DESC, created_at DESC)}：
 *     按状态筛选时，本表的默认排序（sort_order DESC → created_at DESC）与索引同向，
 *     能直接吃到有序结果；</li>
 *     <li>{@code idx_leader (leader_id)}：按队长查项目走它。
 *     注意「队长是谁」的权威源是本表列，不是关联表——查 leader_id 请走本 Mapper。</li>
 * </ul>
 *
 * <p>逻辑删除由 {@code @TableLogic} 驱动：所有查询自动追加 {@code deleted_at = 0}。
 */
@Mapper
public interface StudioProjectMapper extends BaseMapper<StudioProject> {
}
