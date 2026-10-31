package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_attendance 表的数据访问层（签到记录）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAttendanceMapper extends BaseMapper<StudioAttendance> {
}
