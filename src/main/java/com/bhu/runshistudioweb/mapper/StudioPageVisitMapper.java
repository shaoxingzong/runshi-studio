package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_page_visit 表的数据访问层（页面访问记录）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPageVisitMapper extends BaseMapper<StudioPageVisit> {
}
