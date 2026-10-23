package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import org.apache.ibatis.annotations.Mapper;

// 荣誉证书数据访问层（对应 studio_certificate）
//
// 继承 BaseMapper 即自动获得单表 CRUD（insert / deleteById / updateById /
// selectById / selectList / selectPage 等），因此这里不需要写任何方法；
// 只有多表关联、复杂动态条件才在此声明方法并配合 XML 或注解实现。
//
// 使用时注意三件事：
// - 逻辑删除自动生效：实体上有 @TableLogic 的 deletedAt，
// deleteById 实际执行 UPDATE， selectById 会自动追加 deleted_at = 0，
// 手写 SQL 时必须自己带上该条件；
// - 审计字段自动填充：insert / update 会触发 MyMetaObjectHandler，
// 不用自己 set 时间；
// - 分页：必须用 selectPage，依赖 MyBatis-Plus 的分页拦截器；
// 自己 new Page 不会自动分页。
//
// @MapperScan 已在 MyBatisPlusConfig 中批量扫描本包，
// 这里的 @Mapper 属于重复声明（保留或去掉都可以，建议二选一）。
@Mapper
public interface StudioCertificateMapper extends BaseMapper<StudioCertificate> {
}
