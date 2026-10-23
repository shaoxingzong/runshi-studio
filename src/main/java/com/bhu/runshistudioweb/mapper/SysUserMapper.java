package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

// sys_user 数据访问层
//
// 继承 BaseMapper 即自动获得单表 CRUD（insert / deleteById / updateById / selectById /
// selectList / selectPage 等），因此这里不需要写任何方法；只有多表关联、复杂动态条件
// 才在此声明方法并配合 XML 或注解实现。
//
// 使用时的三个关键点：
// - 逻辑删除自动生效：因为 SysUser 有 @TableLogic 字段，
// deleteById 实际执行 UPDATE， selectById 会自动追加 deleted_at = 0，
// 手写 SQL 时也必须自己带上该条件（关联查询尤其容易漏）；
// - 审计字段自动填充：insert / update 会触发
// com.bhu.runshistudioweb.config.MyMetaObjectHandler，不用自己 set 时间；
// - 分页：必须用 selectPage，依赖 MyBatis-Plus 的分页拦截器
// （见 com.bhu.runshistudioweb.config.MyBatisPlusConfig），自己 new Page 不会自动分页。
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
