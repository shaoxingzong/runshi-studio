package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.user.UserAddRequest;
import com.bhu.runshistudioweb.model.dto.user.UserQueryRequest;
import com.bhu.runshistudioweb.model.dto.user.UserUpdateRequest;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.UserVO;

/**
 * 用户服务接口
 *
 * author: shaoshing
 *
 * <p>为什么先定义接口再写实现：Controller 只依赖这个接口，实现改动（换实现类、加缓存、
 * 加事务）不影响调用方；也是依赖倒置的体现——上层依赖抽象，不依赖具体实现。
 *
 * <p>继承 {@code IService<SysUser>} 后，单表 CRUD（save / getById / count / updateById …）
 * 直接可用，不用在接口里再声明一遍。
 *
 * <p>方法按使用者分成两组，不要混在一起看：
 * <ul>
 *     <li><b>C 端（用户自己）</b>：{@link #userRegister}、{@link #userLogin}、
 *     {@link #getLoginUser}、{@link #userLogout}——入参来自表单，权限是「本人」；</li>
 *     <li><b>管理端（管理员）</b>：{@link #addUser}、{@link #updateUser}、
 *     {@link #deleteUser}、{@link #listUserByPage}——入参来自后台，调用方必须已通过
 *     {@code @SaCheckRole("admin")} 校验，因此这两组方法**绝不能互相复用**：
 *     自助注册永远不允许指定角色，而管理端新增可以。</li>
 * </ul>
 *
 * <p>约定：本接口所有方法在「参数不合法 / 业务不允许」时**抛 BusinessException**，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应
 * （例外：{@link #getLoginUserVO(SysUser)}、{@link #getUserVO(SysUser)} 是纯转换方法，
 * 入参为 null 时返回 null）。
 */
public interface UserService extends IService<SysUser> {

    // ==================== C 端：用户自己 ====================

    /**
     * 用户注册
     *
     * @param userAccount   用户账号（唯一，长度与字符集规则见 UserRegisterRequest）
     * @param userPassword  用户密码（明文，方法内部会用 BCrypt 加密后再入库）
     * @param checkPassword 校验密码，必须与 userPassword 完全一致
     * @return 新注册用户 ID（雪花算法生成）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法、两次密码不一致、账号已存在或入库失败时抛出
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     *
     * <p>成功后由 Sa-Token 建立会话（存放于 Redis），并返回带 token 的用户信息。
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码（明文，内部用 BCrypt 与库中哈希串比对）
     * @return 脱敏后的登录用户信息（含 Token）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 账号或密码错误、账号被封禁时抛出
     */
    LoginUserVO userLogin(String userAccount, String userPassword);

    /**
     * 获取当前登录用户
     *
     * <p>注意：未登录时**抛异常**而不是返回 null，避免每个调用方都写判空。
     *
     * @return 当前登录用户实体（含密码哈希串，仅供服务端内部使用，切勿直接返回给前端）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、或 token 有效但用户已不存在时抛出（A0201）
     */
    SysUser getLoginUser();

    /**
     * 实体转「登录用户」VO（含 token 字段，但不含密码）
     *
     * @param user 用户实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    LoginUserVO getLoginUserVO(SysUser user);

    /**
     * 用户退出登录
     *
     * @return true 表示注销成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
     */
    boolean userLogout();

    // ==================== 管理端：仅供管理员调用 ====================

    /**
     * 管理员新增用户
     *
     * <p>与自助注册的差别：这里**允许指定角色与状态**（这正是管理动作的一部分），
     * 但角色值必须落在枚举范围内，不允许写进任意字符串。
     *
     * @param userAddRequest 新增请求（账号必填；密码为空时使用默认初始密码）
     * @return 新用户 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 账号已存在、角色非法时抛出
     */
    long addUser(UserAddRequest userAddRequest);

    /**
     * 管理员更新用户（部分更新：字段为 null 表示不修改）
     *
     * <p>内置两个防锁死守卫：不允许管理员封禁自己、也不允许把自己的管理员角色降级。
     * 因为权限判定是实时查库的（见 {@code StpInterfaceImpl}），一旦自己失去 admin 角色会立刻无法管理，
     * 且此时没有任何管理员能通过接口改回来，只能去数据库修数据。
     *
     * @param userUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 用户不存在、角色非法、触发防锁死守卫时抛出
     */
    boolean updateUser(UserUpdateRequest userUpdateRequest);

    /**
     * 管理员按 id 查询用户详情
     *
     * <p>为什么不让 Controller 直接调 {@code IService.getById()}：那样 Controller 就认识了实体，
     * 很容易顺手把含密码哈希串的实体返回出去。统一由 Service 返回脱敏后的 VO，出口只有一处。
     *
     * @param id 用户 ID
     * @return 脱敏后的用户信息
     * @throws com.bhu.runshistudioweb.exception.BusinessException 用户不存在时抛出（A0402）
     */
    UserVO getUserById(long id);

    /**
     * 管理员逻辑删除用户
     *
     * <p>删除是逻辑删除（写 deleted_at 毫秒时间戳），数据仍可追溯；
     * 且因为唯一索引带着 deleted_at，被删除的账号所占用过的账号名可以被重新注册。
     *
     * @param id 用户 ID
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 用户不存在、或试图删除自己时抛出
     */
    boolean deleteUser(long id);

    /**
     * 管理员分页查询用户列表
     *
     * <p>分页与排序参数的兜底纠正（页码、每页条数、排序字段白名单）都在实现里完成，
     * 调用方不需要自己校验。
     *
     * @param userQueryRequest 查询条件（允许整体为 null，表示无条件查第一页）
     * @return 分页结果，记录为脱敏后的 {@link UserVO}
     */
    Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest);

    /**
     * 实体转「管理端」VO（含角色与状态，不含密码）
     *
     * @param user 用户实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    UserVO getUserVO(SysUser user);
}
