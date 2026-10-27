package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_attendance 表的数据访问层（签到记录）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：按天查必须走 attendance_date 冗余列，写成 DATE(check_in_at) 会让索引失效。
 * 本表只增不改：签到是审计凭证，应用层不提供更新与删除入口。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAttendanceMapper extends BaseMapper<StudioAttendance> {
}
