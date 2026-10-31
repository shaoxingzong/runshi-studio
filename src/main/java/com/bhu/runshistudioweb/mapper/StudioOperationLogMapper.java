package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioOperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_operation_log 表的数据访问层（管理端操作日志）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioOperationLogMapper extends BaseMapper<StudioOperationLog> {
}
