package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_project 表的数据访问层（项目案例）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioProjectMapper extends BaseMapper<StudioProject> {
}
