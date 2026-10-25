package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.user.UserAddRequest;
import com.bhu.runshistudioweb.model.dto.user.UserLoginRequest;
import com.bhu.runshistudioweb.model.dto.user.UserQueryRequest;
import com.bhu.runshistudioweb.model.dto.user.UserRegisterRequest;
import com.bhu.runshistudioweb.model.dto.user.UserUpdateRequest;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.user.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.user.UserAddResultVO;
import com.bhu.runshistudioweb.model.vo.user.UserVO;
import com.bhu.runshistudioweb.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：C 端（用户自己）+ 管理端（管理员增删改查）
 *
 * Controller 的职责边界：只做三件事——接参、调 Service、把结果包成 BaseResponse。
 * 参数长度、密码强度、业务规则全部在 Service 层，Controller 里不写 if-else 业务分支，
 * 这样同一套规则在别的入口（管理端、定时任务）复用时不会有两份实现。
 *
 * 返回类型一律是 BaseResponse<T>：前端只需一个拦截器即可统一处理成败，
 * 业务错误码由全局异常处理器兜住（见 GlobalExceptionHandler），
 * 所以这里不写 try-catch，出了异常直接往外抛。
 *
 * 两组接口的界线（本类最重要的约定）：
 * - C 端：/user/register、/user/login、/user/current、/user/logout。
 * 前两个在 Sa-Token 白名单里（匿名可访问，因为还没有账号或还没登录的人必须能调），
 * 后两个走「默认要求登录」；
 * - 管理端：/user/add、/user/update、/user/delete、/user/get、/user/list/page，
 * 全部要求 {@code @SaCheckRole} 为 admin。
 *
 * 特别注意：C 端与管理端虽然都返回用户信息，但返回的 VO 不同——
 * 自己看自己用 LoginUserVO（带 token 与 AI 配额），管理员看别人用 UserVO（带角色与状态）。
 * 两者都不含密码字段：脱敏靠「目标 VO 里没有这个字段」实现，
 * 而不是「拷完再手动置空」，因此不会因为某次改动漏了一行而泄露密码哈希。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/user/login。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户注册、登录、注销、当前用户查询，以及管理员用户管理")
@RequiredArgsConstructor
public class UserController {

    /**
     * 构造器注入（{@code @RequiredArgsConstructor} + final）：装配后不可变，
     * 依赖缺失在启动期就失败，而不是等到真正调用时才 NPE
     */
    private final UserService userService;

    /** Nginx 惯用配置 proxy_set_header X-Real-IP $remote_addr; 对应的头名 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 逐跳追加的代理链，形如 客户端, 代理1, 代理2 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    // ==================== C 端：用户自己 ====================

    /**
     * 用户注册
     *
     * <p>为什么这样设计：注册接口本质是一个「账号存在性探测器」——
     * 「该账号已存在」这个提示是功能需要，不能隐藏（否则用户不知道换个名字）。
     * 防批量扫描因此只能靠 IP 级限流，而限流需要客户端真实 IP。
     * 所以这里多收一个请求对象，把三个 IP 原始值取出来传下去；
     * Service 层刻意不认识 HTTP（这样限流规则可以脱离容器直接单测）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（账号长度、密码格式），
     *     请求体整体为 null 时它不触发，后面取值会抛空指针被兜成 B0001；</li>
     *     <li>② 在当前请求线程里取出 IP 三参并调 Service。这一步必须同步完成——
     *     请求对象离开方法就失效了，不能丢进异步分支；</li>
     *     <li>③ 返回新用户 ID（雪花算法生成的 19 位长整型，接口层序列化成字符串防前端丢精度）。</li>
     * </ol>
     *
     * <p>容易踩的坑：自助注册永远不允许指定角色。角色由服务端固定为普通用户——
     * 若 DTO 里有 userRole 字段且被采纳，任何人都能注册一个管理员账号。
     * 管理端的 /user/add 才允许指定角色，那是管理动作。
     *
     * @param userRegisterRequest 注册请求：账号、密码、确认密码均必填
     * @param request 当前请求，只用于取 IP 三参（供 Service 做防批量注册限流），不读取业务参数
     * @return 新注册用户的 ID（19 位，JSON 里是字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、两次密码不一致、
     * 账号已存在（均 A0401）、触发 IP 限流（A0501）、入库失败（B0001）时抛出
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "校验账号唯一性与密码格式后创建账号，新账号默认为普通用户角色；按 IP 限流防批量注册")
    public BaseResponse<Long> userRegister(@RequestBody @Valid UserRegisterRequest userRegisterRequest,
                                           HttpServletRequest request) {
        // ① @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，
        // 因此这里兜一层，避免下面直接 getXxx() 抛 NPE 变成 500 系统错误
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);

        // ② IP 三参必须在当前请求线程内取好：它们与 socket 层绑定，离开请求就取不到了
        long id = userService.userRegister(
                userRegisterRequest.getUserAccount(),
                userRegisterRequest.getUserPassword(),
                userRegisterRequest.getCheckPassword(),
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR));
        // ③ 只回 ID：注册成功不代表已登录，前端应当引导用户去登录页
        return ResultUtils.success(id);
    }

    /**
     * 用户登录
     *
     * <p>为什么这样设计：登录成功后由 Sa-Token 建立会话（存放在 Redis），
     * 并返回带 token 的用户信息。返回的 LoginUserVO 是脱敏视图——
     * 它没有密码字段，但有 token 与 AI 配额，前端把 token 存下来、
     * 之后每个请求带上即可（请求头 satoken: xxx）。
     * 登录同样带 IP 三参：登录接口没有验证码、没有失败锁定，
     * 等于把「账号口令空间」开放给无限次尝试，防爆破只能靠 IP 级限流。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 取出账号、密码与 IP 三参交给 Service：限流检查 → 查账号 → BCrypt 比对密码
     *     → 建立会话 → 组装带 token 的 VO；</li>
     *     <li>③ 包成统一响应体。登录失败一律抛异常，不会返回一个「空的用户对象」。</li>
     * </ol>
     *
     * <p>容易踩的坑：账号不存在与密码错误必须返回同一个错误提示，
     * 否则「账号存在」这件事就被泄露了，攻击者可以先枚举账号再爆破密码。
     * 这个口径在 Service 里，Controller 不要为了「提示更友好」把它拆成两种。
     *
     * @param userLoginRequest 登录请求：账号与密码均必填
     * @param request 当前请求，只用于取 IP 三参（供 Service 做防爆破限流），不读取业务参数
     * @return 脱敏后的登录用户信息（含 token，前端需要保存）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 账号或密码错误（A0401）、
     * 账号被封禁（A0302）、触发 IP 限流（A0501）时抛出
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "校验密码并下发 token（含 Cookie，见 sa-token 配置）；按 IP 限流防爆破")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody @Valid UserLoginRequest userLoginRequest,
                                               HttpServletRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);

        // ② 会话建立与密码比对都在 Service 里；IP 三参只用于限流判定
        LoginUserVO loginUserVO = userService.userLogin(
                userLoginRequest.getUserAccount(),
                userLoginRequest.getUserPassword(),
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR));
        // ③ token 在 VO 里，前端必须保存它；后续请求靠它识别身份
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     *
     * <p>为什么这样设计：典型用途是前端刷新页面后，用它判断「是否还处于登录状态」
     * 并把用户信息写回状态管理，而不是把用户信息存本地反复使用——
     * 那样管理员改了昵称或头像，前端不会更新，用户会看到一份过期资料。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 调 Service 取当前登录用户实体。未登录或 token 过期都会在这里抛异常，
     *     交给全局异常处理器转成 A0201；</li>
     *     <li>② 把实体转成 VO 再返回。这一步不能省——实体里有密码哈希串，
     *     直接返回等于把密码送到前端；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有挂 {@code @SaCheckLogin}，看起来像个匿名接口。
     * 实际上它不在白名单里，全局拦截器会先做登录校验，未登录根本进不到方法体；
     * Service 里还会再判一次。两层都在，才允许这里省掉注解——
     * 若哪天这个路径被加进白名单，本方法会立刻变成「未登录也能调」，
     * 而第一层保护就只剩 Service 里那一次判定了。
     *
     * @return 当前登录用户的脱敏信息（不含 token，含角色与 AI 配额）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 或 token 有效但用户已不存在（A0201）时抛出
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前登录用户", description = "未登录时由全局异常处理器返回 A0201")
    public BaseResponse<LoginUserVO> getLoginUser() {
        // ① 未登录 / token 过期都会在这里抛异常，交给全局异常处理器转成 A0201
        SysUser loginUser = userService.getLoginUser();
        // ②③ 返回 VO 而不是实体：实体里有密码哈希串，直接返回等于把密码送到前端
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户注销（退出登录）
     *
     * <p>为什么这样设计：注销是幂等（重复调用结果一致）语义上的清理动作，
     * 但这里刻意不做成静默成功——未登录时直接抛 A0201。
     * 原因是：若未登录也返回 true，前端就无法区分「注销成功」与
     * 「token 早就过期了、你其实一直没登录」，这类含糊的成功会掩盖真实的登录态问题。
     * 作用范围是「当前账号 + 当前这一端」：Sa-Token 默认按 token 注销，
     * 在手机与电脑同时登录时，注销一处不会把另一处也踢下线。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 调 Service：先判断是否登录（未登录抛 A0201），再清掉服务端会话与客户端 Cookie；</li>
     *     <li>② 包成统一响应体。失败会抛异常，因此 true 是唯一可能的成功取值。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法也没有挂登录注解，与 /current 同理——
     * 它不在白名单里，拦截器已经挡过一层。若把它加进白名单，
     * 「未登录调用注销」就会变成常态，而 Service 里那句「未登录抛 A0201」的提示
     * 会让用户困惑：明明只是想退出，为什么报未登录。
     *
     * @return true 表示注销成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）时抛出
     */
    @PostMapping("/logout")
    @Operation(summary = "用户注销", description = "清除服务端会话与客户端 Cookie")
    public BaseResponse<Boolean> userLogout() {
        // ①② 未登录时 Service 会抛 A0201 而不是静默返回 true——含糊的成功会掩盖登录态问题
        return ResultUtils.success(userService.userLogout());
    }

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 管理员新增用户
     *
     * <p>为什么这样设计：与自助注册的差别是这里允许指定角色与状态——
     * 这正是管理动作的一部分（比如给同事开一个管理员账号）。但角色值必须落在枚举范围内，
     * 不允许写进任意字符串。密码留空时使用随机生成的初始密码，而不是固定默认值：
     * 固定口令意味着任何知道本系统的人都能登录所有未及时改密的账号。
     * 随机口令只随本方法的返回值出现一次，后端不存明文、也无法再次查询，
     * 因此管理员必须立即把它转交本人——页面关掉就再也看不到了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：校验账号唯一、角色合法，密码为空时生成随机初始密码，
     *     用 BCrypt 加密后落库；</li>
     *     <li>③ 返回「新用户 ID + 本次生效的初始密码」。这个 VO 是唯一会带明文密码的响应，
     *     前端必须立即展示给管理员并提示「只显示这一次」。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把这个接口的返回结构复用到别处。UserAddResultVO 里带明文密码，
     * 它存在的唯一理由是「管理员要把初始密码转交给本人」；若有人拿它做列表元素，
     * 密码就会出现在日志、缓存与浏览器的历史响应里。
     *
     * @param userAddRequest 新增请求：账号必填；密码留空则使用随机初始密码；
     *                       可指定角色与状态
     * @return 新用户 ID 与本次生效的初始密码（明文，仅此一次返回）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 账号已存在或角色非法（A0401）、入库失败（B0002）时抛出
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增用户", description = "可指定角色与状态；密码留空时生成随机初始密码（仅创建响应返回一次）")
    public BaseResponse<UserAddResultVO> addUser(@RequestBody @Valid UserAddRequest userAddRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 随机初始密码只在这一次响应里出现，服务端只存哈希、无法再次取回
        return ResultUtils.success(userService.addUser(userAddRequest));
    }

    /**
     * 管理员更新用户
     *
     * <p>为什么这样设计：部分更新语义——请求体里为 null 的字段不会被改动，
     * 因此前端「只改昵称」时不必回传整行数据。Service 里内置两个防锁死守卫：
     * 不允许管理员封禁自己、也不允许把自己的管理员角色降级。
     * 因为权限是实时查库判定的，一旦自己失去 admin 角色会立刻无法管理，
     * 且此时没有任何管理员能通过接口改回来，只能去数据库修数据。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：校验用户存在、角色合法、防锁死守卫、只写白名单字段。
     *     重置密码时同样用 BCrypt 加密，不接受明文直接入库；</li>
     *     <li>③ 返回布尔值。true 表示更新命中了一行，失败会抛异常而不是返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：改密码与改昵称走同一个接口，但它们的风险等级完全不同。
     * 若前端把「重置密码」做成一个静默调用（不提示、不二次确认），
     * 管理员可能只是想改个备注，却顺手把用户密码改成了空值。
     * 后端能做的只是拒绝空串，做不了「意图确认」——那必须在前端完成。
     *
     * @param userUpdateRequest 更新请求：id 必填；其余字段为 null 表示保持原值，
     *                          可重置密码、调整角色与状态
     * @return true 表示更新成功（失败抛异常，不会返回 false）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 角色非法或触发防锁死守卫（A0401）、用户不存在（A0402）时抛出
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新用户", description = "字段为 null 表示不修改；可重置密码、调整角色与状态")
    public BaseResponse<Boolean> updateUser(@RequestBody @Valid UserUpdateRequest userUpdateRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(userUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 「不允许把自己降级或封禁」的守卫在 Service 里，Controller 不重复判
        return ResultUtils.success(userService.updateUser(userUpdateRequest));
    }

    /**
     * 管理员删除用户（逻辑删除）
     *
     * <p>为什么这样设计：逻辑删除（只把 deleted_at 标成非 0，行仍留在表里）能保住审计线索。
     * 因为唯一索引带着删除标记，删除之后该账号名可以被重新注册——
     * 若账号名被永久占用，一个人注销后想用原名重新注册就永远做不到。
     * Service 里还有一条守卫：不允许删除自己（否则管理员可能把自己锁在系统外面）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（只判请求体：id 上的 @NotNull 由 {@code @Valid} 负责）；</li>
     *     <li>② 交给 Service：查存在性、校验不是自己、逻辑删除；</li>
     *     <li>③ 返回布尔值。true 只代表 UPDATE 命中了行，数据并没有从库里消失。</li>
     * </ol>
     *
     * <p>容易踩的坑：删除用户不会连带删除他发过的帖子与评论——
     * 那些记录里存的是账号 ID，删号后作者名会变成空或「已注销」。
     * 这是有意的：内容治理与账号治理是两件事，删号不该顺手抹掉公开内容的历史。
     *
     * @param deleteRequest 删除请求：id 必填且为正整数
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 试图删除自己（A0401）、用户不存在（A0402）时抛出
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除用户", description = "逻辑删除，数据可追溯；账号名可被重新注册")
    public BaseResponse<Boolean> deleteUser(@RequestBody @Valid DeleteRequest deleteRequest) {
        // ① 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 「不能删自己」的守卫在 Service 里：没有它，管理员可能把自己锁在系统外
        return ResultUtils.success(userService.deleteUser(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询用户详情
     *
     * <p>为什么这样设计：不让 Controller 直接调单表查询——那样 Controller 就认识了实体，
     * 很容易顺手把含密码哈希串的实体返回出去。统一由 Service 返回脱敏后的 VO，
     * 出口只有一处，审查时只需要看那几个转换方法。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何用户，直接给 A0401，
     *     省掉一次数据库往返，也避免把「参数错」伪装成「用户不存在」；</li>
     *     <li>③ 调 Service 取脱敏 VO 并包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用 long 接参：传非数字（例如 ?id=abc）会被兜成 B0001 而不是 A0401。
     * 管理端详情总是带着 id 调，这个粗糙边界可以接受；若 id 来自用户手输的链接，
     * 应改用包装类型 Long 并显式判空，才能给出准确的参数提示。
     *
     * @param id 用户 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 脱敏后的用户信息，记录为 UserVO（含角色与状态，不含密码）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * id 不合法（A0401）、用户不存在（A0402）时抛出
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询用户详情")
    public BaseResponse<UserVO> getUserById(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何用户，查库只会返回一个误导性的「用户不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // ③ Service 返回的是 VO 而不是实体，密码哈希串没有出口
        return ResultUtils.success(userService.getUserById(id));
    }

    /**
     * 管理员分页查询用户列表
     *
     * <p>为什么这样设计：查询参数走 GET（便于分享链接与浏览器缓存），没有请求体；
     * 分页与排序的非法值由 Service 兜底纠正，不会因为「页码传成 0」而报错。
     * 排序字段必须走白名单：ORDER BY 的位置无法用占位符参数化，
     * 把前端传来的字符串直接拼进 SQL 就是最典型的注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 UserQueryRequest，允许一个参数都不传；</li>
     *     <li>② 交给 Service：纠正分页参数、按白名单应用排序、拼筛选条件、分页查询并转 VO；</li>
     *     <li>③ 包成统一响应体。列表记录同样是脱敏 VO，不含密码。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid}。查询参数走 GET，校验失败抛的是
     * BindException，全局异常处理器没接它，会变成 B0001「系统错误」；
     * 而分页越界与排序字段非法都应该被纠正而不是报错。
     * 另外这里刻意不用 Spring Data 的分页对象——它绑不上查询参数，
     * 与 MyBatis-Plus 的分页也是两套模型。
     *
     * @param userQueryRequest 查询条件：id 精确、账号与昵称模糊、角色与状态精确、分页与排序；
     *                         允许整体为 null
     * @return 分页结果，记录为 UserVO（脱敏）；无数据时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）时抛出
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询用户", description = "支持账号/昵称模糊查询，每页最多 50 条")
    public BaseResponse<Page<UserVO>> listUserByPage(UserQueryRequest userQueryRequest) {
        // ① Spring 把查询串绑定成 UserQueryRequest，允许一个参数都不传
        // ②③ 分页归一化与排序白名单都在 Service 里，Controller 不做二次校验
        return ResultUtils.success(userService.listUserByPage(userQueryRequest));
    }
}
