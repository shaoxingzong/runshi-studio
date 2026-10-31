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
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.enums.UserRoleEnum;
import com.bhu.runshistudioweb.model.vo.user.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.user.UserAddResultVO;
import com.bhu.runshistudioweb.model.vo.user.UserVO;
import com.bhu.runshistudioweb.service.NotificationService;
import com.bhu.runshistudioweb.service.OperationLogService;
import com.bhu.runshistudioweb.service.UserService;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户服务实现：注册 / 登录 / 注销，管理端增删改查与分页
 *
 * {@code @Slf4j} 不能省：父类 MyBatis 的 log 不支持占位参数，Lombok 的静态日志器才能用 "{}" 写法。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    /** 管理员新增用户且未填写密码时，随机初始密码的长度 */
    private static final int INIT_PASSWORD_LENGTH = 10;

    /** 账号长度区间，必须与 UserRegisterRequest / UserAddRequest 上的 @Size 保持一致 */
    private static final int MIN_ACCOUNT_LENGTH = 4;
    private static final int MAX_ACCOUNT_LENGTH = 16;

    /** 密码长度区间，必须与各 DTO 上的 @Size 保持一致 */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 20;

    /** 姓名长度上限，必须与 UserRegisterRequest 的 @Size 及 DDL 的 user_name varchar(64) 一致 */
    private static final int MAX_USER_NAME_LENGTH = 64;

    /** 用户状态：0-正常，1-封禁（与 db/user.sql 中 user_status 的注释一致） */
    private static final int USER_STATUS_NORMAL = 0;
    private static final int USER_STATUS_BANNED = 1;

    /** 分页每页条数的默认值与上限（上限是用来防「一次拉全表」的） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 「账号不存在」时用来消耗一次 BCrypt 的假哈希 */
    private static final String DUMMY_PASSWORD_HASH = PasswordUtils.encrypt(UUID.randomUUID().toString());

    /** AI 提问配额计数 */
    private final AiQueryCountManager aiQueryCountManager;

    /** 客户端 IP 解析（与考勤模块共用同一个 Manager） */
    private final ClientIpManager clientIpManager;

    /** IP 级双窗口限流（登录 / 注册共用，与游客提问限流同一套实现） */
    private final IpRateLimitManager ipRateLimitManager;

    /** 登录 / 注册限流的阈值与总开关 */
    private final AuthRateLimitProperties authRateLimitProperties;

    /** 账号级登录失败锁定（与 IP 限流互补：IP 限流挡单 IP 高频，本类挡分布式慢速爆破） */
    private final AuthLockManager authLockManager;

    /**
     * 站内通知（成员身份授予时告知本人）
     *
     * 依赖方向单向（本类 → 通知服务），通知服务不反向依赖业务服务，否则循环依赖启动即失败。
     */
    private final NotificationService notificationService;

    /** 操作日志（新增 / 改角色 / 封禁 / 重置口令 / 删除 / 重置配额都要留痕） */
    private final OperationLogService operationLogService;

    /** 成员档案 Mapper：用于「授予成员角色前确认档案存在」 */
    private final StudioMemberMapper studioMemberMapper;

    // C 端：用户自己

    /** 用户自助注册：校验参数与账号唯一性后，创建一个「最低权限」的普通账号 */
    @Override
    public long userRegister(String userAccount, String userName, String userPassword, String checkPassword,
                             String remoteAddr, String xRealIp, String xForwardedFor) {
        ThrowUtils.throwIf(StrUtil.isBlank(userAccount) || StrUtil.isBlank(userPassword),
                ErrorCode.PARAMS_ERROR, "用户账号或密码为空");

        userAccount = normalizeAccount(userAccount);

        // 姓名是展示名，必须显式提供：用它兜底账号会让所有新用户都叫「chenyu」这类登录名
        String normalizedUserName = StrUtil.trim(userName);
        ThrowUtils.throwIf(StrUtil.isBlank(normalizedUserName),
                ErrorCode.PARAMS_ERROR, "姓名不能为空");
        ThrowUtils.throwIf(normalizedUserName.length() > MAX_USER_NAME_LENGTH,
                ErrorCode.PARAMS_ERROR, "姓名长度不能超过 " + MAX_USER_NAME_LENGTH + " 个字符");

        assertRegisterRateAllowed(remoteAddr, xRealIp, xForwardedFor);

        ThrowUtils.throwIf(!userPassword.equals(checkPassword), ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");

        ThrowUtils.throwIf(userAccount.length() < MIN_ACCOUNT_LENGTH || userAccount.length() > MAX_ACCOUNT_LENGTH,
                ErrorCode.PARAMS_ERROR,
                "用户账号长度必须在 " + MIN_ACCOUNT_LENGTH + " 到 " + MAX_ACCOUNT_LENGTH + " 个字符之间");
        ThrowUtils.throwIf(userPassword.length() < MIN_PASSWORD_LENGTH || userPassword.length() > MAX_PASSWORD_LENGTH,
                ErrorCode.PARAMS_ERROR,
                "用户密码长度必须在 " + MIN_PASSWORD_LENGTH + " 到 " + MAX_PASSWORD_LENGTH + " 个字符之间");

        assertAccountNotExists(userAccount);

        String encryptedPassword = PasswordUtils.encrypt(userPassword);

        SysUser sysUser = new SysUser();
        sysUser.setUserAccount(userAccount);
        sysUser.setUserPassword(encryptedPassword);
        sysUser.setUserName(normalizedUserName);
        sysUser.setUserRole(UserRoleEnum.USER.getValue());

        boolean saveResult;
        try {
            saveResult = this.save(sysUser);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "注册失败，数据库异常");

        // save 成功后 MyBatis-Plus 会把雪花 ID 回填到实体，这里直接取即可
        return sysUser.getId();
    }

    /** 用户登录：两道防爆破闸门 → 查库比对密码 → 下发 token */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword,
                                 String remoteAddr, String xRealIp, String xForwardedFor) {
        ThrowUtils.throwIf(userAccount == null || userPassword == null, ErrorCode.PARAMS_ERROR, "用户账号或密码为空");

        userAccount = normalizeAccount(userAccount);

        assertLoginRateAllowed(remoteAddr, xRealIp, xForwardedFor);

        authLockManager.assertNotLocked(userAccount);

        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        SysUser user = this.getOne(queryWrapper);

        boolean passwordMatched;
        if (user == null) {
            PasswordUtils.matches(userPassword, DUMMY_PASSWORD_HASH);
            passwordMatched = false;
        } else {
            passwordMatched = PasswordUtils.matches(userPassword, user.getUserPassword());
        }
        if (!passwordMatched && user != null) {
            authLockManager.recordFailure(userAccount);
        }
        ThrowUtils.throwIf(!passwordMatched, ErrorCode.PARAMS_ERROR, "账号或密码错误");

        // 封禁校验放在密码校验之后：此时已确认密码正确，攻击者无法再通过报错差异探测账号状态
        ThrowUtils.throwIf(isBanned(user), ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");

        authLockManager.clear(userAccount);

        StpUtil.login(user.getId());

        LoginUserVO loginUserVO = this.getLoginUserVO(user);
        // token 只在登录这一刻返回，前端需保存并在后续请求里带上（请求头 satoken: xxx）
        loginUserVO.setToken(StpUtil.getTokenValue());
        return loginUserVO;
    }

    /**
     * 取当前登录用户的实体（服务端内部用）
     *
     * 未登录时抛异常而不是返回 null：否则每个调用方都得判空，漏一处就是一次 NPE。
     */
    @Override
    public SysUser getLoginUser() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");

        long loginId = StpUtil.getLoginIdAsLong();
        SysUser user = this.getById(loginId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        ThrowUtils.throwIf(isBanned(user), ErrorCode.NOT_LOGIN_ERROR, "账号已被封禁");

        return user;
    }

    /** 把用户实体转成「用户自己看」的 VO（带 token 字段，不带密码） */
    @Override
    public LoginUserVO getLoginUserVO(SysUser user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        if (user.getId() != null) {
            loginUserVO.setAiQueryCount(
                    aiQueryCountManager.merge(user.getId(), user.getAiQueryCount()));
        }
        return loginUserVO;
    }

    /** 注销登录：清掉当前这一端的会话 */
    @Override
    public boolean userLogout() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        // 注销会同时失效服务端会话（Redis）与客户端 Cookie，两处都清才算真正退出
        StpUtil.logout();
        return true;
    }

    // 管理端：调用方必须已通过 @SaCheckRole("admin")

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAddResultVO addUser(UserAddRequest userAddRequest) {
        // 账号是后面每一步的前提，空值先挡掉
        ThrowUtils.throwIf(userAddRequest == null || StrUtil.isBlank(userAddRequest.getUserAccount()),
                ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        // 与注册同一套规范化：账号一律小写入库，避免后台新增出 "Alice"、前台注册出 "alice"
        String userAccount = normalizeAccount(userAddRequest.getUserAccount());
        // 提前查一次：唯一索引才是最终防线，但先查能给出明确的「已存在」提示
        assertAccountNotExists(userAccount);

        UserRoleEnum role = resolveRole(userAddRequest.getUserRole(), UserRoleEnum.USER);

        String initialPassword = StrUtil.isNotBlank(userAddRequest.getUserPassword())
                ? userAddRequest.getUserPassword()
                : PasswordUtils.generateRandomPassword(INIT_PASSWORD_LENGTH);

        // 组装实体：密码只以 BCrypt 哈希形式入库
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

        // 返回 ID 与明文口令：口令只出现这一次，不返回它管理员就永远拿不到
        UserAddResultVO result = new UserAddResultVO();
        result.setId(sysUser.getId());
        result.setInitialPassword(initialPassword);
        log.warn("管理员新增用户，已生成随机初始密码 | userId={} | 待管理员通过安全渠道转交本人并督促改密",
                sysUser.getId());

        operationLogService.record(OperationTypeEnum.USER_ADD, sysUser.getId(), userAccount,
                "角色：" + role.getValue()
                        + "；状态：" + statusText(sysUser.getUserStatus())
                        + "；初始口令：" + (StrUtil.isNotBlank(userAddRequest.getUserPassword())
                        ? "管理员指定" : "系统随机生成（仅响应返回一次）"));
        return result;
    }

    /** 管理员更新用户（部分更新）：改资料、改角色、改状态、重置密码，并按副作用联动 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUser(UserUpdateRequest userUpdateRequest) {
        ThrowUtils.throwIf(userUpdateRequest == null || userUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        Long id = userUpdateRequest.getId();

        // 先确认目标存在：否则 updateById 只返回 false，前端只看到「更新失败」而不知道原因
        SysUser existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        String userRole = userUpdateRequest.getUserRole();
        // 角色合法性校验：非法值一旦入库，权限判定会静默失效（拿不到任何角色却也不报错）
        ThrowUtils.throwIf(userRole != null && UserRoleEnum.of(userRole) == null,
                ErrorCode.PARAMS_ERROR, "用户角色不合法，仅支持 " + UserRoleEnum.valuesText());

        if (UserRoleEnum.MEMBER.getValue().equals(userRole)
                || UserRoleEnum.ADMIN.getValue().equals(userRole)) {
            boolean hasProfile = studioMemberMapper.exists(
                    new LambdaQueryWrapper<StudioMember>()
                            .eq(StudioMember::getUserId, id));
            ThrowUtils.throwIf(!hasProfile, ErrorCode.PARAMS_ERROR,
                    "该账号还没有成员档案，授予成员/管理员身份前请先到「成员管理」为他建档并绑定此账号");
        }

        if (Objects.equals(id, StpUtil.getLoginIdAsLong())) {
            ThrowUtils.throwIf(Integer.valueOf(USER_STATUS_BANNED).equals(userUpdateRequest.getUserStatus()),
                    ErrorCode.PARAMS_ERROR, "不能封禁当前登录的账号");
            ThrowUtils.throwIf(userRole != null && UserRoleEnum.ADMIN != UserRoleEnum.of(userRole),
                    ErrorCode.PARAMS_ERROR, "不能把当前登录的管理员降级");
        }

        SysUser update = new SysUser();
        update.setId(id);
        // 只 set 白名单字段：DTO 里的值原样搬过来，null 交给 MyBatis-Plus 跳过
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

        if (updated && Integer.valueOf(USER_STATUS_BANNED).equals(userUpdateRequest.getUserStatus())) {
            StpUtil.logout(id);
        }

        if (updated && userRole != null
                && UserRoleEnum.MEMBER.getValue().equals(userRole)
                && !UserRoleEnum.MEMBER.getValue().equals(existing.getUserRole())) {
            notificationService.notify(id, StpUtil.getLoginIdAsLong(),
                    NotificationTypeEnum.MEMBER_GRANTED.getValue(), "USER", id,
                    "你已获得工作室成员身份",
                    "管理员已授予你工作室成员身份：工作台（考勤）与刷题功能已为你开放");
        }

        if (updated) {
            operationLogService.record(OperationTypeEnum.USER_UPDATE, id,
                    StrUtil.blankToDefault(existing.getUserName(), existing.getUserAccount()),
                    describeUserChanges(existing, userUpdateRequest));
        }
        return updated;
    }

    /** 管理员按 id 查用户详情（返回脱敏 VO，接口契约见 UserService） */
    @Override
    public UserVO getUserById(long id) {
        SysUser user = this.getById(id);
        // 查不到要给出明确的 A0402，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return this.getUserVO(user);
    }

    /** 管理员删除用户（逻辑删除）：让账号消失，并立刻让它的登录态失效 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteUser(long id) {
        // 防误删守卫：删掉自己会立刻失去管理端权限（权限判定实时查库），且无法自行恢复
        ThrowUtils.throwIf(id == StpUtil.getLoginIdAsLong(),
                ErrorCode.PARAMS_ERROR, "不能删除当前登录的账号");

        // 先确认存在：removeById 对不存在的行只返回 false，前端会分不清原因
        SysUser existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        boolean removed = this.removeById(id);

        if (removed) {
            StpUtil.logout(id);
            // 留痕：删号是最不可逆的管理动作，账号名要一起记进快照（行本身已被逻辑删除）
            operationLogService.record(OperationTypeEnum.USER_DELETE, id,
                    StrUtil.blankToDefault(existing.getUserName(), existing.getUserAccount()),
                    "逻辑删除账号「" + existing.getUserAccount() + "」，并踢掉该账号全部会话");
        }
        return removed;
    }

    /** 管理端分页查询用户：按条件筛选、排序并返回脱敏后的分页结果 */
    @Override
    public Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest) {
        // 不传条件等于无条件查第一页，兜一个空对象而不是报错
        UserQueryRequest query = userQueryRequest == null ? new UserQueryRequest() : userQueryRequest;

        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时，前端一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        // 拼筛选条件：每个条件都带「是否生效」的布尔参数，为 false 时不拼进 SQL
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        // 条件重载的第一个参数为 false 时该条件不会拼进 SQL，省去了手写 if 判断
        wrapper.eq(query.getId() != null, SysUser::getId, query.getId());
        wrapper.like(StrUtil.isNotBlank(query.getUserAccount()), SysUser::getUserAccount, query.getUserAccount());
        wrapper.like(StrUtil.isNotBlank(query.getUserName()), SysUser::getUserName, query.getUserName());
        // 精确匹配：角色/状态是枚举值域，精确匹配才能命中索引、结果也才可预期
        wrapper.eq(StrUtil.isNotBlank(query.getUserRole()), SysUser::getUserRole, query.getUserRole());
        wrapper.eq(query.getUserStatus() != null, SysUser::getUserStatus, query.getUserStatus());
        // 排序走白名单：ORDER BY 无法用占位符参数化，前端字符串绝不能直接拼进 SQL
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // 分页在数据库侧完成，转换逐条走 getUserVO（实体带着密码哈希串，不能直接序列化出去）
        Page<SysUser> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toUserVOPage(entityPage);
    }

    /** 实体转管理端 VO（含角色与状态，不含密码） */
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

    // 私有工具方法

    /** 校验账号未被占用 */
    private void assertAccountNotExists(String userAccount) {
        // count 而不是 selectOne：只需要判断存在性，避免把整行数据查回内存
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        long count = this.count(queryWrapper);
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号已存在");
    }

    /** 注册限流：按 IP 双窗口计数，超限抛 A0501 */
    private void assertRegisterRateAllowed(String remoteAddr, String xRealIp, String xForwardedFor) {
        if (Boolean.FALSE.equals(authRateLimitProperties.getEnabled())) {
            return;
        }
        String ip = clientIpManager.resolveClientIp(remoteAddr, xRealIp, xForwardedFor);
        ipRateLimitManager.assertAllowed(ip, AuthRateLimitConstant.REGISTER_KEY_PREFIX,
                authRateLimitProperties.getRegisterMinuteLimit(),
                authRateLimitProperties.getRegisterDailyLimit(), "注册");
    }

    /** 登录限流：按 IP 双窗口计数，超限抛 A0501 */
    private void assertLoginRateAllowed(String remoteAddr, String xRealIp, String xForwardedFor) {
        if (Boolean.FALSE.equals(authRateLimitProperties.getEnabled())) {
            return;
        }
        String ip = clientIpManager.resolveClientIp(remoteAddr, xRealIp, xForwardedFor);
        ipRateLimitManager.assertAllowed(ip, AuthRateLimitConstant.LOGIN_KEY_PREFIX,
                authRateLimitProperties.getLoginMinuteLimit(),
                authRateLimitProperties.getLoginDailyLimit(), "登录");
    }

    /** 解析角色：空值用兜底角色，非法值直接报错 */
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
            default -> wrapper.orderByDesc(SysUser::getId);
        }
    }

    /** 实体分页结果转 VO 分页结果 */
    private Page<UserVO> toUserVOPage(Page<SysUser> entityPage) {
        Page<UserVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::getUserVO).toList());
        return voPage;
    }

    /** 判断用户是否已被封禁 */
    private boolean isBanned(SysUser user) {
        return user.getUserStatus() != null && user.getUserStatus() == USER_STATUS_BANNED;
    }

    private String blankToDefault(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    /** 账号规范化（注册 / 管理员新增 / 登录三处共用唯一实现） */
    private String normalizeAccount(String account) {
        return account == null ? null : account.trim().toLowerCase(Locale.ROOT);
    }

    /** 把一次用户更新翻译成可读的变更说明（写进操作日志的 detail） */
    private String describeUserChanges(SysUser existing, UserUpdateRequest request) {
        StringBuilder detail = new StringBuilder();

        if (request.getUserName() != null && !Objects.equals(request.getUserName(), existing.getUserName())) {
            appendChange(detail, "昵称「" + existing.getUserName() + "」→「" + request.getUserName() + "」");
        }
        if (request.getUserAvatar() != null) {
            appendChange(detail, "更新头像");
        }
        if (request.getUserRole() != null && !Objects.equals(request.getUserRole(), existing.getUserRole())) {
            appendChange(detail, "角色「" + existing.getUserRole() + "」→「" + request.getUserRole() + "」");
        }
        if (request.getUserStatus() != null && !Objects.equals(request.getUserStatus(), existing.getUserStatus())) {
            appendChange(detail, "状态 " + statusText(existing.getUserStatus())
                    + " → " + statusText(request.getUserStatus()));
        }
        if (StrUtil.isNotBlank(request.getUserPassword())) {
            appendChange(detail, "重置口令");
        }
        return detail.length() == 0 ? "提交了更新请求，字段无变化" : detail.toString();
    }

    /** 追加一条变更说明（分号分隔） */
    private void appendChange(StringBuilder detail, String change) {
        if (detail.length() > 0) {
            detail.append("；");
        }
        detail.append(change);
    }

    /** 用户状态的中文文案（日志与提示共用） */
    private String statusText(Integer status) {
        return status != null && status == USER_STATUS_BANNED ? "封禁" : "正常";
    }

    /** 管理端：重置某个用户的 AI 提问配额 */
    @Override
    public int resetAiQueryCount(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不合法");
        SysUser user = this.getById(id);
        // 查不到按 A0402 而不是静默成功：管理端需要知道「这个人已经被删了」
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        int before = aiQueryCountManager.reset(id);

        // 留痕：配额是管理手段，谁给谁开了口子必须可查（detail 记原始用量，便于解释数字变化）
        operationLogService.record(OperationTypeEnum.USER_AI_QUOTA_RESET, id,
                blankToDefault(user.getUserName(), user.getUserAccount()),
                "AI 提问次数已从 " + before + " 次清零");
        return before;
    }
}
