package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 成员-证书关联数据访问层（对应 studio_member_certificate）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，这里**不需要写任何方法**：
 * 本模块的查询采用「两步查询」（先取关联 ID、再 IN 查主表），两步都用 MP 的
 * LambdaQueryWrapper 拼装，不需要自定义 SQL，也就不需要 XML。
 *
 * <p><b>本表是物理删除，这一点会影响手写 SQL</b>：如果将来在这里加自定义查询，
 * 记得它<b>没有</b> {@code deleted_at} 条件可用（与其它 Mapper 的习惯不同），
 * 而关联到的 studio_member / studio_certificate 两张主表是逻辑删除——
 * 它们的 {@code deleted_at = 0} 由 MP 自动追加，手写 JOIN 时必须自己带上，
 * 否则会查出「已删除成员」的悬空数据。
 */
@Mapper
public interface StudioMemberCertificateMapper extends BaseMapper<StudioMemberCertificate> {
}
