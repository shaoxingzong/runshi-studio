package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.membercertificate.MemberCertificateBindRequest;
import com.bhu.runshistudioweb.model.dto.membercertificate.MemberCertificateQueryRequest;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员-证书关联接口（全部为管理端）
 *
 * @author shaoshing
 */
@Tag(name = "成员证书关联", description = "管理员维护成员与证书的绑定关系")
@RestController
@RequestMapping("/member-certificate")
@RequiredArgsConstructor
public class MemberCertificateController {

    private final MemberCertificateService memberCertificateService;

    /**
     * 绑定成员与证书
     *
     * @param bindRequest memberId 与 certificateId 均必填且为正整数
     * @return true 表示绑定成功，失败一律抛异常
     */
    @PostMapping("/bind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】绑定成员与证书", description = "重复绑定返回 A0401；并发下由唯一索引兜底")
    public BaseResponse<Boolean> bind(@RequestBody @Valid MemberCertificateBindRequest bindRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(bindRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 显式动作必须报冲突：与内部同步用的「保证在里面」是两种语义，不能混用
        return ResultUtils.success(
                memberCertificateService.bind(bindRequest.getMemberId(), bindRequest.getCertificateId()));
    }

    /**
     * 解绑成员与证书
     *
     * @param bindRequest memberId 与 certificateId 均必填且为正整数
     * @return true 表示解绑成功
     */
    @PostMapping("/unbind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】解绑成员与证书", description = "关系不存在返回 A0402「该成员未绑定此证书」")
    public BaseResponse<Boolean> unbind(@RequestBody @Valid MemberCertificateBindRequest bindRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(bindRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 关联表物理删除：逻辑删除会占住唯一索引，导致「解绑后重新绑定」永远冲突
        return ResultUtils.success(
                memberCertificateService.unbind(bindRequest.getMemberId(), bindRequest.getCertificateId()));
    }

    /**
     * 查某成员持有的全部证书（管理端视图）
     *
     * @param query memberId 必填；整个对象或该字段为 null 时得到 A0401
     * @return 证书列表（CertificateVO）；该成员没有证书时返回空列表
     */
    @GetMapping("/certificate/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查成员的证书", description = "成员不存在返回 A0402")
    public BaseResponse<List<CertificateVO>> listCertificatesByMember(MemberCertificateQueryRequest query) {
        ThrowUtils.throwIf(query == null || query.getMemberId() == null,
                ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        // 成员是否存在由 Service 校验：不存在时抛 A0402，而不是返回空列表让人误解成「没证书」
        return ResultUtils.success(memberCertificateService.listCertificatesByMember(query.getMemberId()));
    }

    /**
     * 查某证书关联的全部成员（管理端视图，反向查询）
     *
     * @param query certificateId 必填；整个对象或该字段为 null 时得到 A0401
     * @return 成员列表（MemberVO，含账号绑定等内部字段，不能用于对外接口）
     */
    @GetMapping("/member/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查证书的成员", description = "证书不存在返回 A0402")
    public BaseResponse<List<MemberVO>> listMembersByCertificate(MemberCertificateQueryRequest query) {
        ThrowUtils.throwIf(query == null || query.getCertificateId() == null,
                ErrorCode.PARAMS_ERROR, "证书 id 不能为空");
        // 返回的是管理端 VO：含 userId 等内部字段，因此这个接口绝不能对外
        return ResultUtils.success(memberCertificateService.listMembersByCertificate(query.getCertificateId()));
    }
}
