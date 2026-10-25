package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;

import java.util.List;

/**
 * 成员-证书关联服务接口
 *
 * 与 StudioMemberService、CertificateService 保持同一套约定：
 * 参数不合法 / 目标不存在时抛 BusinessException，不返回错误码、不返回 null。
 *
 * 方法分三组：
 * - 写操作：bind、unbind——维护关联关系；
 * - 读操作：两个列表接口，分别从「成员」与「证书」两端查询，
 * 管理端返回带内部字段的 VO；
 * - 级联清理：removeByMemberId、removeByCertificateId——
 * 供主表删除时调用，不对外暴露接口，避免被当成独立的删除入口滥用。
 *
 * 依赖方向铁律（本模块最重要的一条）：本接口被
 * StudioMemberServiceImpl / CertificateServiceImpl 依赖（级联清理），
 * 而它自己的实现只准注入 Mapper，绝不能反过来注入那两个 Service——
 * 否则构成循环依赖，Spring Boot 默认禁止循环引用，启动即报错
 * （不是偶发运行问题，而是每次启动都失败）。
 * 代价是 VO 转换要在本模块内各写几行 BeanUtils.copyProperties，
 * 这个代价远小于循环依赖带来的启动失败与架构混乱。
 *
 * @author shaoshing
 */
public interface MemberCertificateService extends IService<StudioMemberCertificate> {

    // ==================== 写操作：管理端调用 ====================

    /**
     * 绑定成员与证书
     *
     * <p>为什么这样设计：每一步都有明确错误码，是为了让管理员知道「到底哪一步不对」。
     * 两个 id 必须大于 0 属于格式兜底；成员、证书都必须存在属于引用完整性；
     * 已绑定过则要给出可读提示而不是让唯一索引抛英文异常。
     * 应用层查重管友好提示，唯一索引管绝对不出现脏数据——两者职责不同，不能互相替代。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 两个 id 必须大于 0，否则 A0401；</li>
     *     <li>② 成员、证书都必须存在（selectById 自动过滤 deleted_at），否则 A0402；</li>
     *     <li>③ 已绑定过 → A0401「该成员已绑定此证书」；</li>
     *     <li>④ 插入。并发下两条请求同时通过第 ③ 步时，由唯一索引
     *         uk_member_cert 兜底抛 DuplicateKeyException，
     *         实现里 catch 后返回同一文案。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步的查询不能当作并发保证。两个请求可能同时查到
     * 「不存在」再同时插入，所以第 ④ 步的 catch 必须保留，且文案要与第 ③ 步一致。
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法或已绑定时抛出（A0401）；
     * 目标不存在时抛出（A0402）
     */
    boolean bind(long memberId, long certificateId);

    /**
     * 解绑成员与证书（物理删除关联行）
     *
     * <p>为什么这样设计：关联表没有 deleted_at 列，也不该有。
     * 若改成逻辑删除，唯一索引 uk_member_cert 会被已删除的行占用，
     * 「先解绑、再重新绑定同一张证书」这个正常操作会一直冲突——
     * 这是关联表必须物理删除最实际的理由。
     * 另外，先查关系是否存在再删：不存在时给 A0402，
     * 而不是返回 false 让前端去猜「是没删掉，还是本来就没有」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 两个 id 必须大于 0；</li>
     *     <li>② 先确认关联存在，不存在给 A0402；</li>
     *     <li>③ 物理删除关联行。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回 true 只代表 DELETE 执行成功。
     * 如果把它当成「这条关系之前一定存在」，就会掩盖掉数据被并发删除的情况；
     * 因此第 ② 步的存在性检查不能省。
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数非法时抛出（A0401）；
     * 关系不存在时抛出（A0402）
     */
    boolean unbind(long memberId, long certificateId);

    // ==================== 读操作 ====================

    /**
     * 查某成员持有的全部证书（管理端视图，含图片 / 权重 / 审计字段）
     *
     * <p>为什么这样设计：用两步查询而不是 JOIN。先从关联表取 ID（覆盖索引、不回表），
     * 再 IN 查主表——主表查询会自动追加 deleted_at = 0，
     * 「证书已被删」的悬空关联被天然过滤。手写 JOIN 没有这层自动保护，
     * 一旦漏写条件就会查出已删除的证书。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验成员存在，不存在给 A0402；</li>
     *     <li>③ 两步查询取证书，再转管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序三键（sort_order → award_date → id）必须与证书模块
     * 的默认排序一致，否则「后台证书列表」与「成员详情里的证书」顺序不同，
     * 运营会以为是数据错乱。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 证书列表（按 sort_order → award_date → id 倒序）；无证书时返回空列表；
     * 成员不存在时抛 A0402
     */
    List<CertificateVO> listCertificatesByMember(long memberId);

    /**
     * 查某证书关联的全部成员（管理端视图）
     *
     * <p>为什么这样设计：反向查询走 idx_cert_member (certificate_id, member_id)，
     * 与正向查询是同一套两步查询思路，只是换了索引方向，不必为反向再加索引。
     * 空集合必须提前返回——拿空集合拼 IN () 是语法错误，MySQL 会直接报错，
     * 而这条路径只在「证书存在但没人绑定」时触发，很容易漏测。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 校验证书存在，不存在给 A0402；</li>
     *     <li>③ 从关联表取 member_id 列表；</li>
     *     <li>④ 空集合提前返回；</li>
     *     <li>⑤ IN 查成员主表并转 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：成员侧没有 award_date，排序只能沿用成员模块的
     * 置顶权重 + id 倒序。硬套证书的三键排序会编译不过，或排出一个无意义的顺序。
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序）；无成员时返回空列表；
     * 证书不存在时抛 A0402
     */
    List<MemberVO> listMembersByCertificate(long certificateId);

    // 这里刻意没有「按成员查证书」的 C 端方法：它以成员为入口、只服务于成员详情页，
    // 与「团队成员不对外展示」的规则冲突。证书对外展示走
    // CertificateService.listFrontCertificates——那条是证书自身维度，不含成员信息。

    // ==================== 级联清理：仅供主表 delete 在同一事务内调用 ====================

    /**
     * 清理某成员的全部关联关系（供 StudioMemberServiceImpl.deleteMember 调用）
     *
     * <p>为什么这样设计：主表被删后，这些关联行继续留着只会占用唯一索引，
     * 让「同一个成员 ID 重新绑定证书」时冲突。所以删成员必须一并清掉。
     * 物理删除：关联表没有 deleted_at，remove(wrapper) 生成的就是 DELETE。
     * 本方法不自己加 @Transactional——它要活在调用方的事务里，
     * 「清关联 + 删主表」才能作为一个整体提交或回滚。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 按 member_id 物理删除，返回行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回 0 不代表失败，只代表该成员本来就没有绑定任何证书。
     * 调用方不要把它当成「清理失败」去抛异常。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 清理的行数（0 表示该成员本来就没有关联）
     */
    long removeByMemberId(long memberId);

    /**
     * 清理某证书的全部关联关系（供 CertificateServiceImpl.deleteCertificate 调用）
     *
     * <p>为什么这样设计：与 removeByMemberId 完全对称。删证书时必须先清掉
     * 「谁持有它」的关系，否则会留下指向已删除证书的悬空关联。
     * 同样必须在调用方的事务内执行，且顺序排在删主表之前。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 按 certificate_id 物理删除，返回行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：顺序反了（先删主表、后清关联）时，第二步失败不会回滚第一步，
     * 悬空关联就留下来了，而且没有任何报错。
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 清理的行数（0 表示这张证书本来就没有关联成员）
     */
    long removeByCertificateId(long certificateId);
}
