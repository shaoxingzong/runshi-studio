package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.membercertificate.MemberCertificateBindRequest;
import com.bhu.runshistudioweb.model.dto.membercertificate.MemberCertificateQueryRequest;
import com.bhu.runshistudioweb.model.vo.CertificateVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.service.MemberCertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员-证书关联接口（<b>全部为管理端</b>）
 *
 * author: shaoshing
 *
 * <p>Controller 只做接参、调 Service、包响应三件事；存在性、重复绑定等规则全在 Service 里。
 *
 * <p><b>本模块没有 C 端接口</b>：官网「某成员的证书」是 {@code GET /member/certificate/list}，
 * 挂在 {@link StudioMemberController} 上（因为它是从「成员」维度出发的展示接口，
 * 与成员列表同前缀，也便于白名单按 {@code /member/...} 精确登记）。
 *
 * <p>路径前缀用 {@code /member-certificate} 而不是 {@code /member/certificate}：
 * 后者会被白名单里的 {@code /member/list} 之外的路径混淆视听（例如误以为
 * {@code /member/certificate/...} 也属公开），扁平且独立的模块前缀更不容易看错。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/member-certificate/bind}。
 */
@Tag(name = "成员证书关联", description = "管理员维护成员与证书的绑定关系")
@RestController
@RequestMapping("/member-certificate")
public class MemberCertificateController {

    @Resource
    private MemberCertificateService memberCertificateService;

    /**
     * 绑定成员与证书
     *
     * @param bindRequest 绑定请求（memberId 与 certificateId 均必填）
     * @return true 表示绑定成功
     */
    @PostMapping("/bind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】绑定成员与证书", description = "重复绑定返回 A0401；并发下由唯一索引兜底")
    public BaseResponse<Boolean> bind(@RequestBody @Valid MemberCertificateBindRequest bindRequest) {
        ThrowUtils.throwIf(bindRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(
                memberCertificateService.bind(bindRequest.getMemberId(), bindRequest.getCertificateId()));
    }

    /**
     * 解绑成员与证书
     *
     * @param bindRequest 解绑请求（与绑定共用同一个请求体结构）
     * @return true 表示解绑成功
     */
    @PostMapping("/unbind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】解绑成员与证书", description = "关系不存在返回 A0402「该成员未绑定此证书」")
    public BaseResponse<Boolean> unbind(@RequestBody @Valid MemberCertificateBindRequest bindRequest) {
        ThrowUtils.throwIf(bindRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(
                memberCertificateService.unbind(bindRequest.getMemberId(), bindRequest.getCertificateId()));
    }

    /**
     * 查某成员持有的全部证书（管理端视图）
     *
     * <p>用 DTO 接参而不是 {@code @RequestParam long memberId}：
     * 基本类型接参时「忘了传 memberId」会抛 MissingServletRequestParameterException，
     * 全局处理器没接它 → 变成 B0001「系统错误」；用包装类型 + 这里显式判空，
     * 才能给出「成员 id 不能为空」这种准确的 A0401。
     *
     * @param query 查询条件（memberId 必填）
     * @return 证书列表（含图片 / 置顶权重 / 审计时间），按 sort_order → award_date → id 倒序
     */
    @GetMapping("/certificate/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查成员的证书", description = "成员不存在返回 A0402")
    public BaseResponse<List<CertificateVO>> listCertificatesByMember(MemberCertificateQueryRequest query) {
        ThrowUtils.throwIf(query == null || query.getMemberId() == null,
                ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        return ResultUtils.success(memberCertificateService.listCertificatesByMember(query.getMemberId()));
    }

    /**
     * 查某证书关联的全部成员（管理端视图，反向查询）
     *
     * @param query 查询条件（certificateId 必填）
     * @return 成员列表（含内部字段），按 sort_order → id 倒序
     */
    @GetMapping("/member/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查证书的成员", description = "证书不存在返回 A0402")
    public BaseResponse<List<MemberVO>> listMembersByCertificate(MemberCertificateQueryRequest query) {
        ThrowUtils.throwIf(query == null || query.getCertificateId() == null,
                ErrorCode.PARAMS_ERROR, "证书 id 不能为空");
        return ResultUtils.success(memberCertificateService.listMembersByCertificate(query.getCertificateId()));
    }
}
