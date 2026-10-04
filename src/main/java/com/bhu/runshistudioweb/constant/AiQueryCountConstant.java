package com.bhu.runshistudioweb.constant;

/**
 * AI 提问配额计数的常量
 *
 * author: shaoshing
 *
 * <p>键、扫描模式、回刷周期全部集中在这里，业务代码里<b>禁止出现魔法字符串</b>：
 * 计数用的是 Redis 的<b>前缀删除与扫描</b>（回刷时按前缀找出所有待落库的 key），
 * 前缀一旦在两处写得不一致，回刷就会静默漏掉一部分用户——表现为「某些人的计数永远不涨」，
 * 而这种 bug 只有在对账时才会被发现。
 */
public final class AiQueryCountConstant {

    /**
     * 计数增量键前缀：{@code studio:ai:query:count:{userId}}
     *
     * <p><b>存的是「未落库的增量」而不是总数</b>：DB 里的 {@code sys_user.ai_query_count}
     * 是基准值，Redis 里只放「还没回刷到 DB 的那部分」。
     * 读的时候两者相加才是真实用量（见 {@code AiQueryCountManager#merge}）。
     *
     * <p><b>为什么是 String key 而不是 Hash</b>：回刷需要<b>原子读删</b>
     * （{@code GETDEL}，Redis 6.2+，本机 8.10 支持）——
     * 如果用 Hash，只能「先 HGET 取值、再 HDEL 删除」，两步之间进程崩溃就会
     * <b>重复计数</b>（值已读到但没删掉，下轮又加一遍）。
     * String key 让 {@code GETDEL} 一步完成「取出并清零」，没有中间状态。
     */
    public static final String KEY_PREFIX = "studio:ai:query:count:";

    /**
     * 回刷时用来 SCAN 的模式（带通配）
     *
     * <p>用 {@code SCAN} 而不是 {@code KEYS}：后者会一次性遍历整个 keyspace 并阻塞 Redis
     * 单线程，key 多了会拖垮其它命令。SCAN 是增量式游标，代价只是多几次往返。
     */
    public static final String KEY_PATTERN = KEY_PREFIX + "*";

    /**
     * 回刷周期的配置键：{@code studio.ai.query-count-flush-delay-ms}
     *
     * <p>用配置而不是写死的常量，是为了让<b>测试能把周期调到足够长</b>：
     * 否则定时任务会在用例执行途中把 Redis 里的增量清零，
     * 让「提问后 Redis 应为 1」这类断言变成<b>偶发失败</b>（取决于用例跑到第几秒）。
     * 偶发失败的测试比没有测试更糟——它会让人习惯于「重跑一次就好了」。
     */
    public static final String FLUSH_DELAY_PROPERTY = "studio.ai.query-count-flush-delay-ms";

    /**
     * 回刷周期默认值（毫秒）：60s
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：回刷涉及逐条 UPDATE，
     * 若上一轮因为慢查询拖长了，fixedRate 会让下一轮立刻叠上来，形成堆积。
     */
    public static final long DEFAULT_FLUSH_DELAY_MS = 60_000L;

    /**
     * 每轮 SCAN 的游标批大小
     *
     * <p>它是「每次 SCAN 返回多少个元素」的<b>建议值</b>，不是总量上限；
     * 200 是个平衡：太小则往返次数多，太大则单次阻塞时间长。
     */
    public static final long SCAN_COUNT = 200L;

    /**
     * 拼接某个用户的计数键
     *
     * @param userId 用户 ID
     * @return 完整的 Redis key
     */
    public static String keyOf(long userId) {
        return KEY_PREFIX + userId;
    }

    private AiQueryCountConstant() {
    }
}
