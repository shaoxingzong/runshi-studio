package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 荣誉证书数据访问层（对应 studio_certificate）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD（insert / deleteById / updateById /
 * selectById / selectList / selectPage 等），因此这里**不需要写任何方法**；
 * 只有多表关联、复杂动态条件才在此声明方法并配合 XML 或注解实现。
 *
 * <p>使用时注意三件事：
 * <ul>
 *     <li><b>逻辑删除自动生效</b>：实体上有 {@code @TableLogic} 的 {@code deletedAt}，
 *     {@code deleteById} 实际执行 UPDATE，{@code selectById} 会自动追加 {@code deleted_at = 0}，
 *     手写 SQL 时必须自己带上该条件；</li>
 *     <li><b>审计字段自动填充</b>：insert / update 会触发 {@code MyMetaObjectHandler}，
 *     不用自己 set 时间；</li>
 *     <li><b>分页</b>：必须用 {@code selectPage}，依赖 MyBatis-Plus 的分页拦截器；
 *     自己 new Page 不会自动分页。</li>
 * </ul>
 *
 * <p>{@code @MapperScan} 已在 MyBatisPlusConfig 中批量扫描本包，
 * 这里的 {@code @Mapper} 属于重复声明（保留或去掉都可以，建议二选一）。
 */
@Mapper
public interface StudioCertificateMapper extends BaseMapper<StudioCertificate> {
}
