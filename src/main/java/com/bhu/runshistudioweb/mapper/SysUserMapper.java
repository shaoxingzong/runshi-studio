package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user 表的数据访问层
 *
 * @author shaoshing
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
