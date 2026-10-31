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
import com.bhu.runshistudioweb.model.vo.certificate.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.certificate.CertificateVO;
import com.bhu.runshistudioweb.service.CertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 荣誉证书接口：管理端增删改查 + C 端官网展示
 *
 * @author shaoshing
 */
@Tag(name = "证书模块", description = "荣誉证书的管理与官网展示")
@RestController
@RequestMapping("/certificate")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;

    // 管理端：全部要求 admin 角色

    /**
     * 新增证书
     *
     * @param certificateAddRequest 名称、级别、类型、获奖日期、图片均必填
     * @return 新证书 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增证书", description = "级别与类型分开提交，获奖日期不得晚于今天")
    public BaseResponse<Long> addCertificate(@RequestBody @Valid CertificateAddRequest certificateAddRequest) {
        // 空 body 时 @Valid 整条链路都不生效，必须先挡
        ThrowUtils.throwIf(certificateAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 枚举取值与日期规则由 Service 校验，提示文案由枚举生成，不会与实际取值脱节
        return ResultUtils.success(certificateService.addCertificate(certificateAddRequest));
    }

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * @param certificateUpdateRequest id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功，失败抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新证书", description = "字段为 null 表示不修改")
    public BaseResponse<Boolean> updateCertificate(
            @RequestBody @Valid CertificateUpdateRequest certificateUpdateRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(certificateUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 「只改传了的字段」由 Service 的白名单控制，Controller 不在这里做字段搬运
        return ResultUtils.success(certificateService.updateCertificate(certificateUpdateRequest));
    }

    /**
     * 删除证书（逻辑删除）
     *
     * @param deleteRequest id 必填且为正整数；这里用的是通用 DeleteRequest，别换成帖子那个
     * @return true 表示删除成功
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除证书", description = "逻辑删除，数据可追溯")
    public BaseResponse<Boolean> deleteCertificate(@RequestBody @Valid DeleteRequest deleteRequest) {
        // 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，这里再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 级联清理在 Service 的同一事务里完成，返回值只表示「主表 UPDATE 命中了行」
        return ResultUtils.success(certificateService.deleteCertificate(deleteRequest.getId()));
    }

    /**
     * 查询证书详情（管理端）
     *
     * @param id 证书 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return CertificateVO（含置顶权重与审计时间）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询证书详情")
    public BaseResponse<CertificateVO> getCertificateById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // 管理端 VO 含内部字段，因此不能把它复用到任何匿名接口上
        return ResultUtils.success(certificateService.getCertificateById(id));
    }

    /**
     * 分页查询证书（管理端）
     *
     * @param certificateQueryRequest id、名称模糊、级别与类型精确 + 分页与排序，允许整体为 null
     * @return 分页结果（CertificateVO）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询证书", description = "支持名称模糊查询与级别/类型精确筛选，每页最多 50 条")
    public BaseResponse<Page<CertificateVO>> listCertificateByPage(@Valid CertificateQueryRequest certificateQueryRequest) {
        // @Valid 只做标题关键词长度校验（@Size max=64）；分页归一化与排序白名单仍在 Service 里
        return ResultUtils.success(certificateService.listCertificateByPage(certificateQueryRequest));
    }

    // C 端：匿名可访问（已加入 Sa-Token 白名单）

    /**
     * 官网证书列表（匿名可访问）
     *
     * @param certificateQueryRequest 级别、类型、标题，允许为 null
     * @return 脱敏列表（CertificateFrontVO，不含置顶权重与审计字段）
     */
    @GetMapping("/list")
    @Operation(summary = "官网证书列表", description = "匿名可访问，返回脱敏后的展示字段")
    public BaseResponse<List<CertificateFrontVO>> listCertificate(
            @Valid CertificateQueryRequest certificateQueryRequest) {
        return ResultUtils.success(certificateService.listFrontCertificates(certificateQueryRequest));
    }
}
