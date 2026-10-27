package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user 表的数据访问层
 *
 * 继承 BaseMapper 即得单表 CRUD（insert / deleteById / updateById / selectById /
 * selectList / selectPage 等），故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 {@code deleted_at = 0}、deleteById 实为 UPDATE）、审计填充、分页须走
 * selectPage——自己 new 一个 Page 传进普通查询不会分页。
 *
 * @author shaoshing
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
