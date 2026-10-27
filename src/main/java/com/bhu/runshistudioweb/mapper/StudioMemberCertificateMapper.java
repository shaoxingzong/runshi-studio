package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_member_certificate 表的数据访问层（成员-证书关联）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：本表是物理删除、没有 deleted_at 列，而它关联的成员与证书表是逻辑删除，
 * 手写 JOIN 时必须自己带上 deleted_at = 0，否则会查出已删除成员的悬空数据。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioMemberCertificateMapper extends BaseMapper<StudioMemberCertificate> {
}
