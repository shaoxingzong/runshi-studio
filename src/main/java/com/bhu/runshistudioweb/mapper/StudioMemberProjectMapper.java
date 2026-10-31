package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_member_project 表的数据访问层（成员-项目关联）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioMemberProjectMapper extends BaseMapper<StudioMemberProject> {
}
