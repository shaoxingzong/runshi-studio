package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_member 数据访问层
 *
 * 继承 BaseMapper 即自动获得单表 CRUD，因此这里不需要写任何方法；
 * 只有多表关联（如「按成员查证书」）、复杂动态条件才在此声明方法并配合 XML 实现。
 *
 * 三个与 SysUserMapper 相同的使用要点（照抄一遍，因为每条都踩过坑）：
 * - 逻辑删除自动生效：StudioMember 有 @TableLogic 字段，
 * 查询自动追加 deleted_at = 0，删除是 UPDATE；手写 SQL 时必须自己带该条件；
 * - 审计字段自动填充：insert / update 触发
 * com.bhu.runshistudioweb.config.MyMetaObjectHandler，不用自己 set 时间；
 * - 分页：必须走 selectPage（分页拦截器见
 * com.bhu.runshistudioweb.config.MyBatisPlusConfig），自己 new Page 不会分页。
 *
 * 注意：@MapperScan 已在 MyBatisPlusConfig 中批量扫描本包，
 * 这里的 @Mapper 属于重复声明，与 SysUserMapper 保持一致保留。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioMemberMapper extends BaseMapper<StudioMember> {
}