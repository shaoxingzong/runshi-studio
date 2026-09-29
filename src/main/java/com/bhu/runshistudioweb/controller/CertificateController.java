package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateAddRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateQueryRequest;
import com.bhu.runshistudioweb.model.dto.certificate.CertificateUpdateRequest;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.vo.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.CertificateVO;
import com.bhu.runshistudioweb.service.CertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 荣誉证书接口：管理端增删改查 + C 端官网展示
 *
 * author: shaoshing
 *
 * <p>与 UserController、StudioMemberController 完全同一套约定：
 * Controller 只做三件事——接参、调 Service、把结果包成 {@link BaseResponse}；
 * 校验与业务规则全部在 Service 层，这里不写 if-else 业务分支，也不写 try-catch。
 *
 * <p>两组接口的界线：
 * <ul>
 *     <li><b>管理端</b>：{@code /certificate/add}、{@code /update}、{@code /delete}、
 *     {@code /get}、{@code /list/page} —— 全部要求 {@code @SaCheckRole} 为 admin；</li>
 *     <li><b>C 端</b>：{@code /certificate/list} —— 匿名可访问，
 *     已在 {@code SaTokenMvcConfig} 的白名单中登记，
 *     <b>新增/改名这个路径时必须同步改白名单</b>，否则官网游客会拿到 40100。</li>
 * </ul>
 *
 * <p>返回的 VO 也分两套：管理端拿 {@link CertificateVO}（含置顶权重与审计时间），
 * C 端拿 {@link CertificateFrontVO}（脱敏）。二者都不含任何内部字段。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/certificate/list}。
 */
@Tag(name = "证书模块", description = "荣誉证书的管理与官网展示")
@RestController
@RequestMapping("/certificate")
public class CertificateController {

    @Resource
    private CertificateService certificateService;

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 新增证书
     *
     * @param certificateAddRequest 新增请求（名称、级别、类型、获奖日期、图片均必填）
     * @return 新证书 ID
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增证书", description = "级别与类型分开提交，获奖日期不得晚于今天")
    public BaseResponse<Long> addCertificate(@RequestBody @Valid CertificateAddRequest certificateAddRequest) {
        ThrowUtils.throwIf(certificateAddRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(certificateService.addCertificate(certificateAddRequest));
    }

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * @param certificateUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新证书", description = "字段为 null 表示不修改")
    public BaseResponse<Boolean> updateCertificate(
            @RequestBody @Valid CertificateUpdateRequest certificateUpdateRequest) {
        ThrowUtils.throwIf(certificateUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(certificateService.updateCertificate(certificateUpdateRequest));
    }

    /**
     * 删除证书（逻辑删除）
     *
     * @param deleteRequest 删除请求（id 必填），复用通用 DTO
     * @return true 表示删除成功
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除证书", description = "逻辑删除，数据可追溯")
    public BaseResponse<Boolean> deleteCertificate(@RequestBody @Valid DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(certificateService.deleteCertificate(deleteRequest.getId()));
    }

    /**
     * 查询证书详情（管理端）
     *
     * @param id 证书 ID
     * @return 证书信息（管理端 VO）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询证书详情")
    public BaseResponse<CertificateVO> getCertificateById(@RequestParam("id") long id) {
        // 用 long 接参：传非数字时 Spring 抛类型转换异常，由全局异常处理器兜成 40000 而不是 500
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        return ResultUtils.success(certificateService.getCertificateById(id));
    }

    /**
     * 分页查询证书（管理端）
     *
     * <p>用 GET + 查询参数而不是 POST + 请求体：查询不改变服务端状态，
     * 用 GET 才能被浏览器/网关缓存，也便于直接把链接贴出来排查问题。
     *
     * @param certificateQueryRequest 查询条件（id / title / awardType / awardLevel
     *                                + current / pageSize / sortField / sortOrder），允许为空
     * @return 分页结果，记录为管理端 VO
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询证书", description = "支持名称模糊查询与级别/类型精确筛选，每页最多 50 条")
    public BaseResponse<Page<CertificateVO>> listCertificateByPage(CertificateQueryRequest certificateQueryRequest) {
        return ResultUtils.success(certificateService.listCertificateByPage(certificateQueryRequest));
    }

    // ==================== C 端：匿名可访问（已加入 Sa-Token 白名单） ====================

    /**
     * 官网证书列表（匿名可访问）
     *
     * @param certificateQueryRequest 查询条件，允许为空（可按级别/类型筛选）
     * @return 脱敏后的证书列表，按置顶权重 + 获奖日期倒序
     */
    @GetMapping("/list")
    @Operation(summary = "官网证书列表", description = "匿名可访问，返回脱敏后的展示字段")
    public BaseResponse<List<CertificateFrontVO>> listCertificate(
            CertificateQueryRequest certificateQueryRequest) {
        return ResultUtils.success(certificateService.listFrontCertificates(certificateQueryRequest));
    }
}
