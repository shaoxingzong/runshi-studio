package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * IP 级双窗口限流（通用实现，登录 / 注册 / 游客提问共用）
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitManager {

    /** 原子自增 + 首次设置过期时间（Lua） */
    private static final String LUA_INCR_WITH_EXPIRE =
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current";

    private final DefaultRedisScript<Long> incrScript =
            new DefaultRedisScript<>(LUA_INCR_WITH_EXPIRE, Long.class);

    /** 分钟窗口键的时间格式（yyyyMMddHHmm，窗口 = 自然分钟） */
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 日窗口键的时间格式（yyyyMMdd，窗口 = 自然日） */
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 分钟窗口键的 TTL（秒）：120 = 窗口 60s + 余量 60s */
    private static final long MINUTE_KEY_TTL_SECONDS = 120L;

    /** 日窗口键 TTL 的额外余量（秒）：在「距次日零点」之上再加 60s，避免跨日瞬间因时钟误差出现「新窗口还没建、旧窗口已过期」的空档 */
    private static final long DAY_KEY_TTL_MARGIN_SECONDS = 60L;

    /** 一天的秒数 */
    private static final long SECONDS_PER_DAY = 24 * 60 * 60L;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 做一次限流检查：分钟窗口与日窗口任意一个超限就拒绝
     *
     * @param ip 客户端 IP，可为 null 或空白；为空时直接放行
     * @param keyPrefix 业务键前缀；登录与注册必须用不同前缀，否则登录被限流会连带堵死注册
     * @param minuteLimit 分钟窗口上限，null 或 &lt;= 0 表示关闭该窗口
     * @param dayLimit 日窗口上限，null 或 &lt;= 0 表示关闭该窗口
     * @param action 动作名，只用于拼接提示文案
     * @throws BusinessException 超过任一窗口上限时抛出 A0501
     */
    public void assertAllowed(String ip, String keyPrefix, Integer minuteLimit, Integer dayLimit, String action) {
        if (StrUtil.isBlank(ip)) {
            return;
        }

        checkWindow(minuteKey(keyPrefix, ip), ip, minuteLimit, MINUTE_KEY_TTL_SECONDS, "每分钟", action);
        checkWindow(dayKey(keyPrefix, ip), ip, dayLimit, dayTtlSeconds(), "每天", action);
    }

    /** 检查单个窗口是否超限 */
    private void checkWindow(String key, String ip, Integer limit, long ttl, String window, String action) {
        if (limit == null || limit <= 0) {
            // 上限配成 0 或负数等于关闭该窗口，而不是「一次都不许」
            return;
        }
        long current;
        try {
            Long result = stringRedisTemplate.execute(incrScript, List.of(key), String.valueOf(ttl));
            current = result == null ? 0L : result;
        } catch (Exception e) {
            // Redis 不可用：放行 + 告警（可用性优先，与配额计数降级同一纪律）
            log.warn("IP 限流不可用，本次放行 | key={}", key, e);
            return;
        }
        if (current > limit) {
            log.warn("IP 限流触发，拒绝请求 | ip={} | key={} | 当前第 {} 次 | 上限 {} 次/{}",
                    ip, key, current, limit, window);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    action + "太频繁了（" + window + "最多 " + limit + " 次），请稍后再试");
        }
    }

    /** 拼出分钟窗口的键 */
    private String minuteKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":min:" + LocalDateTime.now().format(MINUTE_FORMATTER);
    }

    /** 拼出日窗口的键（与分钟键同一思路：把日期写进键里，跨天自动换键） */
    private String dayKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":day:" + LocalDateTime.now().format(DAY_FORMATTER);
    }

    /** 算出日窗口键的 TTL：距次日零点的秒数再加 60 秒余量 */
    private long dayTtlSeconds() {
        long secondsToMidnight = SECONDS_PER_DAY - LocalTime.now().toSecondOfDay();
        return secondsToMidnight + DAY_KEY_TTL_MARGIN_SECONDS;
    }
}
