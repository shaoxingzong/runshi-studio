package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_report 表的数据访问层（内容举报）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostReportMapper extends BaseMapper<StudioPostReport> {
}
