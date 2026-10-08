package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.constant.AiRateLimitConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

/**
 * 游客 AI 提问的 IP 级限流
 *
 * author: shaoshing
 *
 * <p><b>只管游客</b>：登录用户走 {@code studio.ai.query-limit}（按用户计数，与 IP 无关），
 * 限流对他们既没必要（有配额兜底）也不公平（同一出口 IP 的多个用户会互相拖累）。
 *
 * <p><b>为什么用 Lua 而不是「INCR 后判断 ==1 再 EXPIRE」</b>（本类最重要的选择）：
 * 两步写法在<b>进程崩溃于两步之间</b>时会留下一个<b>没有 TTL 的 key</b>——
 * 那个 IP 会被<b>永久限流</b>，而且没有任何报错，只能靠运维手动删 key 恢复。
 * 这比「偶尔多放几个请求」严重得多。Lua 让 INCR 与 EXPIRE 在 Redis 侧<b>原子完成</b>，
 * 不存在中间状态。代价是要维护一段脚本，值得。
 *
 * <p><b>降级纪律</b>：Redis 不可用时<b>放行</b>（可用性优先），只告警。
 * 与配额计数的降级是同一条纪律——限流是防护手段，不能反过来变成故障源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRateLimitManager {

    /**
     * 原子自增 + 首次设置过期时间（Lua）
     *
     * <pre>
     * KEYS[1] = 限流键  ARGV[1] = TTL 秒数
     * 返回自增后的值
     * </pre>
     */
    private static final String LUA_INCR_WITH_EXPIRE =
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current";

    private final DefaultRedisScript<Long> incrScript =
            new DefaultRedisScript<>(LUA_INCR_WITH_EXPIRE, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final AiProperties aiProperties;

    /**
     * 游客提问前的限流检查
     *
     * <p><b>调用位置必须在最外层</b>（配额预检、检索、模型调用之前）：
     * 超限的请求不该触发后面的检索与 Embedding——那才是真正的开销。
     *
     * @param ip 客户端 IP（为 null 或空时直接放行：拿不到 IP 不能变成「拒绝所有人」）
     * @throws BusinessException 超过任一窗口上限时抛出 A0501
     */
    public void assertAllowed(String ip) {
        Boolean enabled = aiProperties.getGuestIpLimitsEnabled();
        if (enabled != null && !enabled) {
            return;
        }
        if (StrUtil.isBlank(ip)) {
            // 拿不到 IP（例如 MockMvc 没设置 remoteAddr）：放行。
            // 宁可漏限也不能误杀——误杀会让整个匿名接口不可用
            return;
        }

        checkWindow(AiRateLimitConstant.minuteKey(ip, AiRateLimitConstant.currentMinute()),
                aiProperties.getGuestIpMinuteLimit(), AiRateLimitConstant.MINUTE_KEY_TTL_SECONDS,
                "每分钟");
        checkWindow(AiRateLimitConstant.dayKey(ip, AiRateLimitConstant.currentDay()),
                aiProperties.getGuestIpDailyLimit(), dayTtlSeconds(),
                "每天");
    }

    /**
     * 检查单个窗口
     *
     * @param key   窗口键
     * @param limit 上限（null 表示不限制）
     * @param ttl   TTL 秒数
     * @param label 文案用的窗口名
     */
    private void checkWindow(String key, Integer limit, long ttl, String label) {
        if (limit == null || limit <= 0) {
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
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    "提问太频繁了（游客" + label + "最多 " + limit + " 次），请稍后再试或登录后继续");
        }
    }

    /**
     * 日窗口键的 TTL：距次日零点的秒数 + 余量
     *
     * <p>用「到次日零点」而不是固定 24 小时：固定 24h 会让早上 9 点创建的 key
     * 活到第二天 9 点，覆盖了新一天窗口的开端。
     *
     * @return TTL 秒数
     */
    private long dayTtlSeconds() {
        long secondsToMidnight = AiRateLimitConstant.SECONDS_PER_DAY - LocalTime.now().toSecondOfDay();
        return secondsToMidnight + AiRateLimitConstant.DAY_KEY_TTL_MARGIN_SECONDS;
    }
}
