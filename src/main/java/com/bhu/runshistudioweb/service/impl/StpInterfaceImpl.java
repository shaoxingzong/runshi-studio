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
 * Sa-Token 只在鉴权时回调本类，它不认识业务库。容器里唯一的 StpInterface Bean
 * 会被 starter 自动注入 SaManager，所以只加 @Service 就够了，不要自己 new 一个再 set。
 *
 * getRoleList 供 @SaCheckRole 使用，每个带该注解的请求都会查一次库；要加 Redis 缓存
 * 必须同时在「修改用户角色」处删 key，否则撤权后仍会继续拿着旧角色。
 * getPermissionList 供 @SaCheckPermission 使用，本项目未使用权限点。
 *
 * @author shaoshing
 */
@Service
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    /** 用户状态：0-正常（与 db/user.sql 中 user_status 的取值保持一致：0-正常，1-封禁） */
    private static final int USER_STATUS_NORMAL = 0;

    private final SysUserMapper sysUserMapper;

    /**
     * 查询权限点集合：本项目不使用细粒度权限点，恒返回空集合
     *
     * 鉴权粒度停在角色层，再拆一层就要多维护一张「角色 → 权限点」映射表，当前没有这种需求。
     * 空集合的语义是「本系统不使用权限点」，不影响 @SaCheckRole 的角色判定。
     *
     * @return 恒为空集合（不可变），与 loginId 无关
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /**
     * 查询账号拥有的角色，直接取 sys_user.user_role
     *
     * 不缓存：只有带 @SaCheckRole 的接口才触发，一旦缓存就必须回答「改角色时何时删缓存」，
     * 漏删的表现是「撤了管理员权限却一直是管理员」，比慢一点危险得多。
     * 封禁账号一律不授予角色，否则已签发的 token 在过期前仍然可用。
     *
     * 坑：角色名大小写敏感且无层级包含，库里存 admin 时 @SaCheckRole("Admin") 永远不通过；
     * 异常一律收敛成空集合，抛出去会让鉴权失败变成 500 而不是 403。
     *
     * @param loginId 登录账号 id，非数字（脏 token）按「无角色」处理
     * @return 角色集合；账号不存在、已逻辑删除、已封禁或角色为空时返回空集合
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // loginId 的具体类型取决于登录时的传参（本项目统一传 Long 型用户 id）。
        // 这里用 Convert 兼容 String 形式，避免有人写成 StpUtil.login("10001") 时直接抛类型转换异常
        Long userId = Convert.toLong(loginId);
        if (userId == null) {
            // 转不出数字说明这个 loginId 根本不是本系统签发的（token 被篡改、或换了账号体系）。
            // 按「无角色」处理而不是抛异常：鉴权失败应该是 403 语义，抛异常会变成 500
            return List.of();
        }

        // 查库拿角色：selectById 会被 MyBatis-Plus 自动追加 deleted_at = 0，
        // 因此已注销（逻辑删除）的账号查出来就是 null，不需要额外判断
        SysUser user = sysUserMapper.selectById(userId);
        // 三个条件分开写而不是合成一个：角色为空与账号不存在的原因完全不同，
        // 但对外都表现为「没有任何角色」，合并判断可以少一次短路比较
        if (user == null || user.getUserRole() == null || user.getUserRole().isBlank()) {
            return List.of();
        }

        // 封禁账号一律不授予角色：这样即使 token 尚未过期，@SaCheckRole 也会直接判定为无权限。
        // 先判 null 再比较：user_status 理论上可能为空（人工改库留下的脏数据），直接拆箱会 NPE
        if (user.getUserStatus() == null || user.getUserStatus() != USER_STATUS_NORMAL) {
            return List.of();
        }

        // 返回库里的原始角色值，Sa-Token 会用它与 @SaCheckRole 的入参做精确比较。
        // 用 List.of 而不是 Arrays.asList：单元素不可变集合，调用方改不动它
        return List.of(user.getUserRole());
    }
}
