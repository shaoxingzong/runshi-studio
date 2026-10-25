package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_attendance 数据访问层
 *
 * 继承 BaseMapper 即自动获得单表 CRUD，因此这里不需要写任何方法。
 * 出勤看板需要的「按天统计」「按用户分组计数」都由 ServiceImpl
 * 的 listMaps + QueryWrapper.groupBy 完成，不必引入 XML。
 *
 * 与 SysUserMapper 相同的三条要点（每条都踩过坑，故重复说明）：
 * - 逻辑删除自动生效：StudioAttendance 有 @TableLogic 字段，
 * 查询自动追加 deleted_at = 0；手写 SQL 时必须自己带该条件；
 * - 审计字段自动填充：insert 触发
 * com.bhu.runshistudioweb.config.MyMetaObjectHandler，不用自己 set 时间；
 * - 索引利用：按天查必须走 attendance_date 列（冗余列的目的就在这里），
 * 不要写成 DATE(check_in_at)——函数会让索引失效。
 *
 * 注意：本表没有更新与删除的数据访问方法，也不应该有——
 * 签到是审计凭证，写进去就不再改动（应用层不提供入口）。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAttendanceMapper extends BaseMapper<StudioAttendance> {
}
