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
import com.bhu.runshistudioweb.model.vo.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.CertificateVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 成员-证书关联服务实现
 *
 * author: shaoshing
 *
 * <p><b>依赖方向铁律（见接口注释，这里再强调一次）</b>：本类<b>只注入 Mapper</b>
 * （{@link StudioMemberMapper} / {@link StudioCertificateMapper}），
 * <b>绝不注入</b> {@code StudioMemberService} / {@code CertificateService}——
 * 那两个 Service 反过来要依赖本类做级联清理，一旦这里再依赖回去就是循环依赖，
 * Spring Boot 默认禁止，**启动直接失败**。代价是三个 VO 转换要在本类各写一遍
 * {@code BeanUtils.copyProperties}（照抄对应 Service 里的写法），这个代价是明确且可接受的。
 *
 * <p><b>查询策略：两步查询，而不是 JOIN</b>。
 * <p>{@code db/DESIGN.md} 的场景 C 论证的是「一次 JOIN」的索引路径，这里刻意不那样做：
 * <ol>
 *     <li>项目目前<b>没有任何 XML Mapper</b>，为了一个 JOIN 引入手写 SQL，
 *     就多了一层「漏写 {@code deleted_at = 0}」的风险——主表是逻辑删除的，
 *     手写 JOIN 一旦漏掉这个条件，就会查出已删除成员/证书的悬空数据；</li>
 *     <li>两步查询里第二步走的是 {@code certificateMapper.selectList(...)}，
 *     MP 会<b>自动</b>追加 {@code deleted_at = 0}，于是「证书已被删」的悬空关联
 *     被天然过滤掉（查询侧防御），不需要我们记得写；</li>
 *     <li>一名成员的证书只有十几张，多一次数据库往返的成本可以忽略。</li>
 * </ol>
 * 取舍理由写在这里，是为了让"设计与实现不一致"这件事有据可查，而不是装作没看见。
 *
 * <p>第一步取 ID 时只 select 需要的那一列：{@code where member_id = ?} 走
 * {@code uk_member_cert} 的最左前缀，只取 certificate_id 属于覆盖索引（不回表）；
 * 反向查成员时同理走 {@code idx_cert_member}。
 *
 * <p><b>排序三键：sort_order 倒序 → award_date 倒序 → id 倒序</b>，与证书模块默认排序一致；
 * 追加 {@code id} 作为最终键，保证前两个键都相同时分页次序稳定（不会翻页时乱序）。
 */
@Service
public class MemberCertificateServiceImpl extends ServiceImpl<StudioMemberCertificateMapper, StudioMemberCertificate>
        implements MemberCertificateService {

    /**
     * 只注入 Mapper，不注入主表 Service（原因见类注释的「依赖方向铁律」）
     */
    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private StudioCertificateMapper studioCertificateMapper;

    // ==================== 写操作 ====================

    /**
     * 绑定成员与证书（管理员显式动作）
     *
     * <p><b>两次防御缺一不可</b>：
     * <ul>
     *     <li>先 {@code count} 查重 → 命中就给「该成员已绑定此证书」这种可读提示；
     *     单靠唯一索引的话，报错是英文的 SQLException，不能直接给前端；</li>
     *     <li>再 {@code catch DuplicateKeyException} → 兜住<b>并发竞态</b>
     *     （两个请求同时通过上面的查重，后到的会被唯一索引拦下）。</li>
     * </ul>
     */
    @Override
    public boolean bind(long memberId, long certificateId) {
        // 两个 id 都必须是正数：雪花 ID 恒为正，0 / 负数一定是调用方传错或探测请求
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");

        // 存在性校验：selectById 会被 MP 自动追加 deleted_at = 0，
        // 因此「已逻辑删除的成员/证书」在这里同样按不存在处理
        assertMemberExists(memberId);
        assertCertificateExists(certificateId);

        // 应用层查重：先查一次，命中就给出可读提示（唯一索引冲突的报错是英文 SQLException，不能给前端）
        ThrowUtils.throwIf(this.count(relationWrapper(memberId, certificateId)) > 0,
                ErrorCode.PARAMS_ERROR, "该成员已绑定此证书");

        StudioMemberCertificate relation = new StudioMemberCertificate();
        relation.setMemberId(memberId);
        relation.setCertificateId(certificateId);

        try {
            return this.save(relation);
        } catch (DuplicateKeyException e) {
            // 并发竞态兜底：两条请求同时通过上面的查重时，后到的会被 uk_member_cert 拦下。
            // 这里必须与上面的文案**完全一致**——对调用方而言，
            // 「先查出来的重复」和「并发下撞上唯一索引的重复」是同一件事，不该有两种提示
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该成员已绑定此证书");
        }
    }

    /**
     * 解绑成员与证书（<b>物理删除</b>：本实体没有 {@code @TableLogic}）
     *
     * <p>先查关系是否存在再删：不存在要明确返回 40400「该成员未绑定此证书」，
     * 而不是返回 false 让前端去猜「是没删掉还是本来就没有」。
     */
    @Override
    public boolean unbind(long memberId, long certificateId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");

        // 先查关系是否存在：不存在要明确说「未绑定」，而不是返回 false 让前端猜
        boolean exists = this.count(relationWrapper(memberId, certificateId)) > 0;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "该成员未绑定此证书");

        // 物理删除：本实体没有 @TableLogic，remove(wrapper) 生成的是 DELETE 而不是 UPDATE
        return this.remove(relationWrapper(memberId, certificateId));
    }

    // ==================== 读操作 ====================

    /**
     * 查某成员持有的全部证书（管理端视图）
     *
     * <p>采用<b>两步查询</b>而非 JOIN：先从关联表取 ID 列表（覆盖索引、不回表），
     * 再 {@code IN} 查主表——主表查询时 MyBatis-Plus 会<b>自动</b>追加
     * {@code deleted_at = 0}，于是「证书已被删」的悬空关联被天然过滤（查询侧防御）。
     */
    @Override
    public List<CertificateVO> listCertificatesByMember(long memberId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        assertMemberExists(memberId);

        List<StudioCertificate> certificates = selectCertificatesOfMember(memberId);
        return certificates.stream().map(this::toCertificateVO).toList();
    }

    /**
     * 查某证书关联的全部成员（反向查询，走 {@code idx_cert_member}）
     *
     * <p>与正向查询是同一套实现思路，只是换了索引方向；
     * 空集合必须提前返回——拿空集合拼 {@code IN ()} 是<b>语法错误</b>，MySQL 会直接报错。
     */
    @Override
    public List<MemberVO> listMembersByCertificate(long certificateId) {
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        assertCertificateExists(certificateId);

        // 反向查询：先从关联表取 member_id 列表（走 idx_cert_member 最左前缀）
        LambdaQueryWrapper<StudioMemberCertificate> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.select(StudioMemberCertificate::getMemberId)
                .eq(StudioMemberCertificate::getCertificateId, certificateId);
        List<Long> memberIds = this.list(relationWrapper).stream()
                .map(StudioMemberCertificate::getMemberId)
                .toList();

        // 空集合必须直接返回：拿空集合拼 IN () 是语法错误（MySQL 会直接报语法错）
        if (memberIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getId, memberIds);
        // 成员侧没有 award_date，沿用成员模块的默认排序：置顶权重倒序 + id 倒序兜底
        wrapper.orderByDesc(StudioMember::getSortOrder).orderByDesc(StudioMember::getId);
        // selectList 同样自动追加 deleted_at = 0：已逻辑删除的成员不会出现在结果里
        return studioMemberMapper.selectList(wrapper).stream().map(this::toMemberVO).toList();
    }

    /**
     * 查某成员持有的全部证书（<b>C 端脱敏视图</b>）
     *
     * <p>与管理端查询共用同一条取数逻辑（{@code selectCertificatesOfMember}），
     * 只是换成了 {@code CertificateFrontVO}——这样两端的筛选与排序规则天然一致，
     * 不会出现「后台看到 3 张、官网只显示 2 张」。
     */
    @Override
    public List<CertificateFrontVO> listFrontCertificatesByMember(long memberId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        assertMemberExists(memberId);

        return selectCertificatesOfMember(memberId).stream().map(this::toCertificateFrontVO).toList();
    }

    // ==================== 级联清理 ====================

    /**
     * 清理某成员的全部证书关联（供 {@code deleteMember} 在同一事务内调用）
     *
     * <p>用 {@code baseMapper.delete()} 而不是 Service 的 {@code remove()}：
     * 前者返回<b>被删除的行数</b>（便于日志核对），后者只返回 boolean。
     *
     * <p>物理删除：关联表没有 {@code deleted_at}，也不该有——成员都被删了，
     * 保留他的绑定关系没有意义。
     */
    @Override
    public long removeByMemberId(long memberId) {
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // 用 baseMapper.delete() 而不是 Service 的 remove()：
        // 前者返回「被删除的行数」，后者只返回 boolean（删没删掉）。
        // 级联清理是内部调用，行数对排查问题有用（例如日志里确认清理了 3 条）
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId));
    }

    /**
     * 清理某证书的全部关联关系（供 {@code deleteCertificate} 在同一事务内调用）
     *
     * <p>与 {@code removeByMemberId} 对称：删证书时也要把「谁持有它」的关系一次性清掉，
     * 否则会留下指向已删除证书的悬空关联。
     */
    @Override
    public long removeByCertificateId(long certificateId) {
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getCertificateId, certificateId));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 两步查询的第一步 + 第二步：取「某成员持有的全部证书」（管理端与 C 端共用）
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
     * <p>与 {@code CertificateServiceImpl} 的默认排序保持一致（置顶优先、其次获奖时间从新到旧），
     * 并追加 {@code id} 作为最终稳定键，避免前两个键相同时分页翻页出现乱序。
     *
     * @param wrapper 证书查询包装类
     */
    private void applyCertificateSort(LambdaQueryWrapper<StudioCertificate> wrapper) {
        wrapper.orderByDesc(StudioCertificate::getSortOrder)
                .orderByDesc(StudioCertificate::getAwardDate)
                .orderByDesc(StudioCertificate::getId);
    }

    /**
     * 按 (member_id, certificate_id) 构造关联查询条件
     *
     * @param memberId      成员 ID
     * @param certificateId 证书 ID
     * @return 查询包装类
     */
    private LambdaQueryWrapper<StudioMemberCertificate> relationWrapper(long memberId, long certificateId) {
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
        ThrowUtils.throwIf(studioMemberMapper.selectById(memberId) == null,
                ErrorCode.NOT_FOUND_ERROR, "成员不存在");
    }

    /**
     * 校验证书存在（含「已逻辑删除视为不存在」）
     *
     * @param certificateId 证书 ID
     */
    private void assertCertificateExists(long certificateId) {
        ThrowUtils.throwIf(studioCertificateMapper.selectById(certificateId) == null,
                ErrorCode.NOT_FOUND_ERROR, "证书不存在");
    }

    /**
     * 实体转管理端证书 VO（写法与 CertificateServiceImpl 一致）
     *
     * @param certificate 证书实体
     * @return 管理端 VO
     */
    private CertificateVO toCertificateVO(StudioCertificate certificate) {
        CertificateVO certificateVO = new CertificateVO();
        BeanUtils.copyProperties(certificate, certificateVO);
        return certificateVO;
    }

    /**
     * 实体转 C 端证书 VO（脱敏：目标 VO 没有 sortOrder 与审计字段，结构上拷不过去）
     *
     * @param certificate 证书实体
     * @return C 端脱敏 VO
     */
    private CertificateFrontVO toCertificateFrontVO(StudioCertificate certificate) {
        CertificateFrontVO frontVO = new CertificateFrontVO();
        BeanUtils.copyProperties(certificate, frontVO);
        return frontVO;
    }

    /**
     * 实体转管理端成员 VO（写法与 StudioMemberServiceImpl 一致）
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
