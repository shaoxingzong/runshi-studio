package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user 表的数据访问层（Mapper）
 *
 * <p>为什么这个接口是空的：
 * 它继承了 MyBatis-Plus 的 BaseMapper，父接口里已经提供了
 * insert / deleteById / updateById / selectById / selectList / selectPage 等方法，
 * 单表增删改查一行 SQL 都不用写。只有「多表关联」或「复杂动态条件」才需要在这里声明方法。
 *
 * <p>初学者最容易困惑的三件事：
 * <ul>
 *     <li><b>逻辑删除是自动的</b>：实体上有 @TableLogic 字段 deleted_at，
 *     所以 selectList 会自动追加 {@code WHERE deleted_at = 0}，
 *     deleteById 实际执行的是 UPDATE。一旦自己写 SQL（@Select），
 *     这个条件就<b>不会</b>自动加，必须手写，否则会查出已删除的数据；</li>
 *     <li><b>审计字段是自动的</b>：insert / update 时会触发
 *     com.bhu.runshistudioweb.config.MyMetaObjectHandler，
 *     自动填 createdAt / updatedAt / createdBy / updatedBy，不用自己 set；</li>
 *     <li><b>分页必须走 selectPage</b>：自己 new 一个 Page 对象传进普通查询是<b>不会</b>分页的，
 *     必须调用 selectPage，因为分页靠的是拦截器
 *     （com.bhu.runshistudioweb.config.MyBatisPlusConfig 里注册的 PaginationInnerInterceptor）。</li>
 * </ul>
 *
 * <p>关于 @Mapper 注解：MyBatisPlusConfig 已经用 @MapperScan 批量扫描了本包，
 * 所以这个注解其实是重复声明。保留它只是为了让每个接口自己就能被单独识别
 * （在 IDE 里跳转、单独写测试时更直观）。
 *
 * @author shaoshing
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
