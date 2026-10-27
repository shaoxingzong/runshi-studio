package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;

import java.util.List;

/**
 * 成员-证书关联服务接口
 *
 * 参数不合法 / 目标不存在时抛 BusinessException，不返回错误码、不返回 null。
 *
 * 依赖方向铁律：本接口被 StudioMemberServiceImpl / CertificateServiceImpl 依赖（级联清理），
 * 而它自己的实现只准注入 Mapper，绝不能反过来注入那两个 Service——否则构成循环依赖，
 * Spring Boot 默认禁止循环引用，每次启动都会失败。代价是 VO 转换要在本模块内各写几行。
 *
 * 级联清理方法（removeByMemberId / removeByCertificateId）供主表删除时调用，
 * 不对外暴露成独立接口。
 *
 * @author shaoshing
 */
public interface MemberCertificateService extends IService<StudioMemberCertificate> {

    // ==================== 写操作：管理端调用 ====================

    /**
     * 绑定成员与证书
     *
     * 应用层查重负责可读提示，唯一索引负责保证不出现脏数据，两者职责不同、不能互相替代。
     *
     * 坑：查重那一步不能当作并发保证。两个请求可能同时查到「不存在」再同时插入，
     * 因此必须保留对 DuplicateKeyException 的捕获，且文案要与查重时一致。
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
     * 关联表没有 deleted_at，也不该有：若改成逻辑删除，唯一索引 uk_member_cert 会被已删除的行占用，
     * 「先解绑、再重新绑定同一张证书」这个正常操作会一直冲突。
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws BusinessException 参数非法（A0401）、关系不存在（A0402）
     */
    boolean unbind(long memberId, long certificateId);

    // ==================== 读操作 ====================

    /**
     * 查某成员持有的全部证书（管理端视图，含图片 / 权重 / 审计字段）
     *
     * 用两步查询而不是 JOIN：先从关联表取 ID，再 IN 查主表，主表查询会自动追加
     * deleted_at = 0，「证书已被删」的悬空关联被天然过滤。手写 JOIN 没有这层保护。
     *
     * 坑：排序三键（sort_order → award_date → id）必须与证书模块的默认排序一致，
     * 否则「后台证书列表」与「成员详情里的证书」顺序不同，运营会以为是数据错乱。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 证书列表（按 sort_order → award_date → id 倒序）；无证书时返回空列表
     * @throws BusinessException 成员不存在时抛 A0402
     */
    List<CertificateVO> listCertificatesByMember(long memberId);

    /**
     * 查某证书关联的全部成员（管理端视图）
     *
     * 反向查询走 idx_cert_member，与正向同一套两步查询思路，只是换了索引方向。
     *
     * 坑：空集合必须提前返回——拿空集合拼 {@code IN ()} 是 SQL 语法错误，
     * 而这条路径只在「证书存在但没人绑定」时触发，很容易漏测。
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序）；无成员时返回空列表
     * @throws BusinessException 证书不存在时抛 A0402
     */
    List<MemberVO> listMembersByCertificate(long certificateId);

    // 这里刻意没有「按成员查证书」的 C 端方法：它以成员为入口、只服务于成员详情页，
    // 与「团队成员不对外展示」的规则冲突。证书对外展示走
    // CertificateService.listFrontCertificates——那条是证书自身维度，不含成员信息。

    // ==================== 级联清理：仅供主表 delete 在同一事务内调用 ====================

    /**
     * 清理某成员的全部关联关系（供 StudioMemberServiceImpl.deleteMember 调用）
     *
     * 本方法不自己加 @Transactional——它要活在调用方的事务里，
     * 「清关联 + 删主表」才能作为一个整体提交或回滚。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 清理的行数；0 不代表失败，只表示该成员本来就没有绑定任何证书
     */
    long removeByMemberId(long memberId);

    /**
     * 清理某证书的全部关联关系（供 CertificateServiceImpl.deleteCertificate 调用）
     *
     * 与 removeByMemberId 完全对称：必须在调用方的事务内执行，且顺序排在删主表之前。
     * 顺序反了的话，第二步失败不会回滚第一步，悬空关联就留下来了且没有报错。
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 清理的行数；0 表示这张证书本来就没有关联成员
     */
    long removeByCertificateId(long certificateId);
}
