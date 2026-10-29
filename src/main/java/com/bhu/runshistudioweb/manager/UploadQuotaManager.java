package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.UploadProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 上传配额（每个账号每天 N 次）
 *
 * 为什么用 Redis 而不查库：上传是「一次请求一次配额」的高频判断，走 Redis 的 INCR 是 O(1)；
 * 落库要么多一张计数表，要么每次 count 一遍上传记录，都得为一张低频功能付出长期成本。
 *
 * 为什么 fail-open：Redis 挂了就不让用户上传，是把「缓存故障」升级成「功能不可用」。
 * 配额是防滥用，不是安全边界（真正的边界是登录 + 类型校验 + 10MB 限制），
 * 因此故障时放行并打 warn，让运维能从日志发现缓存异常。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadQuotaManager {

    /** 配额键前缀：studio:upload:quota:{userId}:{yyyyMMdd} */
    private static final String KEY_PREFIX = "studio:upload:quota:";

    private final StringRedisTemplate stringRedisTemplate;

    private final UploadProperties uploadProperties;

    /**
     * 校验并占用一次上传配额
     *
     * 先 INCR 再判断：读-改-写会让并发请求同时看到「还没超」，等于配额形同虚设；
     * INCR 的返回值是本请求在当天的序号，天然原子。
     *
     * 每次都给键续一次过期时间（而不是只在第一次设）：万一 expire 那次失败，
     * 下一次上传会把 TTL 补回来，不会留下一个永不过期的计数键把人永久锁死。
     *
     * @param userId 登录用户 ID，调用方保证已登录
     * @throws BusinessException 超过当日上限时抛 A0501
     */
    public void assertAllowed(long userId) {
        if (!Boolean.TRUE.equals(uploadProperties.getEnabled())) {
            return;
        }
        Integer limit = uploadProperties.getDailyLimit();
        if (limit == null || limit <= 0) {
            return;
        }

        String key = keyOf(userId);
        try {
            Long used = stringRedisTemplate.opsForValue().increment(key);
            // 过期时刻固定为「次日零点」，与自然日口径一致；同时避免跨天时旧计数延续
            stringRedisTemplate.expireAt(key, nextMidnight());
            if (used != null && used > limit) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                        "今日上传次数已达上限（" + limit + " 次），请明天再试");
            }
        } catch (BusinessException e) {
            // 业务异常必须原样抛出：它代表「确实超限了」，不是缓存故障
            throw e;
        } catch (Exception e) {
            // 只打一行，不打整段堆栈：Redis 抖动时高频上传会把日志刷满
            log.warn("上传配额检查失败，已放行 | userId={} | 原因={}", userId, e.getMessage());
        }
    }

    /**
     * 当天的配额键
     *
     * 日期进键而不是只存一个计数器：跨天自然从 0 开始，不需要定时任务清零，
     * 也避免了「今天多传几次、明天就不能传」这类跨天残留。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    private String keyOf(long userId) {
        return KEY_PREFIX + userId + ":" + LocalDate.now().toString().replace("-", "");
    }

    /**
     * 次日零点（系统时区）
     *
     * @return 过期时刻
     */
    private Instant nextMidnight() {
        return LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
    }
}
