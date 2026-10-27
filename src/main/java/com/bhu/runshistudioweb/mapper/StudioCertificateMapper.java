package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_certificate 表的数据访问层（荣誉证书）
 *
 * 继承 BaseMapper 即得单表 CRUD（insert / deleteById / updateById / selectById /
 * selectList / selectPage 等），故接口为空；只有多表关联、复杂动态条件才在此声明方法。
 * 三个自动行为只对 MP 方法生效：逻辑删除（deleteById 实为 UPDATE）、审计填充、
 * 分页须走 selectPage。自己写 @Select 时这三样都要手工补。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioCertificateMapper extends BaseMapper<StudioCertificate> {
}
