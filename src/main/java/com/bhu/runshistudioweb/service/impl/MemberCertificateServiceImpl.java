package com.bhu.runshistudioweb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberCertificateMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 成员-证书关联服务实现
 *
 * 依赖方向铁律：本类只注入 Mapper，绝不注入 StudioMemberService / CertificateService——
 * 它们反过来依赖本类做级联清理，再依赖回去就是循环依赖，启动直接失败。
 *
 * 查询用两步而不是 JOIN：手写 SQL 多一层「漏写 deleted_at = 0」的风险，
 * 而两步查询的第二步由 MyBatis-Plus 自动追加该条件，悬空关联被天然过滤。
 *
 * 排序三键：sort_order 倒序 → award_date 倒序 → id 倒序，与证书模块一致。
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class MemberCertificateServiceImpl extends ServiceImpl<StudioMemberCertificateMapper, StudioMemberCertificate>
        implements MemberCertificateService {

    /** 只注入 Mapper，不注入主表 Service（原因见类注释的「依赖方向铁律」） */
    private final StudioMemberMapper studioMemberMapper;

    private final StudioCertificateMapper studioCertificateMapper;

    // ==================== 写操作 ====================

    /**
     * 绑定成员与证书（管理员显式动作）
     *
     * 两次防御缺一不可：先 count 查重是为了给出可读提示，单靠唯一索引的话报错是英文 SQLException，
     * 落到全局兜底后变成 B0001；catch DuplicateKeyException 是为了兜住并发竞态。
     * 应用层管友好提示，索引管「绝对不出现脏数据」，两者不能互相替代。
     *
     * 坑：catch 处的文案必须与查重处的完全一致——对调用方而言，「先查出来的重复」与
     * 「并发撞上唯一索引的重复」是同一件事。
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示绑定成功
     * @throws BusinessException 参数非法或已绑定抛 A0401，成员 / 证书不存在抛 A0402
     */
    @Override
    public boolean bind(long memberId, long certificateId) {
        // ① 两个 id 都必须是正数：雪花 ID 恒为正，0 / 负数一定是调用方传错或探测请求
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");

        // ② 存在性校验：selectById 会被 MP 自动追加 deleted_at = 0，
        // 因此「已逻辑删除的成员 / 证书」在这里同样按不存在处理
        assertMemberExists(memberId);
        assertCertificateExists(certificateId);

        // ③ 应用层查重：先查一次，命中就给出可读提示（唯一索引冲突的报错是英文 SQLException，不能给前端）
        ThrowUtils.throwIf(this.count(relationWrapper(memberId, certificateId)) > 0,
                ErrorCode.PARAMS_ERROR, "该成员已绑定此证书");

        StudioMemberCertificate relation = new StudioMemberCertificate();
        relation.setMemberId(memberId);
        relation.setCertificateId(certificateId);

        try {
            return this.save(relation);
        } catch (DuplicateKeyException e) {
            // ④ 并发竞态兜底：两条请求同时通过上面的查重时，后到的会被 uk_member_cert 拦下。
            // 这里必须与上面的文案完全一致——对调用方而言，
            // 「先查出来的重复」和「并发下撞上唯一索引的重复」是同一件事，不该有两种提示
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该成员已绑定此证书");
        }
    }

    /**
     * 解绑成员与证书（物理删除：本实体没有 @TableLogic）
     *
     * 先查关系是否存在再删：不存在时要明确返回 A0402「该成员未绑定此证书」，而不是返回 false
     * 让前端去猜「是没删掉，还是本来就没有」——前者要重试或报错，后者只需刷新列表。
     *
     * 坑：关联表用物理删除是刻意的。若加 deleted_at 做逻辑删除，uk_member_cert 会被已删除的行占用，
     * 「先解绑、再重新绑定同一张证书」这个正常操作会一直冲突。
     *
     * @param memberId 成员 ID，必须大于 0
     * @param certificateId 证书 ID，必须大于 0
     * @return true 表示解绑成功
     * @throws BusinessException 参数非法抛 A0401，关系不存在抛 A0402
     */
    @Override
    public boolean unbind(long memberId, long certificateId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");

        // ② 先查关系是否存在：不存在要明确说「未绑定」，而不是返回 false 让前端猜
        boolean exists = this.count(relationWrapper(memberId, certificateId)) > 0;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "该成员未绑定此证书");

        // ③ 物理删除：本实体没有 @TableLogic，remove(wrapper) 生成的是 DELETE 而不是 UPDATE
        return this.remove(relationWrapper(memberId, certificateId));
    }

    // ==================== 读操作 ====================

    /**
     * 查某成员持有的全部证书（管理端视图）
     *
     * 两步查询而非 JOIN：先从关联表取 ID 列表（覆盖索引、不回表），再 IN 查主表，
     * 主表查询会自动追加 deleted_at = 0，「证书已被删」的悬空关联被天然过滤。
     *
     * 坑：返回空列表有两种含义——「该成员确实没有证书」与「关联都在，但证书全被逻辑删除了」，
     * 对前端都是空态，排查数据问题时要知道有第二种可能。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 证书列表（按 sort_order → award_date → id 倒序）；无证书时返回空列表
     * @throws BusinessException id 不合法抛 A0401，成员不存在抛 A0402
     */
    @Override
    public List<CertificateVO> listCertificatesByMember(long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // ② 先校验成员存在：否则「成员不存在」会被伪装成「这个人没有证书」
        assertMemberExists(memberId);

        // ③ 取数逻辑收在私有方法里，保证正向与反向查询用同一套排序与过滤规则
        List<StudioCertificate> certificates = selectCertificatesOfMember(memberId);
        return certificates.stream().map(this::toCertificateVO).toList();
    }

    /**
     * 查某证书关联的全部成员（反向查询，走 idx_cert_member）
     *
     * 与正向查询同一套思路，只是换了索引方向。两步查询直接写在方法里而不抽私有方法，
     * 是因为它只在这一处用到。
     *
     * 坑：空集合必须提前返回——拿空集合拼 IN () 在 MySQL 里是语法错误，整个请求直接 500，
     * 而这条路径只在「证书存在但没人绑定」时触发，测试时很容易漏掉。
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 成员列表（按 sort_order → id 倒序）；无成员时返回空列表
     * @throws BusinessException id 不合法抛 A0401，证书不存在抛 A0402
     */
    @Override
    public List<MemberVO> listMembersByCertificate(long certificateId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        // ② 先校验证书存在：否则「证书不存在」会被伪装成「这张证书还没人绑定」
        assertCertificateExists(certificateId);

        // ③ 反向查询：先从关联表取 member_id 列表（走 idx_cert_member 最左前缀）
        LambdaQueryWrapper<StudioMemberCertificate> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.select(StudioMemberCertificate::getMemberId)
                .eq(StudioMemberCertificate::getCertificateId, certificateId);
        List<Long> memberIds = this.list(relationWrapper).stream()
                .map(StudioMemberCertificate::getMemberId)
                .toList();

        // ④ 空集合必须直接返回：拿空集合拼 IN () 是语法错误（MySQL 会直接报语法错）
        if (memberIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getId, memberIds);
        // 成员侧没有 award_date，沿用成员模块的默认排序：置顶权重倒序 + id 倒序兜底
        wrapper.orderByDesc(StudioMember::getSortOrder).orderByDesc(StudioMember::getId);
        // ⑤ selectList 同样自动追加 deleted_at = 0：已逻辑删除的成员不会出现在结果里
        return studioMemberMapper.selectList(wrapper).stream().map(this::toMemberVO).toList();
    }

    // 这里没有「按成员查证书」的 C 端脱敏视图：它与「团队成员不对外展示」的规则冲突。
    // 管理端的 listCertificatesByMember 复用下方同一条取数逻辑（selectCertificatesOfMember），
    // 保证「后台看到几张、门户就看到几张」这个一致性前提不受影响。

    // ==================== 级联清理 ====================

    /**
     * 清理某成员的全部证书关联（供 deleteMember 在同一事务内调用）
     *
     * 成员被删后这些行会继续占用唯一索引，导致「同一个成员 ID 重新绑进证书」时冲突。
     * 用 baseMapper.delete() 而不是 Service 的 remove()：前者返回被删除的行数，便于日志核对。
     *
     * 坑：本方法必须在调用方的事务里执行，自己不加 @Transactional——
     * 在这里另开事务会让「清关联 + 删主表」失去原子性。
     *
     * @param memberId 成员 ID，必须大于 0
     * @return 被删除的行数（0 表示该成员本来就没有绑定任何证书）
     * @throws BusinessException id 不合法时抛出（A0401）
     */
    @Override
    public long removeByMemberId(long memberId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // ② 用 baseMapper.delete() 而不是 Service 的 remove()：
        // 前者返回「被删除的行数」，后者只返回 boolean（删没删掉）。
        // 级联清理是内部调用，行数对排查问题有用（例如日志里确认清理了 3 条）
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId));
    }

    /**
     * 清理某证书的全部关联关系（供 deleteCertificate 在同一事务内调用）
     *
     * 与 removeByMemberId 对称：不清掉会留下指向已删除证书的悬空关联，并持续占用唯一索引。
     *
     * 坑：调用方必须先执行本方法、再删主表。顺序反了，主表删掉、清理失败就会留下悬空关联，且不报错。
     *
     * @param certificateId 证书 ID，必须大于 0
     * @return 被删除的行数（0 表示这张证书本来就没有绑定任何成员）
     * @throws BusinessException id 不合法时抛出（A0401）
     */
    @Override
    public long removeByCertificateId(long certificateId) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        // ② 与 removeByMemberId 同一写法：baseMapper.delete 返回行数，remove 只返回 boolean
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getCertificateId, certificateId));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 两步查询：取「某成员持有的全部证书」（管理端复用）
     *
     * @param memberId 成员 ID（调用方已校验存在）
     * @return 证书实体列表，已排序
     */
    private List<StudioCertificate> selectCertificatesOfMember(long memberId) {
        // 第一步：只取 certificate_id 一列，走 uk_member_cert 覆盖索引（不回表）
        LambdaQueryWrapper<StudioMemberCertificate> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.select(StudioMemberCertificate::getCertificateId)
                .eq(StudioMemberCertificate::getMemberId, memberId);
        List<Long> certificateIds = this.list(relationWrapper).stream()
                .map(StudioMemberCertificate::getCertificateId)
                .toList();

        // 空集合直接返回：IN () 是非法 SQL，不能交给 MP 去拼
        if (certificateIds.isEmpty()) {
            return List.of();
        }

        // 第二步：按 ID 批量查主表。selectList 自动追加 deleted_at = 0，
        // 于是「关联还在、但证书已被逻辑删除」的悬空数据被天然过滤
        LambdaQueryWrapper<StudioCertificate> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioCertificate::getId, certificateIds);
        applyCertificateSort(wrapper);
        return studioCertificateMapper.selectList(wrapper);
    }

    /**
     * 证书列表排序：sort_order 倒序 → award_date 倒序 → id 倒序
     *
     * 与 CertificateServiceImpl 的默认排序保持一致（置顶优先、其次获奖时间从新到旧），
     * 并追加 id 作为最终稳定键，避免前两个键相同时分页翻页出现乱序。
     *
     * @param wrapper 证书查询包装类
     */
    private void applyCertificateSort(LambdaQueryWrapper<StudioCertificate> wrapper) {
        // 三键缺一不可：前两键很容易相同（权重默认 0、同一天获奖），
        // 少了 id 这个稳定键，翻页时同一行可能出现在两页
        wrapper.orderByDesc(StudioCertificate::getSortOrder)
                .orderByDesc(StudioCertificate::getAwardDate)
                .orderByDesc(StudioCertificate::getId);
    }

    /**
     * 按 (member_id, certificate_id) 构造关联查询条件
     *
     * 把条件收在一处：查重与解绑都用它，避免某处少写一个 eq，
     * 导致「查到了别人绑定同一张证书的记录」。
     *
     * @param memberId 成员 ID
     * @param certificateId 证书 ID
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioMemberCertificate> relationWrapper(long memberId, long certificateId) {
        // 两个条件缺一不可：少一个就会把「其他成员绑同一张证书」也算进来
        return new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId)
                .eq(StudioMemberCertificate::getCertificateId, certificateId);
    }

    /**
     * 校验成员存在（含「已逻辑删除视为不存在」）
     *
     * @param memberId 成员 ID
     */
    private void assertMemberExists(long memberId) {
        // selectById 会被 MP 自动追加 deleted_at = 0：已删除的成员同样视为不存在
        ThrowUtils.throwIf(studioMemberMapper.selectById(memberId) == null,
                ErrorCode.NOT_FOUND_ERROR, "成员不存在");
    }

    /**
     * 校验证书存在（含「已逻辑删除视为不存在」）
     *
     * @param certificateId 证书 ID
     */
    private void assertCertificateExists(long certificateId) {
        // 与 assertMemberExists 同理：逻辑删除的证书不该再被绑定或查询
        ThrowUtils.throwIf(studioCertificateMapper.selectById(certificateId) == null,
                ErrorCode.NOT_FOUND_ERROR, "证书不存在");
    }

    /**
     * 实体转管理端证书 VO（写法与 CertificateServiceImpl 一致）
     *
     * 本类不能注入 CertificateService（那会构成循环依赖），所以这几行拷贝是刻意重复的。
     *
     * @param certificate 证书实体
     * @return 管理端 VO
     */
    private CertificateVO toCertificateVO(StudioCertificate certificate) {
        CertificateVO certificateVO = new CertificateVO();
        BeanUtils.copyProperties(certificate, certificateVO);
        return certificateVO;
    }

    // 实体转 C 端证书 VO（脱敏：目标 VO 没有 sortOrder 与审计字段，结构上拷不过去）
    //
    // @param certificate 证书实体
    // @return C 端脱敏 VO

    /**
     * 实体转管理端成员 VO（写法与 StudioMemberServiceImpl 一致）
     *
     * 同样因为依赖方向不能注入 StudioMemberService，这里照抄一份转换。
     *
     * @param member 成员实体
     * @return 管理端 VO
     */
    private MemberVO toMemberVO(StudioMember member) {
        MemberVO memberVO = new MemberVO();
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }
}
