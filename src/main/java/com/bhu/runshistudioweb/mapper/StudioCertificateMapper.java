package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_certificate 表的数据访问层（荣誉证书）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioCertificateMapper extends BaseMapper<StudioCertificate> {
}
