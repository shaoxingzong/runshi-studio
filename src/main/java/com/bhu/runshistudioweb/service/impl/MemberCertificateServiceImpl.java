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
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class MemberCertificateServiceImpl extends ServiceImpl<StudioMemberCertificateMapper, StudioMemberCertificate>
        implements MemberCertificateService {

    /** 只注入 Mapper，不注入主表 Service（原因见类注释的「依赖方向铁律」） */
    private final StudioMemberMapper studioMemberMapper;

    private final StudioCertificateMapper studioCertificateMapper;

    // 写操作

    /** 绑定成员与证书（管理员显式动作） */
    @Override
    public boolean bind(long memberId, long certificateId) {
        // 两个 id 都必须是正数：雪花 ID 恒为正，0 / 负数一定是调用方传错或探测请求
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");

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
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该成员已绑定此证书");
        }
    }

    /** 解绑成员与证书（物理删除：本实体没有 @TableLogic） */
    @Override
    public boolean unbind(long memberId, long certificateId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");

        // 先查关系是否存在：不存在要明确说「未绑定」，而不是返回 false 让前端猜
        boolean exists = this.count(relationWrapper(memberId, certificateId)) > 0;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "该成员未绑定此证书");

        // 物理删除：本实体没有 @TableLogic，remove(wrapper) 生成的是 DELETE 而不是 UPDATE
        return this.remove(relationWrapper(memberId, certificateId));
    }

    // 读操作

    /** 查某成员持有的全部证书（管理端视图） */
    @Override
    public List<CertificateVO> listCertificatesByMember(long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        // 先校验成员存在：否则「成员不存在」会被伪装成「这个人没有证书」
        assertMemberExists(memberId);

        // 取数逻辑收在私有方法里，保证正向与反向查询用同一套排序与过滤规则
        List<StudioCertificate> certificates = selectCertificatesOfMember(memberId);
        return certificates.stream().map(this::toCertificateVO).toList();
    }

    /** 查某证书关联的全部成员（反向查询，走 idx_cert_member） */
    @Override
    public List<MemberVO> listMembersByCertificate(long certificateId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        // 先校验证书存在：否则「证书不存在」会被伪装成「这张证书还没人绑定」
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

    // 级联清理

    /** 清理某成员的全部证书关联（供 deleteMember 在同一事务内调用） */
    @Override
    public long removeByMemberId(long memberId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(memberId <= 0, ErrorCode.PARAMS_ERROR, "成员 id 不合法");
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId));
    }

    /** 清理某证书的全部关联关系（供 deleteCertificate 在同一事务内调用） */
    @Override
    public long removeByCertificateId(long certificateId) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(certificateId <= 0, ErrorCode.PARAMS_ERROR, "证书 id 不合法");
        // 与 removeByMemberId 同一写法：baseMapper.delete 返回行数，remove 只返回 boolean
        return baseMapper.delete(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getCertificateId, certificateId));
    }

    // 私有工具方法

    /** 两步查询：取「某成员持有的全部证书」（管理端复用） */
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

        LambdaQueryWrapper<StudioCertificate> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioCertificate::getId, certificateIds);
        applyCertificateSort(wrapper);
        return studioCertificateMapper.selectList(wrapper);
    }

    /** 证书列表排序：sort_order 倒序 → award_date 倒序 → id 倒序 */
    private void applyCertificateSort(LambdaQueryWrapper<StudioCertificate> wrapper) {
        wrapper.orderByDesc(StudioCertificate::getSortOrder)
                .orderByDesc(StudioCertificate::getAwardDate)
                .orderByDesc(StudioCertificate::getId);
    }

    /** 按 (member_id, certificate_id) 构造关联查询条件 */
    private LambdaQueryWrapper<StudioMemberCertificate> relationWrapper(long memberId, long certificateId) {
        // 两个条件缺一不可：少一个就会把「其他成员绑同一张证书」也算进来
        return new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId)
                .eq(StudioMemberCertificate::getCertificateId, certificateId);
    }

    /** 校验成员存在（含「已逻辑删除视为不存在」） */
    private void assertMemberExists(long memberId) {
        ThrowUtils.throwIf(studioMemberMapper.selectById(memberId) == null,
                ErrorCode.NOT_FOUND_ERROR, "成员不存在");
    }

    /** 校验证书存在（含「已逻辑删除视为不存在」） */
    private void assertCertificateExists(long certificateId) {
        // 与 assertMemberExists 同理：逻辑删除的证书不该再被绑定或查询
        ThrowUtils.throwIf(studioCertificateMapper.selectById(certificateId) == null,
                ErrorCode.NOT_FOUND_ERROR, "证书不存在");
    }

    /**
     * 实体转管理端证书 VO（写法与 CertificateServiceImpl 一致）
     *
     * 本类不能注入 CertificateService（那会构成循环依赖），所以这几行拷贝是刻意重复的。
     */
    private CertificateVO toCertificateVO(StudioCertificate certificate) {
        CertificateVO certificateVO = new CertificateVO();
        BeanUtils.copyProperties(certificate, certificateVO);
        return certificateVO;
    }

    /**
     * 实体转管理端成员 VO（写法与 StudioMemberServiceImpl 一致）
     *
     * 同样因为依赖方向不能注入 StudioMemberService，这里照抄一份转换。
     */
    private MemberVO toMemberVO(StudioMember member) {
        MemberVO memberVO = new MemberVO();
        BeanUtils.copyProperties(member, memberVO);
        return memberVO;
    }
}
