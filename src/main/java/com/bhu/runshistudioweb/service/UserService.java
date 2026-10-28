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
 * 两组方法不能互相复用：自助注册永远不允许指定角色，管理端新增可以。
 * 一旦让注册走管理端那条路径，任何人都能注册出一个管理员。
 *
 * 继承 {@code IService<SysUser>} 的 CRUD 方法绕过本接口的业务校验
 * （账号唯一、密码加密、防锁死守卫），只供服务端内部使用，不能直接暴露给 Controller。
 *
 * 约定：参数不合法 / 业务不允许时抛 BusinessException，不返回错误码、不返回 null
 * （例外：两个 VO 转换方法是纯转换，入参为 null 时返回 null）。
 *
 * @author shaoshing
 */
public interface UserService extends IService<SysUser> {

    // ==================== C 端：用户自己 ====================

    /**
     * 用户注册：校验 → IP 限流 → 查重 → BCrypt 加密 → 落库
     *
     * 限流挡在查重之前：本接口的「账号已存在」提示是功能需要，天然是存在性探测器，
     * 防批量扫描只能靠 IP 限流，被限流的请求不该产生后续开销。
     *
     * 坑：不能靠 insert 的返回值判断重复——撞唯一索引抛的是 DuplicateKeyException，
     * 并发下两个请求都能通过查重，必须 catch 后转成 A0401。新用户角色写死为 user，
     * 成员身份由管理员建档决定。本方法不幂等，同参数第二次调用会因账号已存在而失败。
     *
     * @param userAccount 账号，4~16 位且全局唯一；null 或空白抛 A0401
     * @param userPassword 明文密码，8~20 位，入库前用 BCrypt 加密
     * @param checkPassword 必须与 userPassword 完全一致，否则抛 A0401
     * @param remoteAddr / xRealIp / xForwardedFor IP 限流用的地址与请求头原始值，均可为 null
     * @return 新用户 ID（19 位 Long，接口层序列化为字符串）
     * @throws BusinessException 参数不合法 / 密码不一致 / 账号已存在（A0401）、触发限流（A0501）
     */
    long userRegister(String userAccount, String userPassword, String checkPassword,
                      String remoteAddr, String xRealIp, String xForwardedFor);

    /**
     * 用户登录：IP 限流 → 账号锁定检查 → 查库比对密码 → 下发 token
     *
     * 两道闸互补：IP 限流挡「一个 IP 试多个账号」，账号锁定挡「海量 IP 各试几次」的慢速爆破。
     * 顺序按成本从低到高——BCrypt 故意慢，放在最前面，防护本身就成压垮 CPU 的那只手。
     *
     * 坑：不能对「账号不存在」累计失败次数，「已锁定」会变成账号存在性探测器；
     * 密码错误必须在封禁校验之前抛出，否则可用「已封禁 / 密码错误」的差异反推密码。
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
     * 返回实体而非 VO，绝不能直接序列化进响应体，对外必须走 {@link #getLoginUserVO(SysUser)}。
     * 未登录、token 失效、用户已不存在或已被封禁统一抛 A0201：前端据此跳登录页，
     * 被封禁的账号会在登录时看到真实原因。
     *
     * @return 当前登录用户，含密码哈希串；永不为 null
     * @throws BusinessException 未登录 / token 失效 / 用户已不存在或已被封禁（均 A0201）
     */
    SysUser getLoginUser();

    /**
     * 实体转「登录用户」VO（含 token 字段，不含密码）
     *
     * 脱敏靠 LoginUserVO 结构上没有 userPassword 字段，而不是拷完再置空。
     * 配额字段覆盖为「库值 + Redis 增量」的合并值，否则用户刚问过几次却看不到数字变化。
     *
     * 坑：自动拷贝按属性名与类型匹配，类型不一致会静默跳过、不报错。
     * token 不由本方法填，其它场景下为 null 属正常。
     *
     * @param user 用户实体，允许为 null
     * @return 脱敏后的 VO；入参为 null 时返回 null，调用方必须判空
     */
    LoginUserVO getLoginUserVO(SysUser user);

    /**
     * 用户退出登录（只影响当前账号的当前 token）
     *
     * 未登录直接抛 A0201 而不是静默成功：含糊的成功会掩盖「token 早就过期」这类真实登录态问题。
     *
     * @return 恒为 true；走到返回值这一行说明注销动作已执行
     * @throws BusinessException 未登录时抛出（A0201）
     */
    boolean userLogout();

    // ==================== 管理端：仅供管理员调用 ====================

    /**
     * 管理员新增用户（可指定角色与状态）
     *
     * 与自助注册的唯一差别是允许指定角色，因此两者不能合并——一旦合并，
     * 注册接口就能被传一个角色参数，任何人都能注册出管理员。
     *
     * 密码留空时生成随机初始密码而非固定默认值：固定口令意味着知道本系统的人
     * 能登录所有未改密的账号。初始密码明文只随本次返回值出现一次，后端不存明文。
     * 不幂等：同一账号重复调用会因账号已存在而失败。
     *
     * @param userAddRequest 新增请求，不能为 null；账号必填，密码为空则随机生成，
     *                       角色不传按普通用户、状态不传按正常处理
     * @return 新用户 ID 与本次生效的初始密码（明文，仅此一次）
     * @throws BusinessException 账号为空或已存在、角色非法（均 A0401）、入库失败（B0001）
     */
    UserAddResultVO addUser(UserAddRequest userAddRequest);

    /**
     * 管理员更新用户（昵称 / 角色 / 状态 / 密码，字段为 null 表示不修改）
     *
     * 角色改动会让该用户已签发的会话失效，需要重新登录。
     * 不允许把当前登录的管理员自己降级或封禁，否则会把自己锁在系统外。
     * 授予成员 / 管理员角色前要求该账号已有成员档案，否则界面入口不出现、后端还报 A0301。
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
     * 统一由 Service 返回脱敏 VO，而不是让 Controller 直接查实体——
     * 出口只有一处，才不会顺手把含密码哈希串的实体返回出去。
     *
     * @param id 用户 ID
     * @return 脱敏后的用户信息，永不为 null
     * @throws BusinessException 用户不存在时抛出（A0402）
     */
    UserVO getUserById(long id);

    /**
     * 管理员逻辑删除用户（写 deleted_at，数据仍可追溯）
     *
     * 不能删除当前登录账号：权限判定实时查库，删掉自己会立刻失去管理端权限且无法自行恢复。
     * 删除后踢掉该账号全部会话，不让已下发的 token 继续有效。
     * 不清理该账号的成员档案与业务数据——那是独立实体，级联删除属于另一个决策。
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
     * 分页与排序参数在实现里兜底纠正而非报错：这些参数走 GET，校验失败抛的异常会落到
     * 全局兜底变成 B0001，把「页码错了」显示成「服务器故障」。
     *
     * 坑：排序字段必须走白名单。ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进 SQL 就是最典型的注入入口。
     *
     * @param userQueryRequest 查询条件，允许整体为 null（无条件查第一页）；
     *                         字段为 null 表示该条件不参与筛选
     * @return 分页结果，记录为脱敏后的 UserVO；无数据时 records 为空列表（不是 null）
     */
    Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest);

    /**
     * 实体转「管理端」VO（含角色与状态，不含密码）
     *
     * 与登录 VO 同一套脱敏思路：UserVO 里没有密码字段，自动拷贝不可能把哈希串带出去。
     * 若哪天给 UserVO 补上 userPassword，脱敏会在零代码改动的情况下失效且不报错。
     *
     * @param user 用户实体，允许为 null
     * @return 脱敏后的管理端 VO；入参为 null 时返回 null，调用方必须判空
     */
    UserVO getUserVO(SysUser user);

    /**
     * 管理端：重置某个用户的 AI 提问配额
     *
     * 场景是「用户次数用完后来找管理员」，因此不校验目标用户是否已用完——
     * 在没用完的账号上点重置也只是归零，无副作用。也不区分角色：
     * 给成员重置虽然没意义（他们本就不限额），但报错只会让管理员困惑。
     *
     * 调用方必须已通过 {@code @SaCheckRole("admin")} 校验。
     *
     * @param id 目标用户 ID
     * @return 重置前的真实用量（含未回刷的增量），供管理端提示「已从 N 次清零」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 用户不存在时抛 A0402
     */
    int resetAiQueryCount(long id);
}
