package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_member_certificate 表的数据访问层（成员-证书关联）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioMemberCertificateMapper extends BaseMapper<StudioMemberCertificate> {
}
