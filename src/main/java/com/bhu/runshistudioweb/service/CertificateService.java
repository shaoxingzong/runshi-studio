package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateAddRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateQueryRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;

import java.util.List;

/**
 * 荣誉证书服务接口
 *
 * @author shaoshing
 */
public interface CertificateService extends IService<StudioCertificate> {

    // 管理端：调用方必须已通过 @SaCheckRole

    /**
     * 新增证书
     *
     * @param certificateAddRequest 新增请求，不允许为 null；五项必填
     * @return 新证书 ID（19 位 Long，接口层序列化成字符串）
     * @throws BusinessException 参数不合法时抛出（A0401）
     */
    long addCertificate(CertificateAddRequest certificateAddRequest);

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * @param certificateUpdateRequest 更新请求，不允许为 null；id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功；证书不存在时抛异常而不是返回 false
     * @throws BusinessException 证书不存在（A0402）或参数非法（A0401）
     */
    boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest);

    /**
     * 删除证书（逻辑删除主表 + 物理清理成员-证书关联）
     *
     * @param id 证书 ID，必须大于 0
     * @return true 表示删除成功；只代表 UPDATE 命中了行，数据并没有从库里消失
     * @throws BusinessException 证书不存在时抛出（A0402）
     */
    boolean deleteCertificate(long id);

    /**
     * 按 id 查证书详情（返回管理端 VO）
     *
     * @param id 证书 ID，必须大于 0
     * @return 证书信息（全字段）；不存在时抛 A0402，不返回 null
     */
    CertificateVO getCertificateById(long id);

    /**
     * 管理端分页查询
     *
     * @param certificateQueryRequest 查询条件，允许为 null（无条件查第一页）
     * @return 分页结果，记录为管理端 VO
     */
    Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest);

    // C 端：匿名可访问

    /**
     * C 端证书列表（官网「荣誉墙」，匿名可访问）
     *
     * @param certificateQueryRequest 查询条件，允许为 null
     * @return 脱敏后的证书列表，最多 200 条；无数据时返回空列表而不是 null
     */
    List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest);

    // VO 转换

    /**
     * 实体转管理端 VO（字段齐全，含置顶权重与审计时间）
     *
     * @param certificate 证书实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    CertificateVO getCertificateVO(StudioCertificate certificate);

    /**
     * 实体转 C 端展示 VO（只保留官网展示字段）
     *
     * @param certificate 证书实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    CertificateFrontVO getCertificateFrontVO(StudioCertificate certificate);
}
