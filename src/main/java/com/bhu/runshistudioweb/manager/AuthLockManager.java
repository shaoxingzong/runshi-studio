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
 * 为什么是独立的一个类，不并进 IpRateLimitManager：两者模型不同。
 * IP 限流是「固定窗口内的频率控制」（INCR 到上限即拒，窗口过期计数归零）；
 * 账号锁定是「连续失败累积到阈值后锁一段时间，登录成功立即清零」。
 * 把两种语义塞进一个类，方法签名与注释很快就会互相腐化，各自独立才说得清。
 *
 * 与 IP 限流的分工：IP 限流挡「单 IP 高频试多个账号」，但挡不住
 * 分布式慢速爆破（海量 IP 各试几次，频次远低于任何单 IP 上限）。
 * 本类以「同一账号连续失败 N 次」触发，补的正是这个盲区（见 AuthLockoutProperties）。
 *
 * 三个方法的调用时机（顺序不能乱）：
 * - assertNotLocked：登录最开始——被锁的请求不查库、不比对密码；
 * - recordFailure：仅在「账号存在但密码错误」时——账号不存在无可锁对象，
 * 且对不存在的账号锁定会让锁定提示变成账号存在性探测器；
 * - clear：登录成功时——合法用户不该被自己几分钟前的失误一直挡在门外。
 *
 * 降级纪律与 IP 限流完全一致：Redis 不可用时全部放行（只告警）。
 * 锁定是防护手段，不能反过来变成故障源——为了防刷把自己的登录功能搞挂，是本末倒置。
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
     * 带上账号名而不是用户 ID：登录时手里只有账号（ID 要查库才知道），
     * 且账号是攻击者直接接触的标识，按它计数不需要先查库。
     */
    private static final String KEY_PREFIX = "studio:auth:lock:";

    /**
     * 原子自增 + 首次设置过期时间（Lua）
     *
     * 与 IpRateLimitManager 同一段脚本、同一个理由：两步写法
     * （先 INCR、判断等于 1 再 EXPIRE）在进程崩溃于两步之间时会留下没有 TTL 的 key，
     * 那个账号会被永久锁定且没有任何报错。必须让 Redis 侧原子完成。
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
     * 登录前检查账号是否处于锁定状态
     *
     * @param account 登录账号（为空时直接放行：没有锁定对象）
     * @throws BusinessException 失败次数已达阈值（锁定窗口内）时抛出 A0501
     */
    public void assertNotLocked(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
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
     * 记录一次登录失败（仅在「账号存在但密码错误」时调用）
     *
     * TTL 只在第一次失败时设置（固定窗口，见
     * AuthLockoutProperties#getLockMinutes 关于「为什么不每次续期」的说明）：
     * 从首次失败起算锁定时长，过期后计数清零、自动恢复。
     *
     * @param account 登录账号
     */
    public void recordFailure(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            return;
        }
        long ttlSeconds = (long) authLockoutProperties.getLockMinutes() * 60;
        try {
            Long current = stringRedisTemplate.execute(
                    incrScript, List.of(key(account)), String.valueOf(ttlSeconds));
            log.info("登录失败已计数 | account={} | 窗口内第 {} 次 | 上限 {}",
                    account, current, authLockoutProperties.getMaxFailures());
        } catch (Exception e) {
            // 计数丢失（Redis 故障）不阻塞登录：本次失败仍会被「账号或密码错误」挡下，
            // 丢的只是累计进度，可用性优先
            log.warn("登录失败计数写入失败，本次仅放行计数 | account={}", account, e);
        }
    }

    /**
     * 登录成功时清零失败计数
     *
     * 合法用户输错一次就永久背 5 次计数是不合理的——成功一次即证明持有正确凭据，
     * 此前的失败应当一笔勾销。
     *
     * @param account 登录账号
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

    private String key(String account) {
        return KEY_PREFIX + account;
    }
}
