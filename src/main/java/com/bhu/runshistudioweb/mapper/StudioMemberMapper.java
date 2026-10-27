package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_member 表的数据访问层（成员档案）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：成员身份的唯一权威源是本表有没有他 user_id 的档案，不是 sys_user.user_role——
 * 角色只是权限标签，可能与档案不同步。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioMemberMapper extends BaseMapper<StudioMember> {
}
