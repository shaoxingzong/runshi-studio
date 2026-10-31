package com.bhu.runshistudioweb.constant;

/**
 * AI 提问配额计数的常量
 *
 * @author shaoshing
 */
public final class AiQueryCountConstant {

    /** 计数增量键前缀：studio:ai:query:count:{userId} */
    public static final String KEY_PREFIX = "studio:ai:query:count:";

    /** 回刷时用来 SCAN 的模式（带通配） */
    public static final String KEY_PATTERN = KEY_PREFIX + "*";

    /** 回刷周期的配置键：studio.ai.query-count-flush-delay-ms */
    public static final String FLUSH_DELAY_PROPERTY = "studio.ai.query-count-flush-delay-ms";

    /** 回刷周期默认值（毫秒）：60s */
    public static final long DEFAULT_FLUSH_DELAY_MS = 60_000L;

    /** 每轮 SCAN 的游标批大小 */
    public static final long SCAN_COUNT = 200L;

    /**
     * 拼接某个用户的 AI 提问计数键
     *
     * @param userId 用户 ID，必须是真实存在的登录用户 ID（雪花算法生成的 19 位长整型）；传 0 或负数不会报错，但会得到一个永远不会被读写的孤立键
     * @return 完整的 Redis 键，形如 {@code studio:ai:query:count:1234567890123456789}，不会返回 null
     */
    public static String keyOf(long userId) {
        return KEY_PREFIX + userId;
    }

    private AiQueryCountConstant() {
    }
}
