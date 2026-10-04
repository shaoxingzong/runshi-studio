package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.vo.CertificateVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;

import java.util.List;

/**
 * 成员-证书关联服务接口
 *
 * author: shaoshing
 *
 * <p>与 {@link com.bhu.runshistudioweb.service.StudioMemberService}、
 * {@link CertificateService} 保持同一套约定：参数不合法 / 目标不存在时抛
 * {@code BusinessException}，不返回错误码、不返回 null。
 *
 * <p>方法分三组：
 * <ol>
 *     <li><b>写操作</b>：{@link #bind}、{@link #unbind}——维护关联关系；</li>
 *     <li><b>读操作</b>：三个列表接口，分别从「成员」与「证书」两端查询，
 *     C 端与管理端各返回各自的 VO（脱敏边界与其它模块一致）；</li>
 *     <li><b>级联清理</b>：{@link #removeByMemberId}、{@link #removeByCertificateId}——
 *     供主表删除时调用，<b>不对外暴露接口</b>，避免被当成独立的删除入口滥用。</li>
 * </ol>
 *
 * <p><b>依赖方向铁律（本模块最重要的一条）</b>：本类只被
 * {@code StudioMemberServiceImpl} / {@code CertificateServiceImpl} 依赖（级联清理），
 * 而自己的实现<b>只准注入 Mapper</b>，绝不能反过来注入那两个 Service——
 * 否则构成循环依赖，Spring Boot 默认禁止循环引用，**启动即报错**（不是偶发运行问题）。
 * 代价是 VO 转换要在本模块内各写几行 {@code BeanUtils.copyProperties}，
 * 这个代价远小于循环依赖带来的启动失败与架构混乱。
 */
public interface MemberCertificateService extends IService<StudioMemberCertificate> {

    // ==================== 写操作：管理端调用 ====================

    /**
     * 绑定成员与证书
     *
     * <p>校验顺序（每一步都有明确错误码）：
     * <ol>
     *     <li>两个 id 必须 &gt; 0 → A0401；</li>
     *     <li>成员、证书都必须存在（{@code selectById} 自动过滤 {@code deleted_at}）→ A0402；</li>
     *     <li>已绑定过 → A0401「该成员已绑定此证书」；</li>
     *     <li>插入。并发下两条请求同时通过第 3 步时，由唯一索引
     *     {@code uk_member_cert} 兜底抛 {@code DuplicateKeyException}，
     *     实现里 catch 后返回**同一文案**（应用层管友好提示，索引管绝对不出现脏数据）。</li>
     * </ol>
     *
     * @param memberId      成员 ID
     * @param certificateId 证书 ID
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（A0401）或目标不存在（A0402）时抛出
     */
    boolean bind(long memberId, long certificateId);

    /**
     * 解绑成员与证书（物理删除关联行）
     *
     * @param memberId      成员 ID
     * @param certificateId 证书 ID
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法（A0401）或关系不存在（A0402）时抛出
     */
    boolean unbind(long memberId, long certificateId);

    // ==================== 读操作 ====================

    /**
     * 查某成员持有的全部证书（管理端视图，含图片/权重/审计字段）
     *
     * @param memberId 成员 ID
     * @return 证书列表（按 sort_order → award_date → id 倒序）；成员不存在时抛 A0402
     */
    List<CertificateVO> listCertificatesByMember(long memberId);

    /**
     * 查某证书关联的全部成员（管理端视图）
     *
     * <p>反向查询，走 {@code idx_cert_member (certificate_id, member_id)} 索引。
     *
     * @param certificateId 证书 ID
     * @return 成员列表（按 sort_order → id 倒序）；证书不存在时抛 A0402
     */
    List<MemberVO> listMembersByCertificate(long certificateId);

    // 原 listFrontCertificatesByMember(long)（C 端「某成员的证书」，供匿名接口调用）
    // 已随「团队成员不对外展示」删除：它以成员为入口、只服务于成员详情页，
    // 成员页下线后不再有任何调用方。证书对外展示走 CertificateService.listFrontCertificates
    // ——那条是证书自身维度，不含成员信息。

    // ==================== 级联清理：仅供主表 delete 在同一事务内调用 ====================

    /**
     * 清理某成员的全部关联关系（供 {@code StudioMemberServiceImpl.deleteMember} 调用）
     *
     * <p>物理删除：关联表没有 {@code deleted_at}，{@code remove(wrapper)} 就是 DELETE。
     *
     * @param memberId 成员 ID
     * @return 清理的行数（0 表示该成员本来就没有关联）
     */
    long removeByMemberId(long memberId);

    /**
     * 清理某证书的全部关联关系（供 {@code CertificateServiceImpl.deleteCertificate} 调用）
     *
     * @param certificateId 证书 ID
     * @return 清理的行数
     */
    long removeByCertificateId(long certificateId);
}
