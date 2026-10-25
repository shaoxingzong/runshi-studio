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
 * 作用：告诉 Sa-Token「这个 loginId 拥有哪些角色」，它是 @SaCheckRole("admin") 的判定依据。
 * Sa-Token 自己不认识业务库，只会在需要鉴权时回调本类的方法。
 *
 * 怎么被装配进去的：starter 里的 SaBeanInject 会自动把容器中唯一的
 * StpInterface Bean 注入 SaManager，所以本类只加 @Service 就够了，
 * 不需要任何手写注册代码（也不要自己 new 一个再 set，那样容器里的依赖注入会失效）。
 *
 * 两个方法什么时候被调用：
 * - getRoleList：执行 @SaCheckRole 注解、或调用 StpUtil.getRoleList() 时；
 * - getPermissionList：执行 @SaCheckPermission 注解时（本项目暂未使用）。
 *
 * 性能提醒：这两个方法在每个被鉴权的请求上都会被调用，目前是直接查库。
 * 用户量小的时候完全够用；若某个接口 QPS 很高，可以在这里加 Redis 缓存
 * （key 形如 satoken:role:{userId}，TTL 取几十秒到几分钟，
 * 并在「修改用户角色」的地方主动删除该 key，否则角色变更后会继续拿着旧角色）。
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
     * 查询该账号拥有的权限点集合
     *
     * <p>为什么这样设计：本项目的鉴权粒度停在「角色」这一层，没有引入权限点表。
     * 角色已经能表达管理员 / 成员 / 普通用户三档能力差异，再拆一层权限点，
     * 就要多维护一张「角色 → 权限点」的映射表，而当前没有任何接口需要
     * 「同一角色里有人能改、有人只能看」这种细分。所以这里固定返回空集合，
     * 它的语义是「本系统不使用权限点」，而不是「这个账号没有权限」。
     * 这一点很关键：Sa-Token 只在你真的写了 @SaCheckPermission 时才会调用本方法，
     * 返回空集合不会影响 @SaCheckRole 的角色鉴权。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>直接返回 List.of()。没有查询、没有判空：空集合是不可变常量，
     *     每次调用都不产生新的对象分配；</li>
     *     <li>既不读 loginId 也不读 loginType。本方法的结果与「是谁」无关，
     *     提前返回同时避免了「为了一个用不上的参数去查一次库」的浪费。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把「返回空集合」理解成「这个用户什么都不能做」。
     * 权限点与角色是两套彼此独立的判定通道——@SaCheckRole 走 getRoleList，
     * @SaCheckPermission 才走本方法。让两者互相借用（例如在这里返回角色的名字，
     * 或让 getRoleList 去查权限点）会让两套判定互相污染，而且不会报错，
     * 只表现为某些注解时灵时不灵。另外 List.of() 返回的是不可变集合，
     * 调用方若试图往返回值里 add 元素，会立刻抛 UnsupportedOperationException。
     *
     * @param loginId 登录账号 id（即 StpUtil.login() 时传入的那个值），
     *                本项目统一是 Long 型用户 id；本方法不使用它，传 null 也不会抛异常
     * @param loginType 账号体系标识，本项目只有一套账号体系，固定为 "login"；本方法不使用它
     * @return 恒为空集合（不可变），表示本系统不使用细粒度权限点
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不抛业务异常：
     * 它不查库、不解析参数，是纯粹的常量返回。
     * 鉴权不通过由 Sa-Token 自己抛它自己的异常，不会从这里冒出来
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /**
     * 查询该账号拥有的角色集合，角色值直接取自 sys_user.user_role
     *
     * <p>为什么这样设计：角色只存一个值、只从数据库读，不做缓存也不做映射。
     * 不缓存的原因是这个方法的调用频率远低于想象——只有带 @SaCheckRole 的接口才会触发，
     * 而这类接口基本都是管理端操作；一旦加了缓存，就必须回答「改角色时什么时候删缓存」，
     * 漏删的表现是「明明撤了管理员权限，他还一直是管理员」，比慢一点危险得多。
     * 不映射（不把库里的值翻成别的写法）的原因是 Sa-Token 对角色做的是精确字符串比较，
     * 中间加一层翻译只会让「库里写的是什么、注解里该写什么」多一个需要对齐的环节。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>把 loginId 转成 Long：登录时传的类型取决于调用方，
     *     有人写成 StpUtil.login("10001") 时这里拿到的就是字符串，
     *     不转就直接比较会得到「查不到人 → 无角色」这种难以定位的结果；</li>
     *     <li>按主键查账号。这个查询会被自动追加 deleted_at = 0 的条件，
     *     所以「已注销账号」在这一步直接就是 null，不需要额外判断；</li>
     *     <li>角色为空直接返回空集合。角色列允许为空（历史数据、人工改库都可能留下空值），
     *     空字符串若被当成角色返回，Sa-Token 会认为该账号「拥有一个名字为空字符串的角色」，
     *     虽然匹配不上任何注解，但日志与排查时会非常费解；</li>
     *     <li>封禁账号一律不授予角色。这一步必须排在返回角色之前，
     *     否则封禁只能靠「下次登录被拦」生效，而已经签发的 token 在过期前仍然可用；</li>
     *     <li>返回库里的原始角色值，由 Sa-Token 与注解入参做精确比较。</li>
     * </ol>
     *
     * <p>容易踩的坑：角色名大小写敏感且不做层级包含。库里存 admin、
     * 注解写 @SaCheckRole("Admin") 就永远不通过；而「管理员自动拥有成员权限」这种想法
     * 在这里也不成立——需要两种角色都能访问时必须显式写
     * @SaCheckRole(value = {"member", "admin"}, mode = SaMode.OR)。
     * 另一个坑是本方法在每个带角色注解的请求上都会查一次库，
     * 所以它绝不能顺手再做别的业务查询。
     *
     * @param loginId 登录账号 id，由 Sa-Token 从 token 中取出后传入；
     *                非数字（例如脏 token 里的任意字符串）时按「无角色」处理，不抛异常
     * @param loginType 账号体系标识，固定为 "login"；本方法不使用它
     * @return 角色集合；账号不存在、已逻辑删除、已封禁或角色为空时返回空集合
     * （空集合的含义是「没有任何角色」，因此所有 @SaCheckRole 都会判定为无权限）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法不抛业务异常：
     * 所有异常情况（loginId 非数字、账号不存在、角色为空、账号被封禁）
     * 都收敛成「返回空集合」，让 Sa-Token 走它自己的「无权限」分支。
     * 若在这里抛异常，鉴权失败会变成 500，而语义上它应该是 403
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
