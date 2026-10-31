package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_member 表的数据访问层（成员档案）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioMemberMapper extends BaseMapper<StudioMember> {
}
