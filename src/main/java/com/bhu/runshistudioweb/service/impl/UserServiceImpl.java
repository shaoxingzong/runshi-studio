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
 * 用户服务实现
 *
 * 为什么加 @Slf4j：本类继承 ServiceImpl，它自带一个
 * org.apache.ibatis.logging.Log 实例字段（MP 用来打 SQL 日志），其
 * warn(String) 只接受单个字符串、不支持占位参数。Lombok 生成的
 * 静态 SLF4J 日志器会遮蔽那个继承字段，让本类里的 log.xxx("...{}", arg)
 * 走标准占位写法（与 PostServiceImpl 同一模式）。
 * 遮蔽不影响父类：父类方法里的 log 仍在它自己的作用域内解析。
 *
 * 为什么继承 ServiceImpl<SysUserMapper, SysUser>：
 * MyBatis-Plus 的 ServiceImpl 已经把单表 CRUD（save / getById / count / updateById 等）
 * 用 Mapper 实现好了，这里只需要写「业务动作」，不用重复写 CRUD。
 * 注意第一个泛型必须是 Mapper 类型，写错会导致启动时找不到对应 Bean。
 *
 * 校验统一走 ThrowUtils.throwIf：条件成立即抛 BusinessException，
 * 由全局异常处理器转成标准响应，所以本类不需要写 try-catch，也不需要层层返回错误码。
 *
 * 注意：涉及多写的操作（注册）应保证原子性。当前注册只有一次 insert 是安全的；
 * 若以后注册还要写成员档案等第二张表，必须加 @Transactional，
 * 否则中途失败会留下「有账号无档案」的脏数据。
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
     * 为什么是随机而不是固定默认值：固定默认口令意味着任何知道本系统存在的人
     * 都能登录所有未及时改密的账号——把安全性寄托在「每个人都会立即改密」上，而现实里总有人不会。
     * 随机口令只随 addUser 的返回值出现一次（后端不存明文、查不回来），
     * 管理员必须当场转交本人。10 位是「足够复杂度」与「转告不出错」的平衡点。
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
     * 只用于「读出合并值」：登录信息里的 aiQueryCount 必须是
     * 「DB 基准 + Redis 未落库增量」，否则用户刚问完就看不出数字变化。
     */
    private final AiQueryCountManager aiQueryCountManager;

    /**
     * 客户端 IP 解析（与考勤模块共用同一个 Manager）
     *
     * 登录 / 注册限流按 IP 计数，必须先拿到「真实客户端 IP」：
     * 直接取 TCP 对端在 Nginx 部署下拿到的是代理地址（全员同一 IP，限流形同虚设），
     * 因此复用考勤已验证的 resolveClientIp——只有对端是可信代理时才采信
     * X-Real-IP / X-Forwarded-For，防伪造头绕过限流。
     */
    private final ClientIpManager clientIpManager;

    /** IP 级双窗口限流（登录 / 注册共用，与游客提问限流同一套实现） */
    private final IpRateLimitManager ipRateLimitManager;

    /** 登录 / 注册限流的阈值与总开关 */
    private final AuthRateLimitProperties authRateLimitProperties;

    /**
     * 账号级登录失败锁定（与 IP 限流互补：IP 限流挡单 IP 高频，本类挡分布式慢速爆破）
     *
     * 为什么两道都要：爆破的两种形态——「一个 IP 对多个账号猛试」与
     * 「海量 IP 各试几次」——单靠任何一道都拦不全，且两道都不依赖对方可用。
     */
    private final AuthLockManager authLockManager;

    /**
     * 站内通知（成员身份授予时告知本人）
     *
     * 依赖方向单向（本类 → 通知服务），通知服务不反向依赖任何业务服务——
     * 否则形成循环依赖，Spring 启动即失败。
     */
    private final NotificationService notificationService;

    /**
     * 成员档案 Mapper：用于「授予成员角色前确认档案存在」
     *
     * <p><b>为什么注入 Mapper 而不是 {@code StudioMemberService}</b>：
     * 项目全局约定「关联服务的实现类只准注入 Mapper，不准反向注入主表 Service」
     * （见 HELP.md 第 9 条）——{@code StudioMemberServiceImpl} 已经注入了
     * {@code SysUserMapper}，这里若再注入它就会形成循环依赖，启动即失败。
     * 而且本处只需要一个「按 user_id 数一下档案是否存在」的查询，用 Mapper 最直接。
     */
    private final StudioMemberMapper studioMemberMapper;

    // ==================== C 端：用户自己 ====================

    /**
     * 用户自助注册：校验参数与账号唯一性后，创建一个「最低权限」的普通账号
     *
     * <p>为什么这样设计：注册是<b>唯一一个完全面向陌生人的写接口</b>，
     * 它同时承担两个互相冲突的职责——给正常用户明确反馈（「账号已存在」必须说清楚，
     * 否则用户不知道该换一个），以及不给扫描者提供批量探测的便利。
     * 两者无法同时满足，最终的选择是：<b>提示照实给，防扫描交给 IP 级限流</b>。
     * 若为了防探测而把提示改成模糊的「注册失败」，正常用户会卡在注册页反复试错，
     * 而攻击者本来就能通过「换一个账号名再试」得到同样的信息。
     *
     * <p>为什么角色写死成 user 而不是由前端传入：注册接口一旦允许指定角色，
     * 任何人都能注册一个 admin，这是最常见的越权漏洞。同理也不给 member——
     * 注册只代表「有了账号」，是不是工作室成员由管理员在后台建档决定，
     * 这条界线一旦模糊，就会出现「网上随便注册个号就能改成员档案」。
     *
     * <p>执行步骤（顺序即代码里的注释顺序）：
     * <ol>
     *     <li>空值兜底。Controller 的 {@code @Valid} 已经拦了一道，这里再拦一次，
     *     是因为 Service 也可能被管理端、定时任务或测试直接调用，不能只依赖 Web 层；</li>
     *     <li><b>先限流</b>，再校验密码与长度。这是成本顺序：被限流拒绝的请求
     *     不产生任何后续开销，而密码比对与长度校验虽然便宜，却也是白白消耗。
     *     更重要的是，注册接口是账号存在性探测器，限流是防批量扫描的主要手段，
     *     它必须挡在「探测动作」之前；</li>
     *     <li>两次密码一致性、账号与密码的长度校验。长度区间与 DTO 上的 {@code @Size}
     *     共用类顶部的常量——两边各写一套的话，迟早出现「DTO 说最多 16 位、
     *     Service 说最多 20 位」的两套标准；</li>
     *     <li>查账号是否已存在。放在长度校验之后：格式都不合法的账号名不值得查库；</li>
     *     <li>加密。必须存 BCrypt 哈希串而不是明文，明文入库一旦泄露等于全网账号沦陷；</li>
     *     <li>组装实体并落库，同时接住唯一索引冲突（见「容易踩的坑」）；</li>
     *     <li>返回新账号的 ID，由 {@code save} 回填到实体上。</li>
     * </ol>
     *
     * <p>容易踩的坑：<b>不能靠 {@code save} 的返回值判断账号是否重复。</b>
     * MyBatis-Plus 遇到唯一索引冲突时抛的是 {@code DuplicateKeyException}，不会返回 false。
     * 并发场景下两个请求可能同时通过第 ④ 步的存在性检查（互相都还没写库），
     * 后到的那条由数据库唯一索引拦下——如果这里不 catch，它就会一路冒到全局兜底，
     * 变成 B0001「系统错误」，用户既不知道原因也无从重试。
     *
     * @param userAccount 用户账号，长度 4~16，全局唯一；传 null 或空白会抛 A0401
     * @param userPassword 明文密码，长度 8~20；方法内部用 BCrypt 加密后才入库
     * @param checkPassword 确认密码，必须与 {@code userPassword} 完全一致（含大小写）
     * @param remoteAddr TCP 对端地址，用于 IP 限流；可为 null（此时限流放行）
     * @param xRealIp X-Real-IP 头的原始值，可为 null
     * @param xForwardedFor X-Forwarded-For 头的原始值，可为 null
     * @return 新用户的雪花 ID（19 位 Long，序列化时已转成字符串，前端不会丢精度）
     * @throws BusinessException 参数为空或格式不合法（A0401）、两次密码不一致（A0401）、
     *                           账号已存在（A0401）、触发 IP 限流（A0501）、入库失败（B0001）时抛出
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
     * 用户登录：先过两道防爆破闸门，再查库比对密码，最后才下发 token
     *
     * <p>为什么这样设计：登录接口是整套系统里唯一一个「陌生人可以无限次尝试」的入口，
     * 它必须自己承担防爆破的责任。防护拆成两道互补的闸，缺任何一道都有明显漏洞：
     * <b>IP 级限流</b>挡的是「一个 IP 对多个账号猛试」，<b>账号级锁定</b>挡的是
     * 「海量 IP 各试几次」的分布式慢速爆破——后者每次尝试的频次都远低于任何单 IP 上限，
     * 单靠限流永远发现不了。两道闸互不依赖，任一道失效另一道仍然有效。
     *
     * <p>为什么顺序是「先 IP 限流、再账号锁定、最后查库比对密码」——这是<b>按成本从低到高</b>排的：
     * <ul>
     *     <li>IP 限流是一次 Redis 往返，最便宜，且它对每一次尝试都计数（含失败的），
     *     挡在最前面意味着被拒绝的请求不产生任何数据库开销；</li>
     *     <li>账号锁定也是一次 Redis 读，但只有在「该账号已被锁」时才拒绝，
     *     所以它必须排在限流之后——否则被限流的请求还要多花一次 Redis 往返；</li>
     *     <li>查库 + BCrypt 比对最贵（BCrypt 是<b>故意</b>设计成慢的，一次约几十毫秒），
     *     必须放在最后。反过来写的话，攻击者每次尝试都能让我们跑一次 BCrypt，
     *     防护措施本身就成了压垮 CPU 的那只手。</li>
     * </ul>
     * 这个顺序还有一个附带好处：前两道闸都不需要知道账号是否存在，
     * 因此它们对「账号不存在」与「账号存在」的行为完全一致，不泄露任何信息。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>空值兜底，避免后面的逻辑对 null 做字符串操作；</li>
     *     <li>IP 级限流。它对每次尝试计数，包括那些注定失败的；</li>
     *     <li>账号级锁定检查。放在查库之前，被锁的请求连数据库都不该碰；</li>
     *     <li>按账号查实体。<b>查不到也要继续走完密码比对</b>（这里用短路表达式统一处理），
     *     而不是提前抛「账号不存在」——那会让报错差异变成账号枚举工具；</li>
     *     <li>用 {@code PasswordUtils.matches} 比对密码。失败时<b>只在账号存在时</b>
     *     累计失败次数，理由见「容易踩的坑」；</li>
     *     <li>密码不对就抛统一提示「账号或密码错误」。注意这一步在封禁校验之前，
     *     否则攻击者可以用「已封禁」与「密码错误」的差异反推密码是否猜对；</li>
     *     <li>封禁校验。放在密码校验<b>之后</b>：此时已确认密码正确，
     *     攻击者无法再通过报错差异探测账号状态；</li>
     *     <li>登录成功，清零该账号的失败计数；</li>
     *     <li>建立会话并回填 token。</li>
     * </ol>
     *
     * <p>容易踩的坑（三个）：
     * <ul>
     *     <li><b>不能对「账号不存在」也累计失败。</b>那样一来，「已锁定」这个提示
     *     就变成了账号存在性探测器——能被锁定说明账号存在。攻击者可以先拿一批
     *     猜出来的账号名把它们刷成锁定状态，既完成了探测，又顺手坑了真实用户；</li>
     *     <li><b>不能用 {@code equals} 比较哈希串。</b>BCrypt 每次加密都会生成新的随机盐，
     *     同一明文两次加密的结果并不相等，用 equals 会让登录<b>永远失败</b>，
     *     而且失败得毫无规律（只有极少数恰好撞上同一盐值的情况才会成功）；</li>
     *     <li><b>校验方法本身不抛异常</b>（{@code PasswordUtils.matches} 对非法哈希串
     *     与空密码都只返回 false）。这是刻意的：若它抛异常，一个库里的脏哈希串
     *     就能让登录接口返回 500，攻击者可以据此判断「这个账号的密码字段有问题」。
     *     返回 false 让所有异常输入走同一条「账号或密码错误」路径，行为完全一致。</li>
     * </ul>
     *
     * @param userAccount 用户账号，不能为 null（为 null 抛 A0401）
     * @param userPassword 明文密码，不能为 null；内部用 BCrypt 与库中哈希串比对
     * @param remoteAddr TCP 对端地址，用于 IP 限流；可为 null（此时限流放行）
     * @param xRealIp X-Real-IP 头的原始值，可为 null
     * @param xForwardedFor X-Forwarded-For 头的原始值，可为 null
     * @return 脱敏后的登录用户信息，其中 token 只在登录这一刻返回，前端需保存并在
     *         后续请求里带上（请求头 {@code satoken: xxx}）
     * @throws BusinessException 账号或密码为空（A0401）、账号或密码错误（A0401）、
     *                           账号被封禁（A0302）、触发 IP 限流或账号锁定（A0501）时抛出
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
     * <p>为什么这样设计：本方法返回的是<b>实体</b>而不是 VO，因为它服务于服务端自己的判断
     * （鉴权、审计、守卫），这些地方需要完整字段——包括密码哈希串。
     * 但也正因如此，它<b>绝不能</b>被直接序列化进响应体。要返回给前端，
     * 必须再走一次 {@link #getLoginUserVO(SysUser)} 转成脱敏 VO。
     * 把「内部实体」与「对外 VO」分成两个方法，而不是在同一个对象上按场景置空字段，
     * 是为了让泄露变成一件「做不到」的事，而不是「记得别做」的事。
     *
     * <p>为什么未登录时抛异常而不是返回 null：调用方大多是 Controller，
     * 若返回 null，每个调用方都得自己判空，漏一处就是一次 NPE 变成 B0001。
     * 抛异常则由全局异常处理器统一转成 A0201，前端据此跳登录页，行为一致。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先确认已登录。{@code isLogin} 会检查 token 是否有效、是否过期；</li>
     *     <li>按 loginId 查实体；</li>
     *     <li>查不到时同样按「未登录」处理。典型场景是账号被逻辑删除——
     *     token 还在有效期内，但人已经不存在了。返回 null 会让上游 NPE，
     *     按未登录处理则能让前端干净地回到登录页；</li>
     *     <li>封禁校验。这一步是<b>兜底</b>而不是主防线：token 在 Redis 里的有效期
     *     最长可达数天，若只依赖登录时的那一次检查，被封禁的用户在 token 过期前
     *     仍能使用全部登录态接口。真正的即时生效靠 {@link #updateUser} 里的踢会话。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ④ 步用的错误码是 A0201「未登录」而不是 A0302「已封禁」。
     * 这看起来反直觉，但链路是自洽的：前端拿到 A0201 会跳登录页，
     * 而登录接口对被封禁账号有明确提示（「账号已被封禁」），用户到了登录页就会看到真实原因。
     * 若这里返回 A0302，前端不会跳转，用户会卡在一个「什么都点不动」的页面上。
     *
     * @return 当前登录用户的完整实体，含密码哈希串；调用方必须自行脱敏后再对外输出
     * @throws BusinessException 未登录、token 失效、用户已不存在或已被封禁时抛出（A0201）
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
     * <p>为什么这样设计：脱敏<b>靠目标类的字段结构</b>实现，而不是靠「拷完再手动置空」。
     * {@code LoginUserVO} 里根本没有 {@code userPassword} 这个字段，
     * 因此 {@code BeanUtils.copyProperties} 无论怎么拷都不可能把密码带出去。
     * 这个区别很关键：手动置空的做法在「将来给实体新增一个敏感字段」时会失效，
     * 而结构上不存在字段的做法不会。
     *
     * <p>为什么要在这里覆盖 {@code aiQueryCount}：数据库里存的是「已落库的基准值」，
     * 而最近几次提问的增量还在 Redis 里等定时任务回刷。只返回库值的话，
     * 用户会看到「明明问了好几次，数字一动不动」。合并值才是用户视角的真实配额。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>入参为 null 时返回 null。这是纯转换方法，不该为 null 抛异常——
     *     它不承载任何业务判断；</li>
     *     <li>按属性名自动拷贝；</li>
     *     <li>用合并值覆盖配额字段。先判 id 非空，是因为合并需要按用户 ID 去查 Redis，
     *     id 为空时那次查询毫无意义。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code copyProperties} 只按<b>属性名与类型</b>匹配，类型不一致的属性
     * 会被<b>静默跳过</b>，不报任何错。所以给 VO 加字段时，类型必须与实体完全一致
     * （例如实体是 {@code LocalDateTime}，VO 写成 {@code Date} 就会永远拿到 null）。
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
     * <p>为什么这样设计：注销是<b>幂等（重复调用结果一致）</b>语义上的清理动作，
     * 但这里刻意<b>不</b>做成静默成功——未登录时直接抛 A0201。
     * 原因是：若未登录也返回 true，前端就无法区分「注销成功」与
     * 「token 早就过期了、你其实一直没登录」，这类含糊的成功会掩盖真实的登录态问题。
     *
     * <p>作用范围是「当前账号 + 当前这一端」：Sa-Token 默认按 token 注销，
     * 同一账号在别的设备上登录的会话不受影响。要全端下线需要另传
     * {@code SaLogoutParameter}，本方法不承担这个职责。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>确认当前确实处于登录态，否则抛 A0201；</li>
     *     <li>调用注销。这一步会同时清掉服务端会话（Redis）与客户端 Cookie——
     *     两处都清才算真正退出，只清一处的话，另一个仍能被继续使用。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的 {@code true} 是<b>常量</b>，不是 {@code logout()}
     * 的返回值——{@code StpUtil.logout()} 本身没有返回值。因此不要把它理解成
     * 「服务端确认注销成功」，它表达的是「走到这里说明已登录且注销动作已执行」。
     *
     * @return 恒为 true；走到返回值这一行说明注销动作已经执行完毕
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
     * <p>为什么这样设计：它与自助注册只差一处——允许指定角色与状态，
     * 但正是这一处决定了两者不能合并成一个方法。合并之后注册接口就能被传一个
     * role=admin 的参数，任何人都能注册出管理员，这是最常见的越权漏洞。
     * 因此两条路径各写一份入口，共用同一套底层规则（账号唯一、只存哈希、
     * 昵称为空用账号兜底），而不是共用一个「什么都能传」的方法。
     * 第二个关键取舍是<b>随机生成初始密码而不是用固定默认口令</b>：
     * 固定默认值（例如全站统一发 Runshi@123）意味着任何知道本系统存在的人
     * 都能登录所有未及时改密的账号——安全性被寄托在「每个人都会立即改密」上，
     * 而现实里总有人不会。随机口令只随本方法的返回值出现一次，
     * 后端既不存明文也没有「再查一次」的接口，管理员必须当场转交本人。
     * 生成器还刻意剔除了 0/O/1/l/I 这类易混淆字符（见 PasswordUtils），
     * 因为初始密码往往要靠电话或口头转告。
     * 第三个取舍是角色与状态的默认值写在代码里而不是靠 DDL 默认值：
     * 显式赋值让「不传就是普通用户、不传就是正常状态」这件事在代码里可读，
     * 也避免以后 DDL 变更时行为悄悄漂移。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验账号非空并 trim。放在最前面是因为后面每一步都要用到它，
     *     而且空账号查库没有任何意义；</li>
     *     <li>② 校验账号未被占用。必须在落库之前查——虽然唯一索引才是最终防线，
     *     但提前查一次能给出「账号已存在」这种明确提示，而不是等数据库抛异常；</li>
     *     <li>③ 解析角色：不传按最低权限（user）兜底，传了就必须是枚举内的取值。
     *     这一步必须在组装实体之前，否则非法角色会先被写进实体再被拒绝；
     *     这里刻意不用 {@code @Pattern} 把取值写死在注解上——枚举一改，注解就过期了；</li>
     *     <li>④ 决定初始密码：管理员指定就用他指定的，留空则随机生成。
     *     顺序上必须在第 ⑤ 步加密之前，因为加密只能做一次，不能先生成再回头改；</li>
     *     <li>⑤ 组装实体并落库。密码在这里加密，明文绝不入库；
     *     昵称为空时用账号兜底，因为 user_name 是 NOT NULL 且没有默认值，
     *     不赋值会直接插入失败；</li>
     *     <li>⑥ 返回 ID 与本次生效的初始密码。两者缺一不可——
     *     只返回 ID，管理员就永远拿不到这个账号的口令。</li>
     * </ol>
     *
     * <p>容易踩的坑：初始密码是<b>明文且只返回这一次</b>，后端不存明文、
     * 也没有二次查询的接口，管理员刷新页面后就再也看不到了；
     * 丢了只能走「重置密码」，而不是「再查一次」。
     * 另一个坑在日志：只能记用户 ID 与「已生成初始密码」这个事件，
     * 绝不能把口令打进日志——日志的访问范围往往比数据库更大，
     * 写进去等于把它散播给所有能看日志的人。
     * 还有一点：本方法<b>不幂等</b>，同一账号重复调用第二次会因账号已存在而失败，
     * 这是刻意的——静默返回已有账号会让管理员以为「新建成功了」。
     *
     * @param userAddRequest 新增请求，不能为 null；账号必填（长度 4~16）；
     *                       密码留空则使用随机初始密码（长度 10），
     *                       传了则必须在 8~20 位之间（由 DTO 上的 {@code @Size} 拦下）；
     *                       角色不传按 user 处理，传了必须是 user / member / admin；
     *                       状态不传按 0（正常）处理
     * @return 新用户 ID（雪花算法生成的 19 位 Long，序列化时会转成字符串）
     *         与本次生效的初始密码（明文）；管理员指定了密码时，
     *         这里原样回显他指定的那个，不是随机值
     * @throws BusinessException 账号为空（A0401）、账号已存在（A0401）、
     *         角色非法（A0401）、入库失败（B0001）时抛出
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
     * <p>为什么这样设计：本方法最容易被删掉的部分是那几个守卫，而它们挡的是
     * 「一次误操作导致系统不可维护」的路径：
     * <ul>
     *     <li><b>不能封禁自己、不能把自己降级</b>：权限判定是实时查库的
     *     （见 StpInterfaceImpl），一旦自己失去 admin 角色会立刻无法管理，
     *     而此时已经没有任何管理员能通过接口把你改回来，只能去数据库改数据。
     *     这类「把自己锁在门外」的路径值得在代码里显式挡一道；</li>
     *     <li><b>授予 member / admin 前必须确认该账号已有成员档案</b>：
     *     成员功能的准入判据是 studio_member 档案的 user_id 命中当前账号，
     *     user_role 只是权限标签。只把角色改成 member 却没有档案时，
     *     界面上的成员入口不会出现（前端也按档案判断），即使从别处进了页面，
     *     后端接口也会返回 A0301。这正是「有时候不显示、有时候显示了也报错」的来源；</li>
     *     <li><b>封禁要立刻踢会话</b>：不踢的话，被封禁的用户在 token 自然过期前的
     *     窗口期里仍能用全部登录态接口，封禁就只是一个界面上的标签。</li>
     * </ul>
     * 另一个取舍是「传 null 表示不修改」：它依赖 MyBatis-Plus 的默认策略
     * （null 字段不参与 UPDATE），好处是前端做「只改昵称」的表单时
     * 不必先把整行查出来回传，避免了自己漏字段导致误清空的坑。
     * 代价是无法通过本接口把某个字段清空，真需要时应另设一个语义明确的接口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 非空并先查一次确认用户存在。先查是为了区分
     *     「不存在」与「存在但没改成」——否则 updateById 只返回 false，
     *     前端看到「更新失败」却不知道原因；</li>
     *     <li>② 校验角色合法性。非法值一旦入库，权限判定会静默失效
     *     （拿不到任何角色却也不报错），这类问题极难排查；</li>
     *     <li>③ 授予 member / admin 前查成员档案是否存在。必须在写库之前——
     *     写进去之后再发现没档案，就得回滚一次已经生效的权限变更；</li>
     *     <li>④ 防锁死守卫：只在「目标就是当前登录账号」时生效，
     *     因此必须先取当前登录 ID 再比较。顺序不能与第 ③ 步互换，
     *     因为「不能降级自己」也要用到角色值；</li>
     *     <li>⑤ 组装白名单字段并写库，密码传了才加密重置。
     *     白名单是刻意的：绝不能把 DTO 直接转成实体整体更新，
     *     那样前端能顺手改 deletedAt 与审计字段；</li>
     *     <li>⑥ 封禁时踢掉目标账号的全部会话。放在写库成功之后——
     *     写库失败还去踢人，等于「没封成但把人踢了」；</li>
     *     <li>⑦ 成员身份授予通知：只在「角色从非成员变为成员」时发。
     *     判断依据是查出来的旧角色，而不是「这次传了角色」——
     *     否则每次改昵称都会重复发一条通知。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步最容易被当成多余校验删掉，但它挡的是真实故障
     * （「成员入口不出现」「进了页面又报无权限」）。
     * 另一个坑是第 ④ 步的判断写成了 {@code userRole != null && ADMIN != of(userRole)}，
     * 而不是「传了角色就拒绝」——管理员把自己从 admin 改成 admin 是合法的，
     * 那等于什么都没改，不该报错。
     * 还有一点：本方法<b>不幂等</b>，重复封禁会重复踢会话（通知则会被幂等键挡掉）。
     *
     * @param userUpdateRequest 更新请求，不能为 null；id 必填且必须为正数；
     *                          其余字段为 null 表示不修改；密码传值即重置（内部 BCrypt 加密后覆盖），
     *                          传 null 或空串表示不动原密码
     * @return true 表示更新动作已执行成功；注意它<b>不代表数据真的变了</b>——
     *         什么都没改时也是 true，调用方不要用它判断「有没有变化」
     * @throws BusinessException 用户不存在（A0402）；角色非法、授予成员身份但无档案、
     *         封禁或降级当前登录账号（均为 A0401）时抛出
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
     * <p>为什么这样设计：三个关键取舍。
     * 第一，<b>必须拦住「删除当前登录账号」</b>：删掉自己会立刻失去管理端权限
     * （权限判定实时查库，查不到用户就等于没有角色），而此时已经没有任何管理员
     * 能通过接口把自己恢复，只能去数据库改数据。这是一条不可逆的误操作路径，
     * 所以它在最前面就被挡掉，而不是等用户点完确认再报错。
     * 第二，<b>逻辑删除而不是物理删除</b>：实际执行的是
     * {@code UPDATE ... SET deleted_at = 毫秒时间戳}，数据仍可追溯；
     * 而且账号名的唯一索引带着 deleted_at，删除后这个账号名会被释放，
     * 本人可以重新注册——物理删除会让这条「历史」彻底消失。
     * 第三，<b>删除后必须踢会话</b>：不踢的话，该账号手里的 token 在自然过期前
     * 仍然有效。「取当前登录用户」那条路径能兜住「用户已被删除」的情况，
     * 但直接拿登录 ID 干活的端点看不到那一层，踢会话才是根治。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 防误删守卫：id 等于当前登录账号时直接拒绝。
     *     这一步放在最前，连一次查库都不该为它浪费；</li>
     *     <li>② 查实体确认存在。必须在删除之前——
     *     removeById 对不存在的行只返回 false，前端会看到「删除失败」却不知道为什么；</li>
     *     <li>③ 逻辑删除。返回 false 说明这一行在查出来之后被并发删掉了，
     *     此时不该继续往下踢会话；</li>
     *     <li>④ 踢掉该账号的全部会话。用 removed 做条件，
     *     是为了不出现「没删成却把人踢下线」这种反向影响。</li>
     * </ol>
     *
     * <p>容易踩的坑：删除<b>不会</b>级联清理该账号的成员档案与业务数据。
     * 那些是独立的业务实体，级联删除属于另一个决策（要不要保留历史记录），
     * 不在本方法职责内——但这也意味着「删了用户，成员档案还在」是正常现象，
     * 排查「为什么成员列表里还有这个人」时先看档案表，而不是怀疑删除没生效。
     * 另一个坑是本方法<b>不幂等</b>：对同一个 id 再删一次，第二次会因查不到而抛 A0402，
     * 前端做「删除后刷新列表」时要把 A0402 当成「已经删掉了」处理，而不是弹报错。
     *
     * @param id 用户 ID；等于当前登录账号时抛 A0401（不允许删除自己）
     * @return true 表示删除成功；false 表示该行在查出来之后已被并发删除
     *         （此时没有踢会话，因为这个人已经不存在了）
     * @throws BusinessException 试图删除自己（A0401）、用户不存在或被逻辑删除（A0402）时抛出
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
     * <p>为什么这样设计：这里刻意<b>不做「参数非法就报错」</b>，而是把分页与排序参数
     * 兜底纠正到合理区间。两个理由：一是这些参数走 GET 传递，校验失败抛出的异常
     * 会落到全局兜底变成 B0001，把「页码传错了」显示成「服务器故障」，
     * 排查方向被完全带偏；二是页码传错没必要让整个查询失败，
     * 纠正成第 1 页继续查更符合用户预期。
     * 另一个关键取舍是<b>排序字段必须走白名单</b>：ORDER BY 的位置无法用占位符参数化，
     * 把前端传来的字符串直接拼进 SQL 就是最典型的注入入口，
     * 而且它不像 WHERE 那样能被预编译挡住。因此这里用 switch 把有限的几个字段名
     * 映射成实体方法引用，认不出的字段一律走默认排序，而不是原样拼进去。
     * 还有一点：每页条数有硬上限（50）。不设上限的话，前端一个
     * {@code pageSize=100000} 就能把整表读进内存，这是最容易发生的自我 DoS。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时兜一个空对象：不传任何条件等于「无条件查第一页」，
     *     这是合理用法，不该报错；</li>
     *     <li>② 纠正分页参数：页码下限 1、每页条数收敛到上限。
     *     必须在拼查询条件之前完成，因为分页对象要用到这两个值；</li>
     *     <li>③ 拼筛选条件。每个条件都带一个「是否生效」的布尔参数
     *     （{@code query.getId() != null} 这种），为 false 时该条件不会拼进 SQL，
     *     省去了手写 if 判断；精确匹配用于 id / 角色 / 状态（枚举值域，走索引），
     *     模糊匹配只用于账号与昵称；</li>
     *     <li>④ 应用排序（白名单映射）。放在筛选之后，
     *     这样即使排序字段非法也不影响已经拼好的 WHERE；</li>
     *     <li>⑤ 分页查询并转成脱敏 VO 分页对象。转换必须逐条走 getUserVO，
     *     而不是让实体直接序列化出去——实体带着密码哈希串。</li>
     * </ol>
     *
     * <p>容易踩的坑：模糊查询没有转义 {@code %} 与 {@code _}。
     * 用户输入这两个字符时它们仍会被当作通配符，例如搜「100%」会匹配到一堆无关账号。
     * 这是 LIKE 的固有语义，这里刻意接受（转义会让「搜索」变得反直觉），
     * 但要在排查「为什么搜出多余结果」时先想到它。
     * 另一个坑是别把 {@code sortOrder} 的判断写成「等于 asc 才升序，否则降序」之后
     * 又忘了默认值——本实现里「不传或非法」都按降序处理，这是明确的约定，
     * 不要在调用方再猜一遍。
     *
     * @param userQueryRequest 查询条件，允许整体为 null（表示无条件查第一页）；
     *                         字段为 null 表示「该条件不参与筛选」，不是「查 null 值」；
     *                         current 与 pageSize 传错会被纠正而不是报错；
     *                         sortField 只认白名单内的字段名，认不出时按 id 倒序
     * @return 分页结果，记录为脱敏后的 UserVO（不含密码字段）；
     *         没有匹配数据时 records 为空列表（不是 null），total 为 0；
     *         永不为 null
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
}
