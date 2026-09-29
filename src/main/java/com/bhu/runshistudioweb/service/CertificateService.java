package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateAddRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateQueryRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.vo.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.CertificateVO;

import java.util.List;

/**
 * 荣誉证书服务接口
 *
 * author: shaoshing
 *
 * <p>与 UserService、StudioMemberService 保持同一套约定：
 * <ul>
 *     <li>方法分「管理端（需 admin）」与「C 端（匿名）」两组，职责不混用；</li>
 *     <li>参数不合法 / 目标不存在时**抛 BusinessException**，不返回错误码、不返回 null，
 *     由全局异常处理器统一转成标准响应
 *     （例外：两个 VO 转换方法是纯转换，入参为 null 时返回 null）；</li>
 *     <li><b>不暴露 {@code QueryWrapper}</b>：查询条件的拼装是实现细节，
 *     放到接口上等于允许调用方自己拼 SQL，会把「排序字段改成注入字符串」这类风险扩散出去。</li>
 * </ul>
 */
public interface CertificateService extends IService<StudioCertificate> {

    // ==================== 管理端：调用方必须已通过 @SaCheckRole ====================

    /**
     * 新增证书
     *
     * <p>校验点（对应本模块四个考点）：标题非空、级别与类型必须是枚举内的合法取值、
     * 获奖日期非空且不晚于今天、证书图片 URL 非空（DDL 里该列 NOT NULL）。
     *
     * @param certificateAddRequest 新增请求
     * @return 新证书 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法时抛出（40000）
     */
    long addCertificate(CertificateAddRequest certificateAddRequest);

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * @param certificateUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 证书不存在（40400）或参数非法（40000）时抛出
     */
    boolean updateCertificate(CertificateUpdateRequest certificateUpdateRequest);

    /**
     * 删除证书（逻辑删除）
     *
     * @param id 证书 ID
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 证书不存在时抛出（40400）
     */
    boolean deleteCertificate(long id);

    /**
     * 按 id 查证书详情（返回管理端 VO）
     *
     * @param id 证书 ID
     * @return 证书信息；不存在时抛 40400，不返回 null
     */
    CertificateVO getCertificateById(long id);

    /**
     * 管理端分页查询
     *
     * <p>分页与排序参数的非法值由实现侧兜底纠正，调用方无需预处理。
     *
     * @param certificateQueryRequest 查询条件，允许为 null（无条件查第一页）
     * @return 分页结果，记录为管理端 VO
     */
    Page<CertificateVO> listCertificateByPage(CertificateQueryRequest certificateQueryRequest);

    // ==================== C 端：匿名可访问 ====================

    /**
     * C 端证书列表（官网「荣誉墙」，匿名可访问）
     *
     * <p>返回的是**脱敏后的** C 端 VO：不含置顶权重与审计字段。
     * 实现侧固定排序、并强制返回条数上限。
     *
     * @param certificateQueryRequest 查询条件，允许为 null
     * @return 脱敏后的证书列表
     */
    List<CertificateFrontVO> listFrontCertificates(CertificateQueryRequest certificateQueryRequest);

    // ==================== VO 转换 ====================

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
