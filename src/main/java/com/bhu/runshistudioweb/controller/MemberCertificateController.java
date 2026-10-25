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
 * Controller 只做接参、调 Service、包响应三件事；存在性、重复绑定等规则全在 Service 里。
 *
 * 鉴权：四个接口全部要求 admin。关联关系是后台维护的数据，没有 C 端读者——
 * 官网荣誉墙展示的是证书本身（CertificateFrontVO），不含「这张证书属于谁」。
 *
 * 本模块没有 C 端接口：官网「某成员的证书」这类按成员维度的展示，
 * 与「团队成员不对外展示」的产品规则冲突，因此不做。
 * 证书对外展示走 CertificateController 的 /certificate/list，那是证书自身维度、不含成员信息。
 *
 * 路径前缀用 /member-certificate 而不是 /member/certificate：
 * 后者会让人误以为它属于 /member 那一片（管理端 /member/* 全部要求 admin，
 * 但看路径容易以为「/member 下面有公开的东西」）。扁平且独立的模块前缀更不容易看错，
 * 白名单登记时也不会与 /member 下的路径混淆。
 *
 * 返回的 VO 直接复用证书模块与成员模块的管理端 VO（CertificateVO / MemberVO）：
 * 这里回答的是「这个成员有哪些证书」「这张证书有哪些成员」，
 * 返回的就是那两个模块的实体视图，再包一层新 VO 只会多一次字段同步的负担。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/member-certificate/bind。
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
     * <p>为什么这样设计：这是管理员的显式动作，必须「所见即所得」——点了绑定，
     * 要么成功，要么明确告诉管理员为什么没成。所以重复绑定返回 A0401 而不是静默成功。
     * 两层防御缺一不可：应用层查重负责给出可读的中文提示，
     * 数据库唯一索引负责兜住并发、保证不出现重复数据。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（两个 id 都非空且为正），
     *     请求体整体为 null 时它不触发；</li>
     *     <li>② 把两个 id 拆开传给 Service，而不是把整个 DTO 传进去——
     *     方法签名就说明了「它只关心这两个值」；</li>
     *     <li>③ 返回布尔值。true 表示绑定成功，失败一律抛异常而不是返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要为了「省一次查询」去掉 Service 里的应用层查重、只留唯一索引。
     * 那样并发下用户看到的会是一段英文 SQL 异常，落到全局兜底后变成 B0001「系统错误」，
     * 而真实原因只是「这张证书已经绑过了」。
     *
     * @param bindRequest 绑定请求：memberId 与 certificateId 均必填且为正整数
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法或已绑定过（A0401）、成员或证书不存在（A0402）时抛出
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
     * <p>为什么这样设计：与绑定共用同一个请求体结构，因为两者入参完全一样
     * （成员 ID + 证书 ID）。分成两个 DTO 只会带来「改一个忘了改另一个」的风险，
     * 语义差异（新增 vs 删除、冲突报错 vs 不存在报错）由 Service 方法区分。
     * 关联表用物理删除而不是逻辑删除：若改成逻辑删除，唯一索引会被标记删除的行占用，
     * 「先解绑、再重新绑定同一张证书」这个正常操作会一直冲突。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：先确认关联存在（不存在给 A0402），再物理删除。
     *     先查后删的顺序不能省——直接删的话，返回的「影响 0 行」无法区分
     *     「本来就没有」与「刚被别人删掉」；</li>
     *     <li>③ 返回布尔值，true 表示解绑成功。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回 true 只代表 DELETE 执行成功，不代表「这条关系先前一定存在」。
     * 存在性检查在 Service 的第 ① 步，把它当成「一定有」来用会掩盖并发删除的情况。
     *
     * @param bindRequest 解绑请求：memberId 与 certificateId 均必填且为正整数
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法（A0401）、关联不存在（A0402）时抛出
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
     * <p>为什么这样设计：用查询对象接参而不是 {@code @RequestParam long memberId}，
     * 这是本方法最需要讲清的一点。基本类型接参时，「忘了传 memberId」会抛
     * MissingServletRequestParameterException，全局异常处理器没有为它写处理器，
     * 于是被兜成 B0001「系统错误」——明明是用户少传了一个参数，却报成后端故障。
     * 用包装类型 Long + 这里显式判空，才能给出「成员 id 不能为空」这种准确的 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 按字段名绑定查询串，得到一个可能字段全为 null 的对象；</li>
     *     <li>② 显式判空：对象为 null（理论上不会，但兜一层）或 memberId 为 null，
     *     都直接给 A0401 并带上可读文案；</li>
     *     <li>③ 调 Service：校验成员存在、两步查询取证书（先关联表取 ID，再 IN 查主表）、
     *     转成管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要图省事写成 {@code @RequestParam long memberId}。
     * 那样缺参时的错误码是 B0001，前端会弹「系统错误」并触发告警，
     * 而真实原因只是调用方拼错了 URL——一个纯前端问题被计成了服务端故障。
     *
     * @param query 查询条件：memberId 必填；整个对象或该字段为 null 时得到 A0401
     * @return 证书列表，记录为 CertificateVO（含图片、置顶权重与审计时间），
     * 按置顶权重 → 获奖日期 → id 倒序；该成员没有证书时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * memberId 缺失（A0401）、成员不存在（A0402）时抛出
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
     * <p>为什么这样设计：与上一个方法完全对称，只是换了查询方向。
     * 两个接口共用一个查询对象，各自只取自己需要的那一个字段——
     * 它们只是「从哪一端查」的差别，字段集合相同，分成两个 DTO 没有意义。
     * 反向查询走的是关联表上 (certificate_id, member_id) 这条索引，
     * 与正向查询是同一套两步查询思路，不必为反向再加索引。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 绑定查询串；两个字段都可能为 null，本方法只用 certificateId；</li>
     *     <li>② 显式判空并给可读文案。注意这里判的是 certificateId 而不是 memberId——
     *     两个接口用同一个 DTO，取错字段会变成「永远判 null、永远报错」；</li>
     *     <li>③ 调 Service：校验证书存在、从关联表取成员 ID、空集合提前返回、
     *     再 IN 查成员主表并转 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：空集合必须提前返回。拿空集合去拼 {@code IN ()} 是语法错误，
     * MySQL 会直接报错；而这条路径只在「证书存在但没人绑定」时触发，很容易漏测。
     * 这个判断在 Service 里，Controller 不要试图用「先查一次 count」来代替它。
     *
     * @param query 查询条件：certificateId 必填；整个对象或该字段为 null 时得到 A0401
     * @return 成员列表，记录为 MemberVO（含账号绑定与置顶权重等内部字段），
     * 按置顶权重 → id 倒序；该证书没有关联成员时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * certificateId 缺失（A0401）、证书不存在（A0402）时抛出
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
