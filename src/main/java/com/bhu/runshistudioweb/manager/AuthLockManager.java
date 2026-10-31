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
 * 调用时机固定：assertNotLocked 在最前，recordFailure 仅在「账号存在但密码错误」时，
 * clear 在登录成功时。Redis 不可用时全部放行，只告警。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthLockManager {

    /** 失败计数键前缀：studio:auth:lock:{account} */
    private static final String KEY_PREFIX = "studio:auth:lock:";

    /** 原子自增 + 首次设置过期时间（Lua） */
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
            log.warn("账号登录锁定触发，拒绝请求 | account={} | 已失败 {} 次 | 阈值 {}",
                    account, failures, authLockoutProperties.getMaxFailures());
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    "登录失败次数过多，账号已锁定，请 " + authLockoutProperties.getLockMinutes() + " 分钟后再试");
        }
    }

    /**
     * 记录一次登录失败（只应在「账号存在但密码错误」时调用）
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
            log.warn("登录失败计数写入失败，本次仅放行计数 | account={}", account, e);
        }
    }

    /**
     * 登录成功时把该账号的失败计数清零
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
            log.warn("登录成功计数清零失败（不影响本次登录）| account={}", account, e);
        }
    }

    /** 拼出该账号的计数键 */
    private String key(String account) {
        return KEY_PREFIX + account;
    }
}
