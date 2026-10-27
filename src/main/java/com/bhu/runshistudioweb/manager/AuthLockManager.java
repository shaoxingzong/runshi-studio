package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AuthLockoutProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 登录失败锁定（账号级，累积计数 + 固定窗口）
 *
 * 与 {@link IpRateLimitManager} 分开是因为模型不同：后者是固定窗口内的频率控制，
 * 本类是「连续失败累积到阈值后锁一段时间，登录成功立即清零」。它补的是 IP 限流的盲区——
 * 分布式慢速爆破用海量 IP 各试几次，频次远低于任何单 IP 上限。
 *
 * 调用时机不能乱：assertNotLocked 在登录最开始，recordFailure 仅在「账号存在但密码错误」时，
 * clear 在登录成功时。Redis 不可用时全部放行（只告警）。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthLockManager {

    /**
     * 失败计数键前缀：studio:auth:lock:{account}
     *
     * 用账号名而不是用户 ID：登录时手里只有账号，且按它计数不需要先查库。
     */
    private static final String KEY_PREFIX = "studio:auth:lock:";

    /**
     * 原子自增 + 首次设置过期时间（Lua）
     *
     * 两步写法（先 INCR、判断等于 1 再 EXPIRE）在进程崩溃于两步之间时会留下没有 TTL 的 key，
     * 那个账号会被永久锁定且没有任何报错。
     */
    private static final String LUA_INCR_WITH_EXPIRE =
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current";

    private final DefaultRedisScript<Long> incrScript =
            new DefaultRedisScript<>(LUA_INCR_WITH_EXPIRE, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final AuthLockoutProperties authLockoutProperties;

    /**
     * 登录前检查账号是否已被锁定，锁着就直接拒绝
     *
     * 必须是登录流程里最早的一道闸：被锁的请求根本不该消耗资源。一个正在被爆破的账号
     * 每分钟有成百上千次尝试，每次都放它去跑 BCrypt，防护本身就成了压垮 CPU 的那只手。
     *
     * 用固定窗口而不是每次失败续期：续期方案下攻击者每隔十几分钟试一次就能让账号永远锁定，
     * 等于把防护工具改造成拒绝服务工具。
     *
     * 坑：本方法只读不写，不会因「检查到已锁定」而延长锁定时间。
     *
     * @param account 登录账号，可为 null 或空白；为空时直接放行
     * @throws BusinessException 失败次数已达阈值时抛出 A0501，提示中带剩余锁定时长
     */
    public void assertNotLocked(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            // 空账号没有可锁对象；开关关闭时整体跳过（测试环境靠它避免用例互相干扰）
            return;
        }

        long failures;
        try {
            String value = stringRedisTemplate.opsForValue().get(key(account));
            failures = value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            // Redis 不可用：放行 + 告警（与 IP 限流同一条降级纪律）
            log.warn("账号锁定状态读取失败，本次放行 | account={}", account, e);
            return;
        }

        if (failures >= authLockoutProperties.getMaxFailures()) {
            // warn 级日志是刻意留的审计线索：账号被锁定意味着正在被针对性爆破，
            // 运维可以按它发现攻击，而不是只看到用户侧一句「请稍后再试」
            log.warn("账号登录锁定触发，拒绝请求 | account={} | 已失败 {} 次 | 阈值 {}",
                    account, failures, authLockoutProperties.getMaxFailures());
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    "登录失败次数过多，账号已锁定，请 " + authLockoutProperties.getLockMinutes() + " 分钟后再试");
        }
    }

    /**
     * 记录一次登录失败（只应在「账号存在但密码错误」时调用）
     *
     * 这条调用约定是本类最容易被忽略也最关键的：若对「账号不存在」也计数，
     * 攻击者可以拿猜出来的账号名把别人的账号刷成锁定状态；更糟的是，
     * 「已锁定」这个提示本身就变成了账号存在性探测器。
     *
     * 脚本只在计数等于 1（窗口内第一次失败）时设置过期时间，后续失败不续期，
     * 这就是「固定窗口」的实现方式。
     *
     * 坑：本方法返回 void，调用方无法知道「这次是否刚好达到阈值」——是否拒绝登录
     * 由下一次 {@link #assertNotLocked(String)} 决定，本次请求本来就该失败。
     *
     * @param account 登录账号，可为 null 或空白；为空时不做任何计数
     */
    public void recordFailure(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            return;
        }
        long ttlSeconds = (long) authLockoutProperties.getLockMinutes() * 60;
        try {
            Long current = stringRedisTemplate.execute(
                    incrScript, List.of(key(account)), String.valueOf(ttlSeconds));
            // info 而非 warn：单次失败是正常业务事件，真正的告警在「锁定被触发」那一刻
            log.info("登录失败已计数 | account={} | 窗口内第 {} 次 | 上限 {}",
                    account, current, authLockoutProperties.getMaxFailures());
        } catch (Exception e) {
            // 计数丢失（Redis 故障）不阻塞登录：本次失败仍会被「账号或密码错误」挡下，
            // 丢的只是累计进度，可用性优先
            log.warn("登录失败计数写入失败，本次仅放行计数 | account={}", account, e);
        }
    }

    /**
     * 登录成功时把该账号的失败计数清零
     *
     * 计数表达的是「连续失败」而不是「历史上失败过几次」：用户昨天错两次、今天对一次，
     * 就证明他持有正确凭据。不清零会让正常用户被自己零星的失误慢慢推向锁定阈值。
     *
     * 直接 DELETE 而不是置 0：置 0 会留下大量值为 0 却仍占内存的空壳 key。
     *
     * 坑：必须放在「密码校验通过」之后调用，否则攻击者随便提交一次就能清掉自己的失败计数。
     *
     * @param account 登录账号，可为 null 或空白；为空时直接返回
     */
    public void clear(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            return;
        }
        try {
            stringRedisTemplate.delete(key(account));
        } catch (Exception e) {
            // 清零失败不影响本次登录（凭据已验证正确），仅告警
            log.warn("登录成功计数清零失败（不影响本次登录）| account={}", account, e);
        }
    }

    /**
     * 拼出该账号的计数键
     *
     * 坑：账号名大小写敏感，{@code Admin} 与 {@code admin} 是两个键。这与登录查库的
     * 匹配方式一致，因此不会出现「换个大小写绕过锁定」的漏洞——那种写法本来也登不进去。
     *
     * @param account 登录账号，调用方保证非空白
     * @return 形如 {@code studio:auth:lock:zhangsan} 的 Redis 键
     */
    private String key(String account) {
        return KEY_PREFIX + account;
    }
}
