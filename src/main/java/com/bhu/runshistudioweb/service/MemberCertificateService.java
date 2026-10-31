package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;

import java.util.List;

/**
 * 成员-证书关联服务接口
 *
 * @author shaoshing
 */
public interface MemberCertificateService extends IService<StudioMemberCertificate> {

    // 写操作：管理端调用

    /**
     * 绑定成员与证书
     *
     * 应用层查重负责可读提示，唯一索引负责保证不出现脏数据，两者职责不同、不能互相替代。
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示绑定成功
     * @throws BusinessException 参数非法或已绑定（A0401）、目标不存在（A0402）
     */
    boolean bind(long memberId, long certificateId);

    /**
     * 解绑成员与证书（物理删除关联行）
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws BusinessException 参数非法（A0401）、关系不存在（A0402）
     */
    boolean unbind(long memberId, long certificateId);

    // 读操作

    /**
     * 查某成员持有的全部证书（管理端视图，含图片 / 权重 / 审计字段）
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 证书列表（按 sort_order → award_date → id 倒序）；无证书时返回空列表
     * @throws BusinessException 成员不存在时抛 A0402
     */
    List<CertificateVO> listCertificatesByMember(long memberId);

    /**
     * 查某证书关联的全部成员（管理端视图）
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序）；无成员时返回空列表
     * @throws BusinessException 证书不存在时抛 A0402
     */
    List<MemberVO> listMembersByCertificate(long certificateId);

    // 级联清理：仅供主表 delete 在同一事务内调用

    /**
     * 清理某成员的全部关联关系（供 StudioMemberServiceImpl.deleteMember 调用）
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 清理的行数；0 不代表失败，只表示该成员本来就没有绑定任何证书
     */
    long removeByMemberId(long memberId);

    /**
     * 清理某证书的全部关联关系（供 CertificateServiceImpl.deleteCertificate 调用）
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 清理的行数；0 表示这张证书本来就没有关联成员
     */
    long removeByCertificateId(long certificateId);
}
