package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.user.UserAddRequest;
import com.bhu.runshistudioweb.model.dto.user.UserQueryRequest;
import com.bhu.runshistudioweb.model.dto.user.UserUpdateRequest;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.UserRoleEnum;
import com.bhu.runshistudioweb.model.vo.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.UserVO;
import com.bhu.runshistudioweb.service.UserService;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 用户服务实现
 *
 * author: shaoshing
 *
 * <p>为什么继承 {@code ServiceImpl<SysUserMapper, SysUser>}：
 * MyBatis-Plus 的 ServiceImpl 已经把单表 CRUD（save / getById / count / updateById 等）
 * 用 Mapper 实现好了，这里只需要写「业务动作」，不用重复写 CRUD。
 * 注意第一个泛型必须是 Mapper 类型，写错会导致启动时找不到对应 Bean。
 *
 * <p>校验统一走 {@code ThrowUtils.throwIf}：条件成立即抛 {@code BusinessException}，
 * 由全局异常处理器转成标准响应，所以本类**不需要写 try-catch**，也不需要层层返回错误码。
 *
 * <p>注意：涉及多写的操作（注册）应保证原子性。当前注册只有一次 insert 是安全的；
 * 若以后注册还要写成员档案等第二张表，必须加 {@code @Transactional}，
 * 否则中途失败会留下「有账号无档案」的脏数据。
 */
@Service
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    /**
     * 管理员新增用户且未填写密码时使用的初始密码
     *
     * <p>这是「可用性 vs 安全」的折中：管理员批量建号时不可能逐个设密码。
     * 代价是初始密码可预测，因此管理员交付账号后应要求用户立即修改；
     * 若安全要求更高，可改成随机生成密码并只在创建响应里返回一次。
     */
    private static final String DEFAULT_INIT_PASSWORD = "Runshi@123";

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

    // ==================== C 端：用户自己 ====================

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 空值兜底：Controller 的 @Valid 已经拦了一道，这里再拦一次是因为
        // Service 也可能被其他入口（管理端、定时任务、测试）直接调用，不能只依赖 Web 层校验
        ThrowUtils.throwIf(StrUtil.isBlank(userAccount) || StrUtil.isBlank(userPassword),
                ErrorCode.PARAMS_ERROR, "用户账号或密码为空");
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
        // 昵称：DDL 中 user_name 是 NOT NULL 且**没有默认值**，不赋值会直接插入失败
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

        boolean saveResult = this.save(sysUser);
        // save 返回 false 说明插入没成功（如唯一索引冲突），必须显式判断，
        // 否则会把「没存进去」当成注册成功返回给前端
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "注册失败，数据库异常");

        // save 成功后 MyBatis-Plus 会把雪花 ID 回填到实体，这里直接取即可
        return sysUser.getId();
    }

    /**
     * 用户登录（接口契约见 UserService）
     *
     * <p>实现上的三个顺序，任何一条调换都会出问题：
     * <ol>
     *     <li>先按账号查实体——查不到也要走完"密码比对"这一步（这里用短路统一提示）；</li>
     *     <li>密码比对用 {@code PasswordUtils.matches}（BCrypt），不能用 equals 比哈希串；</li>
     *     <li>封禁校验放最后——此时已确认密码正确，攻击者无法通过报错差异探测账号状态。</li>
     * </ol>
     *
     * @param userAccount  用户账号
     * @param userPassword 明文密码
     * @return 脱敏用户信息（含 token）
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword) {
        ThrowUtils.throwIf(userAccount == null || userPassword == null, ErrorCode.PARAMS_ERROR, "用户账号或密码为空");

        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUserAccount, userAccount);
        SysUser user = this.getOne(queryWrapper);

        // 防账号枚举：账号不存在与密码错误使用**同一个提示文案**。
        // 若分开提示（"账号不存在"/"密码错误"），攻击者可用两种报错扫出一批有效账号，再集中爆破。
        boolean passwordMatched = user != null && PasswordUtils.matches(userPassword, user.getUserPassword());
        ThrowUtils.throwIf(!passwordMatched, ErrorCode.PARAMS_ERROR, "账号或密码错误");

        // 封禁校验放在密码校验之后：此时已确认密码正确，攻击者无法再通过报错差异探测账号状态
        ThrowUtils.throwIf(isBanned(user), ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");

        // 登录成功：Sa-Token 会把 loginId 写入会话（Redis，见 sa-token-redis-template），
        // 并按 yml 配置把 token 写进响应 Cookie
        StpUtil.login(user.getId());

        LoginUserVO loginUserVO = this.getLoginUserVO(user);
        // token 只在登录这一刻返回，前端需保存并在后续请求里带上（请求头 satoken: xxx）
        loginUserVO.setToken(StpUtil.getTokenValue());
        return loginUserVO;
    }

    /**
     * 取当前登录用户实体（接口契约见 UserService）
     *
     * <p>返回的是**实体**而不是 VO，仅用于服务端内部逻辑（鉴权、审计、守卫判断）。
     * 要返回给前端请走 {@link #getLoginUserVO(SysUser)} 转换为脱敏 VO。
     *
     * @return 当前登录用户实体，含密码哈希串，切勿直接序列化到响应里
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

        return user;
    }

    /**
     * 实体转「用户自己看」的 VO（含 token 字段，不含密码）
     *
     * <p>与 {@link #getUserVO(SysUser)} 的区别：这个是给用户自己看的（带角色、不带状态位），
     * 那个是给管理员看的（带角色与状态）。二者都不含密码字段。
     *
     * @param user 用户实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    @Override
    public LoginUserVO getLoginUserVO(SysUser user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        // 用属性名自动拷贝，避免手写一长串 setXxx（漏一个字段就是线上 bug）；
        // VO 中没有 userPassword 字段，因此密码哈希串**不会**被拷贝进去，这是脱敏的关键
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /**
     * 注销登录（接口契约见 UserService）
     *
     * <p>只清**当前账号当前端**的会话：Sa-Token 默认按 token 注销，
     * 同一账号在别的设备上登录的会话不受影响（如需全端下线要传 SaLogoutParameter）。
     *
     * @return true 表示注销成功
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
     * 管理员新增用户（接口契约见 UserService）
     *
     * <p>与自助注册（{@link #userRegister}）唯一的业务差别是**允许指定角色与状态**，
     * 其余规则（账号唯一、只存 BCrypt 哈希、昵称为空用账号兜底）完全共用同一套逻辑，
     * 因此新增账号的"最低权限"由 {@code UserRoleEnum.USER} 兜底，不会越权。
     *
     * @param userAddRequest 新增请求（账号必填；密码留空则使用默认初始密码）
     * @return 新用户 ID
     */
    @Override
    public long addUser(UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null || StrUtil.isBlank(userAddRequest.getUserAccount()),
                ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        String userAccount = userAddRequest.getUserAccount().trim();
        assertAccountNotExists(userAccount);

        // 角色：不传按最低权限（user）处理，传了就必须是枚举内的合法取值。
        // 这里绝不能用 @Pattern 把取值写死在注解上——枚举一改，注解就过期了
        UserRoleEnum role = resolveRole(userAddRequest.getUserRole(), UserRoleEnum.USER);

        SysUser sysUser = new SysUser();
        sysUser.setUserAccount(userAccount);
        // 密码为空时用默认初始密码；无论哪种情况都只存 BCrypt 哈希串
        sysUser.setUserPassword(PasswordUtils.encrypt(
                blankToDefault(userAddRequest.getUserPassword(), DEFAULT_INIT_PASSWORD)));
        // 昵称为空时用账号兜底（user_name 是 NOT NULL 且无默认值）
        sysUser.setUserName(blankToDefault(userAddRequest.getUserName(), userAccount));
        sysUser.setUserAvatar(userAddRequest.getUserAvatar());
        sysUser.setUserRole(role.getValue());
        // 状态不传按正常处理：显式赋值比依赖 DDL 默认值更直观，也避免以后 DDL 变更时行为漂移
        sysUser.setUserStatus(userAddRequest.getUserStatus() == null
                ? USER_STATUS_NORMAL : userAddRequest.getUserStatus());

        boolean saveResult = this.save(sysUser);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "新增用户失败，数据库异常");
        return sysUser.getId();
    }

    /**
     * 管理员更新用户（部分更新，接口契约见 UserService）
     *
     * <p><b>防锁死守卫</b>是本方法最容易被删掉的部分：管理员不能封禁自己、不能把自己降级。
     * 因为权限判定是实时查库的，一旦自己失去 admin 角色会立刻无法管理，
     * 且此时没有任何管理员能通过接口把你改回来，只能去数据库修数据。
     *
     * @param userUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     */
    @Override
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

        // 防锁死守卫：管理员不能把自己封禁、也不能把自己降级。
        // 权限判定每次都查库（见 StpInterfaceImpl），一旦自己失去 admin 角色会**立刻**无法管理，
        // 而且此时已经没有任何管理员能通过接口把你改回来，只能去数据库修数据
        if (Objects.equals(id, StpUtil.getLoginIdAsLong())) {
            ThrowUtils.throwIf(Integer.valueOf(USER_STATUS_BANNED).equals(userUpdateRequest.getUserStatus()),
                    ErrorCode.PARAMS_ERROR, "不能封禁当前登录的账号");
            ThrowUtils.throwIf(userRole != null && UserRoleEnum.ADMIN != UserRoleEnum.of(userRole),
                    ErrorCode.PARAMS_ERROR, "不能把当前登录的管理员降级");
        }

        SysUser update = new SysUser();
        update.setId(id);
        update.setUserName(userUpdateRequest.getUserName());
        update.setUserAvatar(userUpdateRequest.getUserAvatar());
        update.setUserRole(userRole);
        update.setUserStatus(userUpdateRequest.getUserStatus());
        // 密码：传了才重置，传 null 表示不动原密码
        if (StrUtil.isNotBlank(userUpdateRequest.getUserPassword())) {
            update.setUserPassword(PasswordUtils.encrypt(userUpdateRequest.getUserPassword()));
        }

        // updateById 默认跳过 null 字段，这正是「传 null 表示不修改」语义成立的基础
        return this.updateById(update);
    }

    /**
     * 管理员按 id 查用户详情（返回脱敏 VO，接口契约见 UserService）
     *
     * @param id 用户 ID
     * @return 用户信息；不存在时抛 40400，不返回 null
     */
    @Override
    public UserVO getUserById(long id) {
        SysUser user = this.getById(id);
        // 查不到要给出明确的 40400，而不是返回 null 让前端收到「成功但 data 为空」
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return this.getUserVO(user);
    }

    /**
     * 管理员删除用户（逻辑删除，接口契约见 UserService）
     *
     * <p>守卫：不能删除当前登录账号——删掉自己会立刻失去管理端权限，且无法自行恢复。
     *
     * @param id 用户 ID
     * @return true 表示删除成功
     */
    @Override
    public boolean deleteUser(long id) {
        // 防误删守卫：删掉自己会立刻失去管理端权限（权限判定实时查库），且无法自行恢复
        ThrowUtils.throwIf(id == StpUtil.getLoginIdAsLong(),
                ErrorCode.PARAMS_ERROR, "不能删除当前登录的账号");

        SysUser existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        // 逻辑删除：实际执行 UPDATE ... SET deleted_at = 毫秒时间戳，
        // 数据仍可追溯，且账号名会因为「唯一索引带 deleted_at」而被释放，可以重新注册
        return this.removeById(id);
    }

    /**
     * 管理端分页查询（接口契约见 UserService）
     *
     * <p>分页与排序参数在这里做兜底纠正：页码 < 1 视为 1、每页条数收敛到 50、
     * 排序字段走 {@code applySort} 的白名单（防 ORDER BY 注入）。
     * 刻意不做"参数非法就报错"，避免把"页码传错"变成 50000 系统错误。
     *
     * @param userQueryRequest 查询条件，允许为 null（无条件查第一页）
     * @return 分页结果，记录为管理端 VO
     */
    @Override
    public Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest) {
        UserQueryRequest query = userQueryRequest == null ? new UserQueryRequest() : userQueryRequest;

        // 分页参数兜底纠正（而不是抛异常）：页码传错没必要让整个查询失败，
        // 收敛到合理区间继续查更符合预期，也避免「参数错」和「查询无结果」混在一起难以排查
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时，前端一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

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
        applySort(wrapper, query.getSortField(), query.getSortOrder());

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
        // 靠限流 + 验证码（见 ratelimiter 包），而不是靠隐藏提示。
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号已存在");
    }

    /**
     * 解析角色：空值用兜底角色，非法值直接报错
     *
     * @param userRole    前端传入的角色，可为空
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

    /**
     * 应用排序规则（白名单映射）
     *
     * <p>关键点：前端只能传「字段名」，由这里映射成实体字段引用。
     * 如果把前端字符串直接拼进 SQL 的 ORDER BY，就是最典型的 SQL 注入入口，
     * 而且 ORDER BY 位置无法用占位符参数化，只能靠白名单。
     *
     * @param wrapper   查询包装类
     * @param sortField 排序字段名，可为空
     * @param sortOrder 排序方向 asc/desc，可为空
     */
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
     * @param value        原值，可为 null/空白
     * @param defaultValue 默认值
     * @return 原值非空白时返回原值，否则返回默认值
     */
    private String blankToDefault(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }
}
