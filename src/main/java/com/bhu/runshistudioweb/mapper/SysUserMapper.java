package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD（insert / deleteById / updateById / selectById /
 * selectList / selectPage 等），因此这里不需要写任何方法；只有多表关联、复杂动态条件
 * 才在此声明方法并配合 XML 或注解实现。
 *
 * <p>使用时的三个关键点：
 * <ul>
 *     <li><b>逻辑删除自动生效</b>：因为 {@link SysUser} 有 {@code @TableLogic} 字段，
 *     {@code deleteById} 实际执行 UPDATE，{@code selectById} 会自动追加 {@code deleted_at = 0}，
 *     手写 SQL 时也必须自己带上该条件（关联查询尤其容易漏）；</li>
 *     <li><b>审计字段自动填充</b>：insert / update 会触发
 *     {@link com.bhu.runshistudioweb.config.MyMetaObjectHandler}，不用自己 set 时间；</li>
 *     <li><b>分页</b>：必须用 {@code selectPage}，依赖 MyBatis-Plus 的分页拦截器
 *     （见 {@link com.bhu.runshistudioweb.config.MyBatisPlusConfig}），自己 new Page 不会自动分页。</li>
 * </ul>
 *
 * <p>注意：{@code @MapperScan} 已在 MyBatisPlusConfig 中批量扫描本包，
 * 这里的 {@code @Mapper} 属于重复声明，保留或去掉都可以（建议二选一，避免两处维护）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
