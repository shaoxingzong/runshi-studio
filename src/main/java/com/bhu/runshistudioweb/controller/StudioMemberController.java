package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.service.StudioMemberService;
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

/**
 * 成员接口：管理端增删改查 + 一个「我的档案」查询
 *
 * 业务规则：团队成员不对外展示。
 * 成员档案只作为后台内部数据存在，对外唯一的成员信息出口是项目详情里的「参与成员」
 * （/project/detail，产品确认保留的例外）。
 *
 * Controller 的职责边界与 UserController 一致：只做三件事——接参、调 Service、
 * 包成 BaseResponse；业务规则全部在 Service 层，这里不写 if-else 业务分支。
 *
 * 本类全部接口的鉴权：
 * - /member/add、/update、/delete、/get、/list/page：全部要求
 * {@code @SaCheckRole} 为 admin；
 * - /member/mine：{@code @SaCheckLogin}——登录用户看自己的成员档案，
 * 是全部接口里唯一的非 admin 接口。
 *
 * 白名单里没有登记任何 /member/* 路径：成员档案不对外，游客没有查看的理由。
 * 因此管理端接口即便忘了加角色注解，也仍然会被全局拦截器挡住（未登录得到 A0201），
 * 但那只防住了「没登录的人」，防不住「登录了但不是管理员的人」——角色注解不能省。
 *
 * 返回类型：管理端返回 MemberVO（含 userId / sortOrder 等内部字段），
 * 只在 admin 面前出现。脱敏的 MemberFrontVO 仍然保留，但唯一的对外出处是
 * 项目详情里的「参与成员」与 /member/mine（看自己的档案）——
 * 后者返回的也是脱敏视图，因为前端拿它只是为了判断「我是不是成员、要不要显示发帖入口」。
 *
 * 管理端维护绑定关系不在这里，而在 MemberCertificateController 与 MemberProjectController，
 * 避免本类职责膨胀。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/member/list/page。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/member")
@Tag(name = "成员模块", description = "官网成员展示（匿名可访问）与管理员成员管理")
@RequiredArgsConstructor
public class StudioMemberController {

    /**
     * 构造器注入（{@code @RequiredArgsConstructor} + final）：装配后不可变，
     * 依赖缺失在启动期就失败，而不是等到真正调用时才 NPE
     */
    private final StudioMemberService studioMemberService;

    // ==================== 当前登录用户：查自己的在队成员档案 ====================

    /**
     * 我的成员档案（当前登录用户）
     *
     * <p>为什么这样设计：判断「我是不是在队成员」必须以 studio_member 表为准，
     * 而不是前端本地存储里的角色标签——角色是权限标签，可能与档案不同步
     * （管理员改了角色未必建档案，成员毕业时档案状态改了角色未必跟着改）。
     * 前端只按角色判断，就会出现「发帖按钮在那儿，点了却被后端拒绝」。
     * 前端拿它决定要不要显示「发帖」「签到」「刷题」这些入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 从登录态取当前账号 ID；未登录走不到这里，{@code @SaCheckLogin} 会先拦下；</li>
     *     <li>② 按账号查「在队」成员档案（member_status 为在队）。认表不认角色——
     *     与考勤、刷题模块同一套判定；</li>
     *     <li>③ 查到就转成脱敏 VO，查不到返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：为什么用 null 表示「不是成员」而不是抛异常——
     * 「不是成员」是完全正常的查询结果，普通注册用户就是这个结果。
     * 真正的拦截（A0301）发生在发帖、打卡这些业务接口里，那里才该报错。
     * 让查询接口抛异常，会把「查询」和「鉴权」两件事混在一起：
     * 前端只想问「要不要显示按钮」，却得先 try-catch 一个业务异常。
     *
     * @return 在队的成员档案（脱敏视图）；不是成员、或已毕业离队时返回 null，
     * 前端必须判空——这是本类唯一可能返回 null data 的接口
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）时抛出
     */
    @GetMapping("/mine")
    @SaCheckLogin
    @Operation(summary = "【登录用户】我的成员档案", description = "在队则返回档案；不是成员返回 null")
    public BaseResponse<MemberFrontVO> mine() {
        // ① 从登录态取当前账号 ID；未登录走不到这里，@SaCheckLogin 会先拦下
        long userId = StpUtil.getLoginIdAsLong();
        // ② 与考勤、刷题同一套判定：查在队成员，认表不认角色
        StudioMember member = studioMemberService.getActiveMemberByUserId(userId);
        // ③ 脱敏后返回：不含 userId / sortOrder / 审计字段（MemberFrontVO 本身就是白名单）
        return ResultUtils.success(member == null ? null : studioMemberService.getMemberFrontVO(member));
    }

    // ==================== 管理端：全部要求 admin 角色 ====================

    // 这里刻意没有「C 端：官网展示，匿名可访问」的接口（成员列表 / 成员证书 / 成员详情）：
    // 「团队成员不对外展示」这条产品规则要求成员档案只作为后台内部数据，
    // 因此它们既不在本类里，也不在 SaTokenMvcConfig 的白名单里。
    // 成员档案只经管理端读取：/member/list/page 与 /member/get。
    //
    // 一处有意保留的例外：项目详情（/project/detail）仍返回参与成员，用于项目介绍。

    /**
     * 管理员新增成员档案
     *
     * <p>为什么这样设计：两个与普通 CRUD 不同的校验点。绑定账号（userId 非空时）
     * 必须「账号存在」且「未被其他成员绑定」——一个账号只能对应一份档案，
     * 否则同一个人会有两个成员身份，考勤与刷题都会算重。并发竞态由唯一索引兜底：
     * 应用层查重负责给出可读提示，唯一索引负责保证不出现脏数据，两者不能互相替代。
     * 入学年份限定在合理区间，是为了拒绝「二〇二六级」这类字符串输入。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（姓名、入学年份必填），
     *     请求体整体为 null 时它不触发；</li>
     *     <li>② 交给 Service：校验账号存在且未被占用、校验年份区间、落库；</li>
     *     <li>③ 返回新成员 ID（雪花算法生成的 19 位长整型，接口层序列化成字符串防前端丢精度）。</li>
     * </ol>
     *
     * <p>容易踩的坑：userId 是选填的。新增成员时可以先不绑定账号，之后再通过更新接口绑定；
     * 但一旦绑定，就不能再绑到第二份档案上。若前端为了「省一步」在新增时就传一个
     * 随便填的 userId，接口会返回 A0402「账号不存在」——那不是系统故障，是数据不对。
     *
     * @param memberAddRequest 新增请求：姓名与入学年份必填；userId 为可选的账号绑定
     * @return 新成员 ID（19 位，JSON 里是字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数不合法或账号已被绑定（A0401）、绑定账号不存在（A0402）、入库失败（B0002）时抛出
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增成员", description = "可绑定系统账号；绑定校验「账号存在且未被占用」")
    public BaseResponse<Long> addMember(@RequestBody @Valid MemberAddRequest memberAddRequest) {
        // ① @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(memberAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 账号占用检查在 Service 里；并发下由唯一索引兜底，不靠应用层查询保证
        return ResultUtils.success(studioMemberService.addMember(memberAddRequest));
    }

    /**
     * 管理员更新成员档案
     *
     * <p>为什么这样设计：部分更新语义——请求体里为 null 的字段不会被改动，
     * 前端「只改头像」时不必回传整行数据。改绑账号时会排除自身再校验唯一性：
     * 把成员 A 绑定到「已属于 A 自己」的账号是合法的，若不做排除，
     * 重复提交同一个 userId 会被误判为「已被其他成员绑定」，看起来像 bug。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：校验成员存在、按需校验账号唯一性（排除自身）、
     *     只把白名单字段写库；</li>
     *     <li>③ 返回布尔值。true 表示更新命中了一行，失败会抛异常而不是返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：更新字段必须由服务端写死成白名单，不能把 DTO 直接转成实体整体 updateById——
     * 那样前端能顺手改 deletedAt 与审计字段。另外「传空串」与「不传」是两件事：
     * 不传是不改，传空串是想清空，而 NOT NULL 列不允许清空，必须拦成 A0401。
     *
     * @param memberUpdateRequest 更新请求：id 必填；其余字段为 null 表示保持原值
     * @return true 表示更新成功（失败抛异常，不会返回 false）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数不合法或账号已被其他成员绑定（A0401）、成员或账号不存在（A0402）时抛出
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新成员", description = "字段为 null 表示不修改；改绑账号时校验唯一性")
    public BaseResponse<Boolean> updateMember(@RequestBody @Valid MemberUpdateRequest memberUpdateRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(memberUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 「排除自身的唯一性校验」是这里最容易漏的一步，它在 Service 里
        return ResultUtils.success(studioMemberService.updateMember(memberUpdateRequest));
    }

    /**
     * 管理员删除成员档案（逻辑删除）
     *
     * <p>为什么这样设计：逻辑删除（只把 deleted_at 标成非 0，行仍留在表里）能保住审计线索。
     * 因为唯一索引带着删除标记，删除之后该成员绑定过的账号会被释放，
     * 可以重新绑定到新档案——这一点很重要：若账号被永久占用，
     * 一个人离队再入队就永远绑不回自己原先的账号。
     * 删除是「主表写 + 关联表写」两次操作，必须在同一事务内，
     * 且顺序是先清「成员-证书」「成员-项目」关联、再删主表。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（只判请求体：id 上的 @NotNull 由 {@code @Valid} 负责）；</li>
     *     <li>② 交给 Service：查存在性 → 清两类关联 → 逻辑删除主表；</li>
     *     <li>③ 返回布尔值。true 只代表 UPDATE 命中了行，数据并没有从库里消失。</li>
     * </ol>
     *
     * <p>容易踩的坑：删除成员不会连带删除他发过的帖子、评论与刷题记录——
     * 那些记录里存的是账号 ID 而不是成员档案 ID，两者是两套身份。
     * 若业务上要求「离队即清空内容」，那是另一个需求，不该顺手塞进这个方法里。
     *
     * @param deleteRequest 删除请求：id 必填且为正整数
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数不合法（A0401）、成员不存在（A0402）时抛出
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除成员", description = "逻辑删除，数据可追溯；其绑定的账号会被释放")
    public BaseResponse<Boolean> deleteMember(@RequestBody @Valid DeleteRequest deleteRequest) {
        // ① 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 级联清理在 Service 的同一事务里完成；账号释放靠「唯一索引带删除标记」实现
        return ResultUtils.success(studioMemberService.deleteMember(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询成员详情
     *
     * <p>为什么这样设计：查不到时 Service 抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code 为成功但 data 为空」的响应，
     * 它分不清是「确实没有这个成员」还是「接口出错了」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何成员，直接给 A0401，
     *     省掉一次数据库往返，也避免把「参数错」伪装成「成员不存在」；</li>
     *     <li>③ 调 Service 取管理端 VO 并包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用 long 接参：传非数字（例如 ?id=abc）会被兜成 B0001 而不是 A0401。
     * 管理端详情总是带着 id 调，这个粗糙边界可以接受；若 id 来自用户手输的链接，
     * 应改用包装类型 Long 并显式判空。
     *
     * @param id 成员 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 成员信息，记录为 MemberVO（含 userId、sortOrder 与审计字段）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * id 不合法（A0401）、成员不存在（A0402）时抛出
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询成员详情")
    public BaseResponse<MemberVO> getMemberById(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何行，查库只会返回一个误导性的「成员不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // ③ 管理端 VO 含内部字段，因此不能把它复用到任何对外接口上
        return ResultUtils.success(studioMemberService.getMemberById(id));
    }

    /**
     * 管理员分页查询成员列表
     *
     * <p>为什么这样设计：查询参数走 GET（便于分享链接与浏览器缓存），没有请求体；
     * 分页与排序的非法值由 Service 兜底纠正，不会因为「页码传成 0」而报错。
     * 默认排序是置顶权重倒序而不是 id 倒序：官网展示顺序由运营配置的权重决定，
     * 后台列表若按 id 排，管理员看到的顺序就与官网不一致，核对排序时会一头雾水。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 MemberQueryRequest，允许一个参数都不传；</li>
     *     <li>② 交给 Service：纠正分页参数、按白名单应用排序、拼筛选条件、分页查询并转 VO；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid}。查询参数走 GET，校验失败抛的是
     * BindException，全局异常处理器没接它，会变成 B0001「系统错误」；
     * 而分页越界与排序字段非法都应该被纠正而不是报错。排序字段必须走白名单——
     * ORDER BY 的位置无法用占位符参数化，把前端字符串直接拼进去就是 SQL 注入入口。
     *
     * @param memberQueryRequest 查询条件：id / 姓名模糊 / 届别 / 方向 / 职务 / 状态 + 分页与排序；
     *                            允许整体为 null
     * @return 分页结果，记录为 MemberVO（含内部字段）；无数据时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）时抛出
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询成员", description = "支持姓名/方向模糊查询与届别筛选，每页最多 50 条")
    public BaseResponse<Page<MemberVO>> listMemberByPage(MemberQueryRequest memberQueryRequest) {
        // ① Spring 把查询串绑定成 MemberQueryRequest，允许一个参数都不传
        // ②③ 默认按 sort_order 倒序，与官网展示口径一致，后台看到的顺序就是官网顺序
        return ResultUtils.success(studioMemberService.listMemberByPage(memberQueryRequest));
    }
}
