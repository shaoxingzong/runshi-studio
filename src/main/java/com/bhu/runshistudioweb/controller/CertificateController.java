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
 * 两组界线：/certificate/add、/update、/delete、/get、/list/page 要求 admin；
 * /certificate/list 匿名可访问，精确路径登记在白名单，不能写成 /certificate/**。
 *
 * 管理端返回 CertificateVO，C 端返回 CertificateFrontVO——脱敏靠「目标 VO 没有这些字段」。
 *
 * @author shaoshing
 */
@Tag(name = "证书模块", description = "荣誉证书的管理与官网展示")
@RestController
@RequestMapping("/certificate")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 新增证书
     *
     * 级别与类型拆成两个字段：合并成一个字符串后想查「所有国家级证书」就只能模糊匹配，
     * 既走不了索引，又会把「省级竞赛（国家级）」这类命名误匹配进来。
     *
     * @param certificateAddRequest 名称、级别、类型、获奖日期、图片均必填
     * @return 新证书 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增证书", description = "级别与类型分开提交，获奖日期不得晚于今天")
    public BaseResponse<Long> addCertificate(@RequestBody @Valid CertificateAddRequest certificateAddRequest) {
        // ① 空 body 时 @Valid 整条链路都不生效，必须先挡
        ThrowUtils.throwIf(certificateAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 枚举取值与日期规则由 Service 校验，提示文案由枚举生成，不会与实际取值脱节
        return ResultUtils.success(certificateService.addCertificate(certificateAddRequest));
    }

    /**
     * 更新证书（部分更新：字段为 null 表示不修改）
     *
     * 成立前提是 updateById 跳过 null 字段，所以 Service 绝不能给未传的字段填默认值；
     * 「不传」是不改，「传空串」是想清空，NOT NULL 列必须拦成 A0401。
     *
     * @param certificateUpdateRequest id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功，失败抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新证书", description = "字段为 null 表示不修改")
    public BaseResponse<Boolean> updateCertificate(
            @RequestBody @Valid CertificateUpdateRequest certificateUpdateRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(certificateUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 「只改传了的字段」由 Service 的白名单控制，Controller 不在这里做字段搬运
        return ResultUtils.success(certificateService.updateCertificate(certificateUpdateRequest));
    }

    /**
     * 删除证书（逻辑删除）
     *
     * 逻辑删除保住审计线索，误删可恢复。删除是「主表写 + 关联表写」两次操作，须同一事务，
     * 且先清「成员-证书」关联再删主表，否则会留下指向已删证书的悬空关联且不报错。
     *
     * @param deleteRequest id 必填且为正整数；这里用的是通用 DeleteRequest，别换成帖子那个
     * @return true 表示删除成功
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除证书", description = "逻辑删除，数据可追溯")
    public BaseResponse<Boolean> deleteCertificate(@RequestBody @Valid DeleteRequest deleteRequest) {
        // ① 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，这里再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 级联清理在 Service 的同一事务里完成，返回值只表示「主表 UPDATE 命中了行」
        return ResultUtils.success(certificateService.deleteCertificate(deleteRequest.getId()));
    }

    /**
     * 查询证书详情（管理端）
     *
     * 不存在时抛 A0402 而不是返回 null——「code 成功但 data 为空」会让前端分不清空态与故障。
     *
     * @param id 证书 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return CertificateVO（含置顶权重与审计时间）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询证书详情")
    public BaseResponse<CertificateVO> getCertificateById(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何行，查库只会返回一个误导性的「证书不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // ③ 管理端 VO 含内部字段，因此不能把它复用到任何匿名接口上
        return ResultUtils.success(certificateService.getCertificateById(id));
    }

    /**
     * 分页查询证书（管理端）
     *
     * 用 GET 而非 POST：查询不改服务端状态，才能被缓存、便于贴链接排查。
     * 排序字段走白名单——ORDER BY 无法用占位符参数化，直接拼前端字符串就是注入入口。
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

    // ==================== C 端：匿名可访问（已加入 Sa-Token 白名单）====================

    /**
     * 官网证书列表（匿名可访问）
     *
     * 白名单 + 默认登录拦截下，不加鉴权注解即「匿名可访问」，是刻意而非漏加。
     * 请求里的 sortField / sortOrder 一律忽略：排序属于产品定义，不能由匿名请求决定。
     *
     * @param certificateQueryRequest 级别、类型、标题，允许为 null
     * @return 脱敏列表（CertificateFrontVO，不含置顶权重与审计字段）
     */
    @GetMapping("/list")
    @Operation(summary = "官网证书列表", description = "匿名可访问，返回脱敏后的展示字段")
    public BaseResponse<List<CertificateFrontVO>> listCertificate(
            @Valid CertificateQueryRequest certificateQueryRequest) {
        // ① Spring 绑定查询参数（级别、类型、标题），允许一个都不传；
        //    @Valid 只校验标题关键词长度（@Size max=64），超长返回 A0401
        // ②③ 返回条数上限与固定排序都在 Service 里，公开接口不接受请求方指定排序
        return ResultUtils.success(certificateService.listFrontCertificates(certificateQueryRequest));
    }
}
