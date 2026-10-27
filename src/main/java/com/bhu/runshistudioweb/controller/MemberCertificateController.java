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
 * 四个接口全部要求 admin：关联关系是后台维护的数据，没有 C 端读者——官网荣誉墙展示的是
 * 证书本身（CertificateFrontVO），不含「这张证书属于谁」。
 *
 * 前缀用 /member-certificate 而不是 /member/certificate，避免被误读成 /member 那一片路径。
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
     * 管理员的显式动作必须「所见即所得」，所以重复绑定返回 A0401 而不是静默成功。
     * 应用层查重负责可读提示，数据库唯一索引负责并发兜底，两者不能互相替代。
     *
     * @param bindRequest memberId 与 certificateId 均必填且为正整数
     * @return true 表示绑定成功，失败一律抛异常
     */
    @PostMapping("/bind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】绑定成员与证书", description = "重复绑定返回 A0401；并发下由唯一索引兜底")
    public BaseResponse<Boolean> bind(@RequestBody @Valid MemberCertificateBindRequest bindRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(bindRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 显式动作必须报冲突：与内部同步用的「保证在里面」是两种语义，不能混用
        return ResultUtils.success(
                memberCertificateService.bind(bindRequest.getMemberId(), bindRequest.getCertificateId()));
    }

    /**
     * 解绑成员与证书
     *
     * 与绑定共用同一个请求体（入参完全一样），语义差异由 Service 方法区分。
     * 关联表用物理删除：逻辑删除会让被标记的行占住唯一索引，「解绑后再绑同一张证书」永远冲突。
     *
     * @param bindRequest memberId 与 certificateId 均必填且为正整数
     * @return true 表示解绑成功
     */
    @PostMapping("/unbind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】解绑成员与证书", description = "关系不存在返回 A0402「该成员未绑定此证书」")
    public BaseResponse<Boolean> unbind(@RequestBody @Valid MemberCertificateBindRequest bindRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(bindRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 关联表物理删除：逻辑删除会占住唯一索引，导致「解绑后重新绑定」永远冲突
        return ResultUtils.success(
                memberCertificateService.unbind(bindRequest.getMemberId(), bindRequest.getCertificateId()));
    }

    /**
     * 查某成员持有的全部证书（管理端视图）
     *
     * 用查询对象接参而不是 {@code @RequestParam long memberId}：基本类型缺参会被兜成 B0001，
     * 包装类型加显式判空才能给出「成员 id 不能为空」这种准确的 A0401。
     *
     * @param query memberId 必填；整个对象或该字段为 null 时得到 A0401
     * @return 证书列表（CertificateVO）；该成员没有证书时返回空列表
     */
    @GetMapping("/certificate/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查成员的证书", description = "成员不存在返回 A0402")
    public BaseResponse<List<CertificateVO>> listCertificatesByMember(MemberCertificateQueryRequest query) {
        // ① Spring 按字段名绑定查询串，得到一个可能字段全为 null 的对象：缺参时给 A0401「成员 id 不能为空」，而不是 Spring 的 B0001
        // ② 用包装类型 + 显式判空
        ThrowUtils.throwIf(query == null || query.getMemberId() == null,
                ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        // ③ 成员是否存在由 Service 校验：不存在时抛 A0402，而不是返回空列表让人误解成「没证书」
        return ResultUtils.success(memberCertificateService.listCertificatesByMember(query.getMemberId()));
    }

    /**
     * 查某证书关联的全部成员（管理端视图，反向查询）
     *
     * 与上一个方法对称，共用同一个查询对象，本方法只取 certificateId——取错字段会变成永远判空。
     * 空集合必须提前返回：拿空集合拼 {@code IN ()} 是语法错误，而这条路径只在「没人绑定」时触发。
     *
     * @param query certificateId 必填；整个对象或该字段为 null 时得到 A0401
     * @return 成员列表（MemberVO，含账号绑定等内部字段，不能用于对外接口）
     */
    @GetMapping("/member/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查证书的成员", description = "证书不存在返回 A0402")
    public BaseResponse<List<MemberVO>> listMembersByCertificate(MemberCertificateQueryRequest query) {
        // ① Spring 绑定查询串；两个字段都可能为 null，本方法只用 certificateId，这里必须取 certificateId——取错字段会变成永远判空
        // ② 同一个 DTO 服务两个方向
        ThrowUtils.throwIf(query == null || query.getCertificateId() == null,
                ErrorCode.PARAMS_ERROR, "证书 id 不能为空");
        // ③ 返回的是管理端 VO：含 userId 等内部字段，因此这个接口绝不能对外
        return ResultUtils.success(memberCertificateService.listMembersByCertificate(query.getCertificateId()));
    }
}
