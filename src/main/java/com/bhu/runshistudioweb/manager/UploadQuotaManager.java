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

    /** 当天的配额键 */
    private String keyOf(long userId) {
        return KEY_PREFIX + userId + ":" + LocalDate.now().toString().replace("-", "");
    }

    /** 次日零点（系统时区） */
    private Instant nextMidnight() {
        return LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
    }
}
