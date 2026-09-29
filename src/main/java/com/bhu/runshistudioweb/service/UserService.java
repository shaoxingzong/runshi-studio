package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.LoginUserVO;

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
 * <p>约定：本接口所有方法在「参数不合法 / 业务不允许」时**抛 BusinessException**，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应
 * （例外：{@link #getLoginUserVO(SysUser)} 是纯转换方法，入参为 null 时返回 null）。
 */
public interface UserService extends IService<SysUser> {

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
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、或 token 有效但用户已不存在时抛出（40100）
     */
    SysUser getLoginUser();

    /**
     * 获取脱敏后的用户信息 VO
     *
     * @param user 用户实体，允许为 null
     * @return VO；入参为 null 时返回 null
     */
    LoginUserVO getLoginUserVO(SysUser user);

    /**
     * 用户退出登录
     *
     * @return true 表示注销成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（40100）
     */
    boolean userLogout();
}
