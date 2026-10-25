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
 * 与 UserController、StudioMemberController 完全同一套约定：
 * Controller 只做三件事——接参、调 Service、把结果包成 BaseResponse；
 * 校验与业务规则全部在 Service 层，这里不写 if-else 业务分支，也不写 try-catch。
 * 出了异常直接往外抛，由全局异常处理器统一转成标准响应。
 *
 * 两组接口的界线（本类最重要的约定）：
 * - 管理端：/certificate/add、/update、/delete、/get、/list/page ——
 * 全部要求 {@code @SaCheckRole} 为 admin；
 * - C 端：/certificate/list —— 匿名可访问，已登记在 SaTokenMvcConfig 的白名单里。
 * 新增或改名这个路径时必须同步改白名单，否则官网游客会拿到 A0201「未登录」。
 *
 * 白名单为什么必须写精确路径：只放行 /certificate/list 这一条，
 * 绝不写成 /certificate/**——那样会把 add / update / delete 一起放出去。
 * 注意 /certificate/list 与 /certificate/list/page 只差一个后缀，登记时不能省略。
 *
 * 返回的 VO 也分两套，不能互相顶替：
 * - 管理端拿 CertificateVO（含置顶权重与审计时间），管理员需要它来核对官网排序；
 * - C 端拿 CertificateFrontVO（结构上没有这些字段），
 * 脱敏靠「目标 VO 没有这些字段」实现，而不是「拷完再逐个置空」。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/certificate/list。
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
     * <p>为什么这样设计：级别与类型拆成两个字段提交，不允许合成
     * 「国家级竞赛」这样一个字符串。合并之后想单独查「所有国家级证书」，
     * 就只能做字符串模糊匹配——既走不了组合索引，又会把「国家级竞赛」与
     * 「省级竞赛（国家级）」这类命名混在一起误匹配。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（名称非空、日期不得晚于今天），
     *     请求体整体为 null 时它不触发，后面取值会抛空指针被兜成 B0001；</li>
     *     <li>② 交给 Service：校验级别与类型是否在枚举内、日期是否合理、图片是否为空，
     *     全部排在写库前——任何一项不合法都不该产生一次数据库往返；</li>
     *     <li>③ 返回新证书 ID（雪花算法生成的 19 位长整型，接口层序列化成字符串防前端丢精度）。</li>
     * </ol>
     *
     * <p>容易踩的坑：级别与类型必须分两次校验、分两列存储。若只校验其中一边，
     * 另一边的脏值会静默入库，表现为「按类型筛选时这条记录时有时无」——
     * 因为它根本没出现在任何合法的枚举取值里。
     *
     * @param certificateAddRequest 新增请求：名称、级别、类型、获奖日期、图片均必填
     * @return 新证书 ID（19 位，JSON 里是字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 字段缺失或取值非法（A0401）时抛出
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
     * <p>为什么这样设计：部分更新让前端「只改名称」时不必回传整行数据。
     * 它成立的前提是 MyBatis-Plus 的 updateById 默认跳过 null 字段——
     * 所以 Service 里绝不能给未传的字段填默认值，否则「不修改」会悄悄变成「改成默认值」。
     * 同时要区分「不传」与「传空串」：不传是不改，传空串是想清空，
     * 而 NOT NULL 列不允许清空，必须拦成 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：先查一次确认证书存在（updateById 失败只返回 false，
     *     前端分不清「不存在」与「没改成」），再只对「传了的字段」校验并写库；</li>
     *     <li>③ 返回布尔值。它表示更新命中了一行，失败会抛异常而不是返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：更新字段必须由服务端写死成白名单，不能把 DTO 直接转成实体整体 updateById——
     * 那样前端能顺手改 deletedAt 与审计字段，等于把内部数据的所有权交了出去。
     *
     * @param certificateUpdateRequest 更新请求：id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功（失败抛异常，不会返回 false）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 证书不存在（A0402）、字段取值非法（A0401）时抛出
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
     * <p>为什么这样设计：逻辑删除（只把 deleted_at 标成非 0，行仍留在表里）能保住审计线索，
     * 误删之后还能靠归档脚本恢复。删除是「主表写 + 关联表写」两次操作，
     * 必须在同一事务内，且顺序是先清「成员-证书」关联、再删主表——
     * 反过来一旦清理失败，就会留下指向被删证书的悬空关联，而且不会有任何报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。这里只判请求体本身，id 为 null 时 {@code @Valid} 会生效
     *     （因为字段上标了 @NotNull），因此不必再判一次；</li>
     *     <li>② 交给 Service：查存在性 → 清关联表 → 逻辑删除主表；</li>
     *     <li>③ 返回布尔值。true 只代表 UPDATE 命中了行，数据并没有从库里消失。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里 import 的是通用 DTO {@code model.dto.common.DeleteRequest}。
     * 帖子模块另有一个同名的 DeleteRequest，两者字段相同但语义不同，
     * 换错 import 编译器不会报错。另外不要为了「彻底清理」把逻辑删除改成物理删除——
     * 那会让「误删可恢复」这个前提消失。
     *
     * @param deleteRequest 删除请求：id 必填且为正整数
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 证书不存在（A0402）时抛出
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
     * <p>为什么这样设计：不存在时 Service 抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code 为成功但 data 为空」的响应，
     * 它分不清是「确实没有这张证书」还是「接口出错了」，只能靠猜决定展示空态还是报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何证书，直接给 A0401，
     *     省掉一次数据库往返，也避免把「参数错」伪装成「证书不存在」；</li>
     *     <li>③ 调 Service 取管理端 VO 并包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用 long 而不是 Long。传非数字（例如 ?id=abc）时 Spring 抛类型转换异常，
     * 全局异常处理器把它兜成 B0001 而不是 A0401。管理端详情总是带着 id 调，
     * 这个粗糙边界可以接受；若 id 来自用户手输的链接，应改用 Long 并显式判空。
     *
     * @param id 证书 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 证书信息，记录为 CertificateVO（含置顶权重与审计时间）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * id 不合法（A0401）、证书不存在（A0402）时抛出
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
     * <p>为什么这样设计：用 GET + 查询参数而不是 POST + 请求体。查询不改变服务端状态，
     * 用 GET 才能被浏览器与网关缓存，也便于直接把链接贴出来排查问题。
     * 排序字段走白名单——ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是 SQL 注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 按字段名把查询串绑定成 CertificateQueryRequest；
     *     一个参数都不传时字段全为 null，Service 会按「无条件、第一页」处理；</li>
     *     <li>② 交给 Service：纠正分页参数、拼筛选条件、按白名单排序、分页查询并转 VO；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid}。这些参数走 GET 传递，校验失败抛的是
     * BindException，全局异常处理器没接它，会变成 B0001「系统错误」；
     * 而「页码传成 0」本来也不该让整个查询失败，由 Service 收敛成 1 更符合预期。
     * 另外不要关掉分页的计数查询：关掉能省一次 COUNT，但前端分页器拿不到 total
     * 就画不出总页数。
     *
     * @param certificateQueryRequest 查询条件：id / title 模糊 / 级别与类型精确 / 分页 / 排序；
     *                                允许整体为 null
     * @return 分页结果，记录为 CertificateVO；无数据时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）时抛出
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询证书", description = "支持名称模糊查询与级别/类型精确筛选，每页最多 50 条")
    public BaseResponse<Page<CertificateVO>> listCertificateByPage(CertificateQueryRequest certificateQueryRequest) {
        // ①②③ 分页归一化与排序白名单都在 Service 里，Controller 不做二次校验
        return ResultUtils.success(certificateService.listCertificateByPage(certificateQueryRequest));
    }

    // ==================== C 端：匿名可访问（已加入 Sa-Token 白名单）====================

    /**
     * 官网证书列表（匿名可访问）
     *
     * <p>为什么这样设计：这是官网「荣誉墙」页的数据源，游客必须能拿到，
     * 因此路径登记在白名单里、方法上不加任何鉴权注解。注意「不加注解」在这里
     * 是刻意设计而不是漏加：白名单 + 默认登录拦截的组合下，不加注解意味着「匿名可访问」。
     * 反过来，如果哪天这个路径从白名单里被删掉，游客会立刻拿到 A0201，
     * 而代码里看不出任何异常。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 绑定查询参数（级别、类型、标题），允许为空；</li>
     *     <li>② 交给 Service：拼筛选条件、固定排序（置顶权重 → 获奖日期 → id 倒序）、
     *     取一页并转成脱敏 VO；</li>
     *     <li>③ 包成统一响应体。无数据时返回空列表而不是 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：请求里的 sortField / sortOrder 在这里一律被忽略。
     * 允许请求方指定排序，等于允许任何人把某条证书顶到官网第一位——
     * 排序规则属于产品定义，不能由匿名请求决定。另外这是公开接口，
     * 返回条数在 Service 里有硬上限（游客流量不可控，不能一次拉全表）。
     *
     * @param certificateQueryRequest 查询条件：级别、类型、标题；允许为 null
     * @return 脱敏后的证书列表，记录为 CertificateFrontVO（不含置顶权重与审计字段），
     * 条数有上限；无数据时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 不抛鉴权异常（匿名可访问），
     * 查询参数非法时由 Service 决定是否报 A0401
     */
    @GetMapping("/list")
    @Operation(summary = "官网证书列表", description = "匿名可访问，返回脱敏后的展示字段")
    public BaseResponse<List<CertificateFrontVO>> listCertificate(
            CertificateQueryRequest certificateQueryRequest) {
        // ① Spring 绑定查询参数（级别、类型、标题），允许一个都不传
        // ②③ 返回条数上限与固定排序都在 Service 里，公开接口不接受请求方指定排序
        return ResultUtils.success(certificateService.listFrontCertificates(certificateQueryRequest));
    }
}
