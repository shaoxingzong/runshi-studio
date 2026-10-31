package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.user.UserAddRequest;
import com.bhu.runshistudioweb.model.dto.user.UserQueryRequest;
import com.bhu.runshistudioweb.model.dto.user.UserUpdateRequest;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.user.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.user.UserAddResultVO;
import com.bhu.runshistudioweb.model.vo.user.UserVO;

/**
 * 用户服务接口（C 端自助 + 管理端）
 *
 * @author shaoshing
 */
public interface UserService extends IService<SysUser> {

    // C 端：用户自己

    /**
     * 用户注册：校验 → IP 限流 → 查重 → BCrypt 加密 → 落库
     *
     * @param userAccount 账号，4~16 位且全局唯一；null 或空白抛 A0401
     * @param userName 真实姓名（展示用），trim 后不能为空且不超过 64 字
     * @param userPassword 明文密码，8~20 位，入库前用 BCrypt 加密
     * @param checkPassword 必须与 userPassword 完全一致，否则抛 A0401
     * @param remoteAddr / xRealIp / xForwardedFor IP 限流用的地址与请求头原始值，均可为 null
     * @return 新用户 ID（19 位 Long，接口层序列化为字符串）
     * @throws BusinessException 参数不合法 / 密码不一致 / 账号已存在（A0401）、触发限流（A0501）
     */
    long userRegister(String userAccount, String userName, String userPassword, String checkPassword,
                      String remoteAddr, String xRealIp, String xForwardedFor);

    /**
     * 用户登录：IP 限流 → 账号锁定检查 → 查库比对密码 → 下发 token
     *
     * 副作用：建立服务端会话（Redis）并写 token 到 Cookie；不幂等，每次成功都建新会话。
     *
     * @param userAccount 账号，为 null 抛 A0401
     * @param userPassword 明文密码，内部用 BCrypt 与库中哈希串比对
     * @param remoteAddr / xRealIp / xForwardedFor IP 限流用的地址与请求头原始值，均可为 null
     * @return 脱敏登录用户信息，含 token；token 仅此一次返回
     * @throws BusinessException 账号或密码错误（A0401）、账号被封禁（A0302）、触发限流或锁定（A0501）
     */
    LoginUserVO userLogin(String userAccount, String userPassword,
                          String remoteAddr, String xRealIp, String xForwardedFor);

    /**
     * 获取当前登录用户实体（含密码哈希串，仅供服务端内部判断）
     *
     * @return 当前登录用户，含密码哈希串；永不为 null
     * @throws BusinessException 未登录 / token 失效 / 用户已不存在或已被封禁（均 A0201）
     */
    SysUser getLoginUser();

    /**
     * 实体转「登录用户」VO（含 token 字段，不含密码）
     *
     * @param user 用户实体，允许为 null
     * @return 脱敏后的 VO；入参为 null 时返回 null，调用方必须判空
     */
    LoginUserVO getLoginUserVO(SysUser user);

    /**
     * 用户退出登录（只影响当前账号的当前 token）
     *
     * @return 恒为 true；走到返回值这一行说明注销动作已执行
     * @throws BusinessException 未登录时抛出（A0201）
     */
    boolean userLogout();

    // 管理端：仅供管理员调用

    /**
     * 管理员新增用户（可指定角色与状态）
     *
     * @param userAddRequest 新增请求，不能为 null；账号必填，密码为空则随机生成，角色不传按普通用户、状态不传按正常处理
     * @throws BusinessException 账号为空或已存在、角色非法（均 A0401）、入库失败（B0001）
     */
    UserAddResultVO addUser(UserAddRequest userAddRequest);

    /**
     * 管理员更新用户（昵称 / 角色 / 状态 / 密码，字段为 null 表示不修改）
     *
     * 副作用：封禁时踢掉目标账号全部会话；角色真的变为成员时发一条站内通知（幂等键去重）。
     *
     * @param userUpdateRequest 更新请求，不能为 null；id 必填；密码传值即重置（内部加密后覆盖）
     * @return true 表示更新成功；不代表数据真的变了，什么都没改时也是 true
     * @throws BusinessException 用户不存在（A0402）、角色非法 / 无成员档案 / 触发防锁死守卫（A0401）
     */
    boolean updateUser(UserUpdateRequest userUpdateRequest);

    /**
     * 管理员按 id 查询用户详情
     *
     * @param id 用户 ID
     * @return 脱敏后的用户信息，永不为 null
     * @throws BusinessException 用户不存在时抛出（A0402）
     */
    UserVO getUserById(long id);

    /**
     * 管理员逻辑删除用户（写 deleted_at，数据仍可追溯）
     *
     * 不幂等：对同一个 id 再删一次会因查不到而抛 A0402。
     *
     * @param id 用户 ID
     * @return true 表示删除成功
     * @throws BusinessException 试图删除自己（A0401）、用户不存在（A0402）
     */
    boolean deleteUser(long id);

    /**
     * 管理员分页查询用户列表
     *
     * @param userQueryRequest 查询条件，允许整体为 null（无条件查第一页）；字段为 null 表示该条件不参与筛选
     * @return 分页结果，记录为脱敏后的 UserVO；无数据时 records 为空列表（不是 null）
     */
    Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest);

    /**
     * 实体转「管理端」VO（含角色与状态，不含密码）
     *
     * @param user 用户实体，允许为 null
     * @return 脱敏后的管理端 VO；入参为 null 时返回 null，调用方必须判空
     */
    UserVO getUserVO(SysUser user);

    /**
     * 管理端：重置某个用户的 AI 提问配额
     *
     * 调用方必须已通过 {@code @SaCheckRole("admin")} 校验。
     *
     * @param id 目标用户 ID
     * @return 重置前的真实用量（含未回刷的增量），供管理端提示「已从 N 次清零」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 用户不存在时抛 A0402
     */
    int resetAiQueryCount(long id);
}
