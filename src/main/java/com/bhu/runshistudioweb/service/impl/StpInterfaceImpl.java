package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.convert.Convert;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sa-Token 的角色 / 权限数据源
 *
 * author: shaoshing
 *
 * <p>作用：告诉 Sa-Token「这个 loginId 拥有哪些角色」，它是 {@code @SaCheckRole("admin")} 的判定依据。
 * Sa-Token 自己不认识业务库，只会在需要鉴权时回调本类的方法。
 *
 * <p><b>怎么被装配进去的</b>：starter 里的 SaBeanInject 会自动把容器中唯一的
 * {@link StpInterface} Bean 注入 SaManager，所以本类**只加 @Service 就够了**，
 * 不需要任何手写注册代码（也不要自己 new 一个再 set，那样容器里的依赖注入会失效）。
 *
 * <p><b>两个方法什么时候被调用</b>：
 * <ul>
 *     <li>{@link #getRoleList}：执行 {@code @SaCheckRole} 注解、或调用 {@code StpUtil.getRoleList()} 时；</li>
 *     <li>{@link #getPermissionList}：执行 {@code @SaCheckPermission} 注解时（本项目暂未使用）。</li>
 * </ul>
 *
 * <p><b>性能提醒</b>：这两个方法在**每个被鉴权的请求**上都会被调用，目前是直接查库。
 * 用户量小的时候完全够用；若某个接口 QPS 很高，可以在这里加 Redis 缓存
 * （key 形如 {@code satoken:role:{userId}}，TTL 取几十秒到几分钟，
 * 并在「修改用户角色」的地方主动删除该 key，否则角色变更后会继续拿着旧角色）。
 */
@Service
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    /** 用户状态：0-正常（与 db/user.sql 中 user_status 的取值保持一致：0-正常，1-封禁） */
    private static final int USER_STATUS_NORMAL = 0;

    private final SysUserMapper sysUserMapper;

    /**
     * 查询该账号拥有的权限点集合
     *
     * <p>本项目采用「角色」粒度鉴权（{@code @SaCheckRole}），没有引入细粒度的权限点表，
     * 因此固定返回空集合。将来若要做 {@code @SaCheckPermission("user:update")}，
     * 在这里把「角色 → 权限点」的映射结果查出来返回即可。
     *
     * @param loginId   登录账号 id（即 StpUtil.login() 时传入的那个值）
     * @param loginType 账号体系标识，本项目只有一套账号体系，固定为 "login"
     * @return 权限点集合；空集合表示没有任何权限
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /**
     * 查询该账号拥有的角色集合，角色值直接取自 sys_user.user_role
     *
     * <p>注意：Sa-Token 对角色名是**大小写敏感的精确字符串比较**，
     * 所以 {@code @SaCheckRole("admin")} 必须与库里存的值完全一致
     * （本项目的取值为 user / member / admin，定义见
     * {@link com.bhu.runshistudioweb.model.enums.UserRoleEnum}）。
     *
     * @param loginId   登录账号 id
     * @param loginType 账号体系标识，固定为 "login"
     * @return 角色集合；账号不存在、已逻辑删除、已封禁或角色为空时返回空集合（等于无任何角色）
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // loginId 的具体类型取决于登录时的传参（本项目统一传 Long 型用户 id）。
        // 这里用 Convert 兼容 String 形式，避免有人写成 StpUtil.login("10001") 时直接抛类型转换异常
        Long userId = Convert.toLong(loginId);
        if (userId == null) {
            return List.of();
        }

        // 查库拿角色：selectById 会被 MyBatis-Plus 自动追加 deleted_at = 0，
        // 因此已注销（逻辑删除）的账号查出来就是 null，不需要额外判断
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getUserRole() == null || user.getUserRole().isBlank()) {
            return List.of();
        }

        // 封禁账号一律不授予角色：这样即使 token 尚未过期，@SaCheckRole 也会直接判定为无权限
        if (user.getUserStatus() == null || user.getUserStatus() != USER_STATUS_NORMAL) {
            return List.of();
        }

        // 返回库里的原始角色值，Sa-Token 会用它与 @SaCheckRole 的入参做精确比较
        return List.of(user.getUserRole());
    }
}
