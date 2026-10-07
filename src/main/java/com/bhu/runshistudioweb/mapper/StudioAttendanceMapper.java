package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_attendance 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，因此这里不需要写任何方法。
 * 出勤看板需要的「按天统计」「按用户分组计数」都由 {@code ServiceImpl}
 * 的 {@code listMaps} + {@code QueryWrapper.groupBy} 完成，不必引入 XML。
 *
 * <p>与 {@link SysUserMapper} 相同的三条要点（每条都踩过坑，故重复说明）：
 * <ul>
 *     <li><b>逻辑删除自动生效</b>：{@link StudioAttendance} 有 {@code @TableLogic} 字段，
 *     查询自动追加 {@code deleted_at = 0}；手写 SQL 时必须自己带该条件；</li>
 *     <li><b>审计字段自动填充</b>：insert 触发
 *     {@link com.bhu.runshistudioweb.config.MyMetaObjectHandler}，不用自己 set 时间；</li>
 *     <li><b>索引利用</b>：按天查必须走 {@code attendance_date} 列（冗余列的目的就在这里），
 *     不要写成 {@code DATE(check_in_at)}——函数会让索引失效。</li>
 * </ul>
 *
 * <p>注意：本表<b>没有更新与删除</b>的数据访问方法，也不应该有——
 * 签到是审计凭证，写进去就不再改动（应用层不提供入口）。
 */
@Mapper
public interface StudioAttendanceMapper extends BaseMapper<StudioAttendance> {
}
