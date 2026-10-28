package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.constant.AuthRateLimitConstant;
import com.bhu.runshistudioweb.config.AuthRateLimitProperties;
import com.bhu.runshistudioweb.manager.AiQueryCountManager;
import com.bhu.runshistudioweb.manager.AuthLockManager;
import com.bhu.runshistudioweb.manager.ClientIpManager;
import com.bhu.runshistudioweb.manager.IpRateLimitManager;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.model.dto.user.UserAddRequest;
import com.bhu.runshistudioweb.model.dto.user.UserQueryRequest;
import com.bhu.runshistudioweb.model.dto.user.UserUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.enums.UserRoleEnum;
import com.bhu.runshistudioweb.model.vo.user.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.user.UserAddResultVO;
import com.bhu.runshistudioweb.model.vo.user.UserVO;
import com.bhu.runshistudioweb.service.NotificationService;
import com.bhu.runshistudioweb.service.UserService;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 用户服务实现：注册 / 登录 / 注销，管理端增删改查与分页
 *
 * 继承 {@code ServiceImpl} 复用单表 CRUD；校验统一走 {@code ThrowUtils.throwIf}，
 * 异常由全局处理器转成标准响应，所以本类不写 try-catch。注册目前只有一次
 * insert，将来若再写第二张表必须补 {@code @Transactional}。
 *
 * 坑：{@code @Slf4j} 不能省。父类自带 MyBatis 的 {@code Log} 字段 log，其 warn
 * 不支持占位参数；Lombok 的静态日志器遮蔽它，才能用 "{}" 写法。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    /**
     * 管理员新增用户且未填写密码时，随机初始密码的长度
     *
     * 不用固定默认口令：知道系统存在的人就能登录所有未改密账号。随机口令只随
     * {@code addUser} 返回值出现一次，后端不存明文，管理员必须当场转交。
     */
    private static final int INIT_PASSWORD_LENGTH = 10;

    /** 账号长度区间，必须与 UserRegisterRequest / UserAddRequest 上的 @Size 保持一致 */
    private static final int MIN_ACCOUNT_LENGTH = 4;
    private static final int MAX_ACCOUNT_LENGTH = 16;

    /** 密码长度区间，必须与各 DTO 上的 @Size 保持一致 */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 20;

    /** 用户状态：0-正常，1-封禁（与 db/user.sql 中 user_status 的注释一致） */
    private static final int USER_STATUS_NORMAL = 0;
    private static final int USER_STATUS_BANNED = 1;

    /** 分页每页条数的默认值与上限（上限是用来防「一次拉全表」的） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /**
     * AI 提问配额计数
     *
     * 只用于读合并值：登录信息里的 aiQueryCount 必须是「DB 基准 + Redis 未落库增量」，
     * 否则用户刚问完看不出数字变化。
     */
    private final AiQueryCountManager aiQueryCountManager;

    /**
     * 客户端 IP 解析（与考勤模块共用同一个 Manager）
     *
     * 必须解析真实 IP：Nginx 部署下 TCP 对端是代理地址，全员同一 IP，限流形同虚设。
     * 只有对端是可信代理时才采信 X-Real-IP / X-Forwarded-For，防伪造头绕过限流。
     */
    private final ClientIpManager clientIpManager;

    /** IP 级双窗口限流（登录 / 注册共用，与游客提问限流同一套实现） */
    private final IpRateLimitManager ipRateLimitManager;

    /** 登录 / 注册限流的阈值与总开关 */
    private final AuthRateLimitProperties authRateLimitProperties;

    /**
     * 账号级登录失败锁定（与 IP 限流互补：IP 限流挡单 IP 高频，本类挡分布式慢速爆破）
     *
     * 爆破有两种形态——「一个 IP 猛试多个账号」与「海量 IP 各试几次」，单靠任一道都拦不全。
     */
    private final AuthLockManager authLockManager;

    /**
     * 站内通知（成员身份授予时告知本人）
     *
     * 依赖方向单向（本类 → 通知服务），通知服务不反向依赖业务服务，否则循环依赖启动即失败。
     */
    private final NotificationService notificationService;

    /**
     * 成员档案 Mapper：用于「授予成员角色前确认档案存在」
     *
     * 注入 Mapper 而非 {@code StudioMemberService}：后者已注入 {@code SysUserMapper}，
     * 反向注入会形成循环依赖，启动即失败（见 HELP.md 第 9 条）。
     */
    private final StudioMemberMapper studioMemberMapper;

    // ==================== C 端：用户自己 ====================

    /**
     * 用户自助注册：校验参数与账号唯一性后，创建一个「最低权限」的普通账号
     *
     * 提示照实给「账号已存在」，防批量扫描交给 IP 限流——改成模糊提示只会卡住正常用户，
     * 攻击者换个账号名照样能探测。角色写死 user：一旦允许前端指定，谁都能注册出 admin。
     *
     * 坑：不能靠 {@code save} 返回值判断重复，唯一索引冲突抛的是
     * {@code DuplicateKeyException}，不 catch 会变成 B0001。
     *
     * @param userAccount 账号，长度 4~16，全局唯一；null 或空白抛 A0401
     * @param userPassword 明文密码，长度 8~20；内部用 BCrypt 加密后才入库
     * @param checkPassword 确认密码，必须与 {@code userPassword} 完全一致
     * @return 新用户的雪花 ID
     * @throws BusinessException 参数不合法、密码不一致、账号已存在（A0401）、限流（A0501）
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword,
                             String remoteAddr, String xRealIp, String xForwardedFor) {
        // 空值兜底：Controller 的 @Valid 已经拦了一道，这里再拦一次是因为
        // Service 也可能被其他入口（管理端、定时任务、测试）直接调用，不能只依赖 Web 层校验
        ThrowUtils.throwIf(StrUtil.isBlank(userAccount) || StrUtil.isBlank(userPassword),
                ErrorCode.PARAMS_ERROR, "用户账号或密码为空");

        // 防批量注册：IP 级限流放在所有校验与查库之前——被拒的请求不产生任何后续开销
        // （注册接口是账号存在性探测器，限流是防批量扫描的主要手段）
        assertRegisterRateAllowed(remoteAddr, xRealIp, xForwardedFor);

        ThrowUtils.throwIf(!userPassword.equals(checkPassword), ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");

        // 长度规则与 DTO 上的 @Size 保持同一套（常量定义在类顶部）：
        // 两边写不同区间的话，会出现「DTO 说最多 16 位、Service 说最多 20 位」的两套标准
        ThrowUtils.throwIf(userAccount.length() < MIN_ACCOUNT_LENGTH || userAccount.length() > MAX_ACCOUNT_LENGTH,
                ErrorCode.PARAMS_ERROR,
                "用户账号长度必须在 " + MIN_ACCOUNT_LENGTH + " 到 " + MAX_ACCOUNT_LENGTH + " 个字符之间");
        ThrowUtils.throwIf(userPassword.length() < MIN_PASSWORD_LENGTH || userPassword.length() > MAX_PASSWORD_LENGTH,
                ErrorCode.PARAMS_ERROR,
                "用户密码长度必须在 " + MIN_PASSWORD_LENGTH + " 到 " + MAX_PASSWORD_LENGTH + " 个字符之间");


        assertAccountNotExists(userAccount);

        // 必须存哈希串：明文入库一旦泄露就等于全网账号沦陷；
        // BCrypt 自带随机盐，所以不需要额外的 salt 字段，也不必担心盐值丢失
        String encryptedPassword = PasswordUtils.encrypt(userPassword);

        SysUser sysUser = new SysUser();
        sysUser.setUserAccount(userAccount);
        sysUser.setUserPassword(encryptedPassword);
        // 昵称：DDL 中 user_name 是 NOT NULL 且没有默认值，不赋值会直接插入失败
        // （报错 Field 'user_name' doesn't have a default value）。
        // 新注册用户还没有昵称，先用账号兜底，后续在「个人资料」里自行修改
        sysUser.setUserName(userAccount);
        // 默认角色 user（普通注册用户）：新注册用户给「最低权限」，绝不能在注册接口里允许前端指定角色
        // （否则任何人都能注册一个 admin，属于最常见的越权漏洞）。
        // 选 user 而不是 member：注册只代表「有了账号」，是否属于工作室成员由后台开通，
        // 这条界线一旦模糊，就会出现「网上随便注册一个号就能改成员档案」的越权问题
        sysUser.setUserRole(UserRoleEnum.USER.getValue());
        // userStatus / aiQueryCount / deletedAt 留空即可：
        // 它们在 DDL 中都有 DEFAULT（0），MyBatis-Plus 插入时会跳过 null 字段，由数据库默认值兜底

        // 用 try / catch 接唯一索引冲突，而不是靠返回值判断：
        // MyBatis-Plus 遇到唯一索引冲突时是抛 DuplicateKeyException，不会返回 false。
        // 分工是：catch 负责并发竞态（唯一索引是最后一道防线，它以异常形式报错），
        // 下面的 throwIf 负责「返回 false 但没有抛异常」的其它失败情况
        boolean saveResult;
        try {
            saveResult = this.save(sysUser);
        } catch (DuplicateKeyException e) {
            // 并发竞态：两个请求同时通过了上面的存在性检查（互相都还没写库），
            // 后到的那条被唯一索引 uk_account_deleted 拦下，走到这里。
            // 必须换成与正常路径一致的可读提示：「账号已存在」本来就是注册接口的标准反馈
            // （见 assertAccountNotExists），并发时返回它不会泄露任何额外信息，
            // 却能让用户知道该换账号重试，而不是收到一句无从下手的「系统错误」
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "注册失败，数据库异常");

        // save 成功后 MyBatis-Plus 会把雪花 ID 回填到实体，这里直接取即可
        return sysUser.getId();
    }

    /**
     * 用户登录：两道防爆破闸门 → 查库比对密码 → 下发 token
     *
     * 顺序按成本从低到高：IP 限流（Redis）→ 账号锁定（Redis）→ 查库 + BCrypt。
     * BCrypt 故意慢，若放在最前面，防护本身就成压垮 CPU 的那只手。
     *
     * 坑：不能对「账号不存在」累计失败次数——「已锁定」会变成账号存在性探测器。
     *
     * @param userAccount 账号，null 抛 A0401
     * @param userPassword 明文密码，内部用 BCrypt 比对
     * @param remoteAddr TCP 对端地址，用于 IP 限流；可为 null
     * @return 脱敏后的登录用户信息，token 仅此一次返回
     * @throws BusinessException 账号密码错误 A0401、被封禁 A0302、触发限流或锁定 A0501
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword,
                                 String remoteAddr, String xRealIp, String xForwardedFor) {
        ThrowUtils.throwIf(userAccount == null || userPassword == null, ErrorCode.PARAMS_ERROR, "用户账号或密码为空");

        // 防爆破第一层：IP 级限流放在查库与密码比对之前——每一次尝试（含失败的）都计数，
        // 被拒的请求不产生任何数据库开销
        assertLoginRateAllowed(remoteAddr, xRealIp, xForwardedFor);

        // 防爆破第二层：账号级失败锁定检查（补 IP 限流挡不住的分布式慢速爆破）。
        // 放在查库之前：被锁的请求连数据库都不该碰
        authLockManager.assertNotLocked(userAccount);

        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        SysUser user = this.getOne(queryWrapper);

        // 防账号枚举：账号不存在与密码错误使用同一个提示文案。
        // 若分开提示（"账号不存在"/"密码错误"），攻击者可用两种报错扫出一批有效账号，再集中爆破。
        // 短路写法还有一个作用：user 为 null 时根本不会调用 matches，
        // 因此「账号不存在」这条路径比「账号存在」更快，但差异小到无法用来做时序探测
        boolean passwordMatched = user != null && PasswordUtils.matches(userPassword, user.getUserPassword());
        if (!passwordMatched && user != null) {
            // 只在账号存在时累计失败：账号不存在时没有可锁对象，
            // 且对不存在的账号也锁定会让「已锁定」提示成为账号存在性探测器
            authLockManager.recordFailure(userAccount);
        }
        ThrowUtils.throwIf(!passwordMatched, ErrorCode.PARAMS_ERROR, "账号或密码错误");

        // 封禁校验放在密码校验之后：此时已确认密码正确，攻击者无法再通过报错差异探测账号状态
        ThrowUtils.throwIf(isBanned(user), ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");

        // 登录成功：清零该账号的失败计数——凭据已验对，此前的失误一笔勾销
        authLockManager.clear(userAccount);

        // 登录成功：Sa-Token 会把 loginId 写入会话（Redis，见 sa-token-redis-template），
        // 并按 yml 配置把 token 写进响应 Cookie
        StpUtil.login(user.getId());

        LoginUserVO loginUserVO = this.getLoginUserVO(user);
        // token 只在登录这一刻返回，前端需保存并在后续请求里带上（请求头 satoken: xxx）
        loginUserVO.setToken(StpUtil.getTokenValue());
        return loginUserVO;
    }

    /**
     * 取当前登录用户的实体（服务端内部用）
     *
     * 返回实体而非 VO，是因为鉴权、审计要完整字段（含密码哈希串），因此绝不能直接
     * 序列化进响应体，对外必须再走 {@link #getLoginUserVO(SysUser)}。
     *
     * 未登录时抛异常而不是返回 null：否则每个调用方都得判空，漏一处就是一次 NPE。
     *
     * @return 当前登录用户的完整实体，含密码哈希串；调用方必须自行脱敏后再对外输出
     * @throws BusinessException 未登录、用户已不存在或已被封禁时抛出（A0201）
     */
    @Override
    public SysUser getLoginUser() {
        // isLogin 会检查 token 是否有效/未过期，未登录时本方法不允许返回 null，
        // 否则调用方（Controller）就得自己判空，容易漏
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");

        long loginId = StpUtil.getLoginIdAsLong();
        SysUser user = this.getById(loginId);
        // token 还在但用户已不存在：典型场景是账号被逻辑删除，
        // 此时必须按「未登录」处理并把原因记下来，不能返回 null 让上游 NPE
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        // 封禁校验：token 在 Redis 里的有效期最长可达 7 天（sa-token.timeout），
        // 若只依赖登录时的那一次 isBanned 检查，被封禁的用户在 token 过期前
        // 仍可正常使用全部登录态接口（发帖、评论、点赞……）。
        // 与上一行同口径按「未登录」处理：前端拿到 A0201 会跳登录页，
        // 而登录接口对封禁账号有明确提示（「账号已被封禁」），链路自洽。
        // 注意这只是兜底——真正的即时生效靠 updateUser 里的踢会话（token 直接失效）
        ThrowUtils.throwIf(isBanned(user), ErrorCode.NOT_LOGIN_ERROR, "账号已被封禁");

        return user;
    }

    /**
     * 把用户实体转成「用户自己看」的 VO（带 token 字段，不带密码）
     *
     * 脱敏靠目标类的字段结构实现：{@code LoginUserVO} 里没有 {@code userPassword}，
     * 怎么拷都带不出去；手动置空在实体新增敏感字段时会失效。
     *
     * aiQueryCount 覆盖为合并值：库里只是基准，最近几次提问的增量还在 Redis 里等回刷。
     *
     * @param user 用户实体，允许为 null
     * @return 脱敏后的 VO；入参为 null 时返回 null，调用方必须判空
     */
    @Override
    public LoginUserVO getLoginUserVO(SysUser user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        // 用属性名自动拷贝，避免手写一长串 setXxx（漏一个字段就是线上 bug）；
        // VO 中没有 userPassword 字段，因此密码哈希串不会被拷贝进去，这是脱敏的关键
        BeanUtils.copyProperties(user, loginUserVO);
        // aiQueryCount 覆盖为合并值：库里的是「基准」，最近几次提问的增量还在 Redis 里
        // 等定时回刷。只返回库值的话，用户会看到「问了好几次，数字一动不动」
        if (user.getId() != null) {
            loginUserVO.setAiQueryCount(
                    aiQueryCountManager.merge(user.getId(), user.getAiQueryCount()));
        }
        return loginUserVO;
    }

    /**
     * 注销登录：清掉当前这一端的会话
     *
     * 未登录时抛 A0201 而不是静默成功——含糊的成功会掩盖「token 早过期」这类登录态问题。
     * 作用范围只是当前账号 + 当前这一端，其他设备的会话不受影响。
     *
     * @return 恒为 true；{@code StpUtil.logout()} 无返回值，这里表达的是动作已执行
     * @throws BusinessException 未登录时抛出（A0201）
     */
    @Override
    public boolean userLogout() {
        // 未登录时直接失败：让前端明确知道「当前本来就没有登录态」，
        // 而不是返回一个含糊的成功，掩盖了 token 过期这类问题
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        // 注销会同时失效服务端会话（Redis）与客户端 Cookie，两处都清才算真正退出
        StpUtil.logout();
        return true;
    }

    // ==================== 管理端：调用方必须已通过 @SaCheckRole("admin") ====================

    /**
     * 管理员新增用户：创建一个账号，并返回「本次生效的初始密码」（明文，仅此一次）
     *
     * 不与自助注册合并，只差「允许指定角色与状态」这一处——合并后注册接口就能传
     * role=admin，谁都能注册出管理员。密码留空则随机生成，不用固定默认口令：
     * 固定值意味着知道系统存在的人就能登录所有未改密账号。
     *
     * 坑：明文口令只返回这一次，后端不存也查不回来，丢了只能重置；日志只能记
     * 用户 ID 与事件，绝不能记口令。
     *
     * @param userAddRequest 新增请求，不能为 null；账号必填（4~16），密码留空则随机生成，
     *                       角色不传按 user 处理，状态不传按 0（正常）
     * @return 新用户 ID 与本次生效的初始密码；管理员指定了密码时原样回显
     * @throws BusinessException 账号为空或已存在（A0401）、角色非法（A0401）、入库失败（B0001）
     */
    @Override
    public UserAddResultVO addUser(UserAddRequest userAddRequest) {
        // ① 账号是后面每一步的前提，空值先挡掉
        ThrowUtils.throwIf(userAddRequest == null || StrUtil.isBlank(userAddRequest.getUserAccount()),
                ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        String userAccount = userAddRequest.getUserAccount().trim();
        // ② 提前查一次：唯一索引才是最终防线，但先查能给出明确的「已存在」提示
        assertAccountNotExists(userAccount);

        // ③ 角色：不传按最低权限（user）处理，传了就必须是枚举内的合法取值。
        // 这里绝不能用 @Pattern 把取值写死在注解上——枚举一改，注解就过期了
        UserRoleEnum role = resolveRole(userAddRequest.getUserRole(), UserRoleEnum.USER);

        // ④ 初始密码：管理员指定则用他指定的，留空则随机生成。
        // 随机化而不是固定默认口令——固定值意味着任何知道本系统的人
        // 都能登录所有未及时改密的账号；随机口令只随返回值出现一次，攻击者无法预知。
        // 字符集剔除 0/O/1/l/I（见 PasswordUtils.generateRandomPassword），转告时不会听错
        String initialPassword = StrUtil.isNotBlank(userAddRequest.getUserPassword())
                ? userAddRequest.getUserPassword()
                : PasswordUtils.generateRandomPassword(INIT_PASSWORD_LENGTH);

        // ⑤ 组装实体：密码只以 BCrypt 哈希形式入库
        SysUser sysUser = new SysUser();
        sysUser.setUserAccount(userAccount);
        // 无论哪种情况都只存 BCrypt 哈希串，明文绝不入库
        sysUser.setUserPassword(PasswordUtils.encrypt(initialPassword));
        // 昵称为空时用账号兜底（user_name 是 NOT NULL 且无默认值）
        sysUser.setUserName(blankToDefault(userAddRequest.getUserName(), userAccount));
        sysUser.setUserAvatar(userAddRequest.getUserAvatar());
        sysUser.setUserRole(role.getValue());
        // 状态不传按正常处理：显式赋值比依赖 DDL 默认值更直观，也避免以后 DDL 变更时行为漂移
        sysUser.setUserStatus(userAddRequest.getUserStatus() == null
                ? USER_STATUS_NORMAL : userAddRequest.getUserStatus());

        boolean saveResult = this.save(sysUser);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增用户失败，数据库异常");

        // ⑥ 返回 ID 与明文口令：口令只出现这一次，不返回它管理员就永远拿不到
        UserAddResultVO result = new UserAddResultVO();
        result.setId(sysUser.getId());
        result.setInitialPassword(initialPassword);
        // warn 级日志记录「事件」而非口令本身：日志里出现明文口令等于把它散播到日志系统。
        // 这条提醒的是运维——有账号被创建，初始口令待转交，别只当成普通 info
        log.warn("管理员新增用户，已生成随机初始密码 | userId={} | 待管理员通过安全渠道转交本人并督促改密",
                sysUser.getId());
        return result;
    }

    /**
     * 管理员更新用户（部分更新）：改资料、改角色、改状态、重置密码，并按副作用联动
     *
     * 几个守卫挡的都是「一次误操作导致系统不可维护」：不能封禁或降级自己（权限实时查库，
     * 失去 admin 后已无人能改回来）；授予 member / admin 前必须已有成员档案（准入判据是
     * 档案而非角色，否则成员入口不出现、进去也报 A0301）；封禁后要立刻踢会话。
     *
     * 传 null 表示不修改，依赖 MyBatis-Plus 跳过 null 字段；代价是本接口无法清空字段。
     *
     * @param userUpdateRequest 更新请求，不能为 null；id 必填；其余字段为 null 表示不修改，
     *                          密码传值即重置
     * @return true 表示更新动作已执行，<b>不代表数据真的变了</b>
     * @throws BusinessException 用户不存在（A0402）；角色非法、授予成员身份但无档案、
     *         封禁或降级当前登录账号（均为 A0401）
     */
    @Override
    public boolean updateUser(UserUpdateRequest userUpdateRequest) {
        ThrowUtils.throwIf(userUpdateRequest == null || userUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        Long id = userUpdateRequest.getId();

        // ① 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        SysUser existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        String userRole = userUpdateRequest.getUserRole();
        // ② 角色合法性校验：非法值一旦入库，权限判定会静默失效（拿不到任何角色却也不报错）
        ThrowUtils.throwIf(userRole != null && UserRoleEnum.of(userRole) == null,
                ErrorCode.PARAMS_ERROR, "用户角色不合法，仅支持 " + UserRoleEnum.valuesText());

        // ③ 授予成员角色前，必须确认该账号已有成员档案。
        //
        // 为什么必须拦：成员功能的准入判据是「studio_member 档案的 user_id 命中当前账号」，
        // 而 user_role 只是权限标签。只把角色改成 member、却没有档案时，
        // 界面上的成员入口不会出现（前端也按档案判断），
        // 即使从别处进了页面，后端接口也会返回 A0301「仅工作室成员可用」。
        // 这正是「有时候不显示、有时候显示了也报错」的一个来源。
        //
        // 提示里明确告诉管理员下一步该做什么（去「成员管理」建档），
        // 而不是只说「不行」——管理员卡在这里往往就是不知道还有第二步。
        if (UserRoleEnum.MEMBER.getValue().equals(userRole)
                || UserRoleEnum.ADMIN.getValue().equals(userRole)) {
            boolean hasProfile = studioMemberMapper.exists(
                    new LambdaQueryWrapper<StudioMember>()
                            .eq(StudioMember::getUserId, id));
            ThrowUtils.throwIf(!hasProfile, ErrorCode.PARAMS_ERROR,
                    "该账号还没有成员档案，授予成员/管理员身份前请先到「成员管理」为他建档并绑定此账号");
        }

        // ④ 防锁死守卫：管理员不能把自己封禁、也不能把自己降级。
        // 权限判定每次都查库（见 StpInterfaceImpl），一旦自己失去 admin 角色会立刻无法管理，
        // 而且此时已经没有任何管理员能通过接口把你改回来，只能去数据库修数据
        if (Objects.equals(id, StpUtil.getLoginIdAsLong())) {
            ThrowUtils.throwIf(Integer.valueOf(USER_STATUS_BANNED).equals(userUpdateRequest.getUserStatus()),
                    ErrorCode.PARAMS_ERROR, "不能封禁当前登录的账号");
            ThrowUtils.throwIf(userRole != null && UserRoleEnum.ADMIN != UserRoleEnum.of(userRole),
                    ErrorCode.PARAMS_ERROR, "不能把当前登录的管理员降级");
        }

        SysUser update = new SysUser();
        update.setId(id);
        // ⑤ 只 set 白名单字段：DTO 里的值原样搬过来，null 交给 MyBatis-Plus 跳过
        update.setUserName(userUpdateRequest.getUserName());
        update.setUserAvatar(userUpdateRequest.getUserAvatar());
        update.setUserRole(userRole);
        update.setUserStatus(userUpdateRequest.getUserStatus());
        // 密码：传了才重置，传 null 表示不动原密码
        if (StrUtil.isNotBlank(userUpdateRequest.getUserPassword())) {
            update.setUserPassword(PasswordUtils.encrypt(userUpdateRequest.getUserPassword()));
        }

        // updateById 默认跳过 null 字段，这正是「传 null 表示不修改」语义成立的基础
        boolean updated = this.updateById(update);

        // ⑥ 封禁即时生效：把目标账号的会话全部踢掉（token 直接失效），
        // 而不是等 7 天后 token 自然过期——否则被封禁的用户在窗口期内
        // 仍可用全部登录态接口。防锁死守卫已保证管理员不会封禁自己，无误踢风险。
        // 只在这一种场景踢：改昵称/头像/角色都不影响既有会话的有效性
        if (updated && Integer.valueOf(USER_STATUS_BANNED).equals(userUpdateRequest.getUserStatus())) {
            StpUtil.logout(id);
        }

        // ⑦ 成员身份授予通知：角色从非成员变为 member 时告知本人。
        // 此前用户被授予成员身份后毫无感知，只能等管理员口头通知——
        // 而「身份变了」这种事件本来就该有系统反馈（工作台/刷题入口也是这时出现的）。
        // 只认 member 不认 admin：管理员极少且互相认识，授予时本人就在场；
        // 给 admin 也发通知只会增加噪音。existing 上的旧角色是判「变化」的依据——
        // 否则每次改资料（昵称/头像）都会重复发一条
        if (updated && userRole != null
                && UserRoleEnum.MEMBER.getValue().equals(userRole)
                && !UserRoleEnum.MEMBER.getValue().equals(existing.getUserRole())) {
            notificationService.notify(id, StpUtil.getLoginIdAsLong(),
                    NotificationTypeEnum.MEMBER_GRANTED.getValue(), "USER", id,
                    "你已获得工作室成员身份",
                    "管理员已授予你工作室成员身份：工作台（考勤）与刷题功能已为你开放");
        }
        return updated;
    }

    /**
     * 管理员按 id 查用户详情（返回脱敏 VO，接口契约见 UserService）
     *
     * @param id 用户 ID
     * @return 用户信息；不存在时抛 A0402，不返回 null
     */
    @Override
    public UserVO getUserById(long id) {
        SysUser user = this.getById(id);
        // 查不到要给出明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return this.getUserVO(user);
    }

    /**
     * 管理员删除用户（逻辑删除）：让账号消失，并立刻让它的登录态失效
     *
     * 必须拦住「删除当前登录账号」：删掉自己会立刻失去管理端权限，且已无人能改回来。
     * 用逻辑删除（写 deleted_at）而非物理删除：数据可追溯，账号名随唯一索引释放可重新注册。
     * 删除后必须踢会话，否则手里已下发的 token 在过期前仍然有效。
     *
     * 坑：不级联清理成员档案与业务数据——「删了用户，成员档案还在」是正常现象。
     * 本方法不幂等，再删一次抛 A0402，前端应视为「已经删掉了」。
     *
     * @param id 用户 ID；等于当前登录账号时抛 A0401
     * @return true 表示删除成功；false 表示该行已被并发删除（此时不踢会话）
     * @throws BusinessException 试图删除自己（A0401）、用户不存在（A0402）
     */
    @Override
    public boolean deleteUser(long id) {
        // ① 防误删守卫：删掉自己会立刻失去管理端权限（权限判定实时查库），且无法自行恢复
        ThrowUtils.throwIf(id == StpUtil.getLoginIdAsLong(),
                ErrorCode.PARAMS_ERROR, "不能删除当前登录的账号");

        // ② 先确认存在：removeById 对不存在的行只返回 false，前端会分不清原因
        SysUser existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        // ③ 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，
        // 数据仍可追溯，且账号名会因为「唯一索引带 deleted_at」而被释放，可以重新注册
        boolean removed = this.removeById(id);

        // ④ 与封禁同一理由：删除后踢掉该账号全部会话，不让已下发的 token 继续有效
        // （getLoginUser 的 user==null 分支能兜住「当前用户」接口，
        //   但直接拿 loginId 干活的端点看不到那一层，踢会话才是根治）
        if (removed) {
            StpUtil.logout(id);
        }
        return removed;
    }

    /**
     * 管理端分页查询用户：按条件筛选、排序并返回脱敏后的分页结果
     *
     * 参数非法不报错而是纠正：这些参数走 GET，抛异常会落到全局兜底变成 B0001，
     * 把「页码传错」显示成「服务器故障」。排序字段必须走白名单——ORDER BY 无法用
     * 占位符参数化，前端字符串直接拼进 SQL 就是注入入口。pageSize 有硬上限 50，
     * 否则一个 pageSize=100000 就能把整表读进内存。
     *
     * 坑：模糊查询不转义 {@code %} 与 {@code _}，搜「100%」会匹配到无关账号（LIKE 固有语义，刻意接受）。
     *
     * @param userQueryRequest 查询条件，允许为 null（表示无条件查第一页）；字段为 null
     *                         表示该条件不参与筛选；current 与 pageSize 传错会被纠正
     * @return 分页结果，记录为脱敏后的 UserVO；无数据时 records 为空列表，永不为 null
     */
    @Override
    public Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest) {
        // ① 不传条件等于无条件查第一页，兜一个空对象而不是报错
        UserQueryRequest query = userQueryRequest == null ? new UserQueryRequest() : userQueryRequest;

        // ② 分页参数兜底纠正（而不是抛异常）：页码传错没必要让整个查询失败，
        // 收敛到合理区间继续查更符合预期，也避免「参数错」和「查询无结果」混在一起难以排查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时，前端一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        // ③ 拼筛选条件：每个条件都带「是否生效」的布尔参数，为 false 时不拼进 SQL
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不会拼进 SQL，省去了手写 if 判断
        wrapper.eq(query.getId() != null, SysUser::getId, query.getId());
        // 模糊查询：MyBatis-Plus 会用占位符传参，不存在 SQL 注入；
        // 但用户输入的 % 与 _ 仍会被当作通配符（属于 LIKE 的固有语义，这里不额外转义）
        wrapper.like(StrUtil.isNotBlank(query.getUserAccount()), SysUser::getUserAccount, query.getUserAccount());
        wrapper.like(StrUtil.isNotBlank(query.getUserName()), SysUser::getUserName, query.getUserName());
        // 精确匹配：角色/状态是枚举值域，精确匹配才能命中索引、结果也才可预期
        wrapper.eq(StrUtil.isNotBlank(query.getUserRole()), SysUser::getUserRole, query.getUserRole());
        wrapper.eq(query.getUserStatus() != null, SysUser::getUserStatus, query.getUserStatus());
        // ④ 排序走白名单：ORDER BY 无法用占位符参数化，前端字符串绝不能直接拼进 SQL
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // ⑤ 分页在数据库侧完成，转换逐条走 getUserVO（实体带着密码哈希串，不能直接序列化出去）
        Page<SysUser> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toUserVOPage(entityPage);
    }

    /**
     * 实体转管理端 VO（含角色与状态，不含密码）
     *
     * @param user 用户实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    @Override
    public UserVO getUserVO(SysUser user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        // VO 中没有 userPassword 字段，密码哈希串不会被拷贝过去（与 LoginUserVO 同一套脱敏思路）
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 校验账号未被占用
     *
     * 这里必须明确告知「账号已存在」，不能像登录那样统一成「账号或密码错误」：
     * 注册的对手是普通用户（需要明确反馈），防的是批量扫描，交给 IP 限流。
     *
     * @param userAccount 待校验账号
     */
    private void assertAccountNotExists(String userAccount) {
        // count 而不是 selectOne：只需要判断存在性，避免把整行数据查回内存
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        long count = this.count(queryWrapper);
        // 注意：这里必须明确告知「账号已存在」，不能像登录那样统一成「账号或密码错误」——
        // 注册的对手是普通用户（需要明确反馈才能完成注册），登录的对手是攻击者（提示差异是他唯一的探测手段）。
        // 注册接口本身就是账号存在性探测器，这是它的功能；防的是「被批量扫描」，
        // 靠 IP 级限流（见 assertRegisterRateAllowed），而不是靠隐藏提示。
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号已存在");
    }

    /**
     * 注册限流：按 IP 双窗口计数，超限抛 A0501
     *
     * 开关关闭（测试环境由 surefire 统一置 false）时直接放行。
     */
    private void assertRegisterRateAllowed(String remoteAddr, String xRealIp, String xForwardedFor) {
        if (Boolean.FALSE.equals(authRateLimitProperties.getEnabled())) {
            return;
        }
        String ip = clientIpManager.resolveClientIp(remoteAddr, xRealIp, xForwardedFor);
        ipRateLimitManager.assertAllowed(ip, AuthRateLimitConstant.REGISTER_KEY_PREFIX,
                authRateLimitProperties.getRegisterMinuteLimit(),
                authRateLimitProperties.getRegisterDailyLimit(), "注册");
    }

    /**
     * 登录限流：按 IP 双窗口计数，超限抛 A0501
     *
     * 与注册限流的差别只是阈值与键前缀（被登录限流不该连带堵死注册，反之亦然）。
     * 开关关闭（测试环境）时直接放行。
     */
    private void assertLoginRateAllowed(String remoteAddr, String xRealIp, String xForwardedFor) {
        if (Boolean.FALSE.equals(authRateLimitProperties.getEnabled())) {
            return;
        }
        String ip = clientIpManager.resolveClientIp(remoteAddr, xRealIp, xForwardedFor);
        ipRateLimitManager.assertAllowed(ip, AuthRateLimitConstant.LOGIN_KEY_PREFIX,
                authRateLimitProperties.getLoginMinuteLimit(),
                authRateLimitProperties.getLoginDailyLimit(), "登录");
    }

    /**
     * 解析角色：空值用兜底角色，非法值直接报错
     *
     * @param userRole 前端传入的角色，可为空
     * @param defaultRole 兜底角色
     * @return 角色枚举
     */
    private UserRoleEnum resolveRole(String userRole, UserRoleEnum defaultRole) {
        if (StrUtil.isBlank(userRole)) {
            return defaultRole;
        }
        UserRoleEnum role = UserRoleEnum.of(userRole);
        ThrowUtils.throwIf(role == null, ErrorCode.PARAMS_ERROR,
                "用户角色不合法，仅支持 " + UserRoleEnum.valuesText());
        return role;
    }

    private void applySort(LambdaQueryWrapper<SysUser> wrapper, String sortField, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        switch (sortField == null ? "" : sortField) {
            case "id" -> wrapper.orderBy(true, isAsc, SysUser::getId);
            case "userAccount" -> wrapper.orderBy(true, isAsc, SysUser::getUserAccount);
            case "userName" -> wrapper.orderBy(true, isAsc, SysUser::getUserName);
            case "userStatus" -> wrapper.orderBy(true, isAsc, SysUser::getUserStatus);
            case "createdAt" -> wrapper.orderBy(true, isAsc, SysUser::getCreatedAt);
            // 默认按 id 倒序：雪花 ID 是时间有序的，因此 id 倒序 ≈ 创建时间倒序，
            // 且走主键索引，比按 created_at 排序少一次 filesort
            default -> wrapper.orderByDesc(SysUser::getId);
        }
    }

    /**
     * 实体分页结果转 VO 分页结果
     *
     * @param entityPage 实体分页
     * @return VO 分页（保留 total/size/current，前端分页组件依赖这三个值）
     */
    private Page<UserVO> toUserVOPage(Page<SysUser> entityPage) {
        Page<UserVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getUserVO).toList());
        return voPage;
    }

    /**
     * 判断用户是否已被封禁
     *
     * @param user 用户实体
     * @return 状态为封禁返回 true
     */
    private boolean isBanned(SysUser user) {
        return user.getUserStatus() != null && user.getUserStatus() == USER_STATUS_BANNED;
    }

    /**
     * 空白字符串替换为默认值（账号、昵称等场景）
     *
     * @param value 原值，可为 null/空白
     * @param defaultValue 默认值
     * @return 原值非空白时返回原值，否则返回默认值
     */
    private String blankToDefault(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    /**
     * 管理端：重置某个用户的 AI 提问配额
     *
     * 先确认用户存在再重置：不存在时静默返回 0 会让管理员以为「点成功了」，
     * 而列表里那个人其实没变。真正的清空动作（DB 与 Redis 两处）交给
     * {@link AiQueryCountManager#reset}，这里只负责准入与存在性校验。
     *
     * @param id 目标用户 ID
     * @return 重置前的真实用量
     * @throws com.bhu.runshistudioweb.exception.BusinessException 用户不存在时抛 A0402
     */
    @Override
    public int resetAiQueryCount(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不合法");
        SysUser user = this.getById(id);
        // 查不到按 A0402 而不是静默成功：管理端需要知道「这个人已经被删了」
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return aiQueryCountManager.reset(id);
    }
}
