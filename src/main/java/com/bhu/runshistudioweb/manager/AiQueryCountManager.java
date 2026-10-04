package com.bhu.runshistudioweb.manager;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bhu.runshistudioweb.constant.AiQueryCountConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 提问配额计数的读写与回刷
 *
 * author: shaoshing
 *
 * <p><b>为什么把计数从 DB 挪到 Redis</b>：提问成功后要写「assistant 消息 + 会话 updated_at + 计数」
 * 三处，其中前两处必须在数据库事务里，而计数原本也被塞进了同一个事务——
 * 于是每次提问都要多一次 {@code UPDATE sys_user}。计数是<b>高频、单点、可最终一致</b>的写入，
 * 用 Redis 的 {@code INCR}（内存原子自增）承接最合适：DB 只保留基准值，由定时任务批量回刷。
 *
 * <p><b>一致性模型：DB 基准 + Redis 增量</b>
 * <pre>
 * 真实用量 = sys_user.ai_query_count（基准） + Redis 里的增量
 * 写：INCR（不碰 DB）
 * 读：两者相加（见 {@link #merge}）
 * 落：定时任务 GETDEL 取走增量 → UPDATE ... + n → 提交
 * </pre>
 * 因此「预检」不依赖回刷是否发生——增量在 Redis 里也能被读到（AC 3）。
 *
 * <p><b>为什么注入 {@link StringRedisTemplate} 而不是项目里的 {@code RedisTemplate<String,Object>}</b>：
 * 后者给 value 配了 JSON 序列化器（且 Long 被序列化成字符串），
 * 写进去的是 {@code {"@class":...,"value":"1"}} 这种文本——
 * {@code INCR} 会直接报 {@code value is not an integer}，而且读回来的也不是数字。
 * 计数需要的是<b>裸字符串数字</b>，只有 StringRedisTemplate 满足。
 *
 * <p><b>降级纪律</b>：Redis 不可用时，写退化为「直接 UPDATE DB」、读退化为「只认 DB 值」。
 * 计数可以短暂不准，但<b>功能永远可用</b>——这是「可用性优先于一致性」的明确取舍。
 */
@Slf4j
@Component
public class AiQueryCountManager {

    /**
     * Redis 不可用时的降级自增 SQL（原子：交给数据库做 {@code 列 = 列 + 1}）
     *
     * <p>硬编码列名是安全的：它是常量，不来自用户输入
     */
    private static final String SQL_INCREASE_QUERY_COUNT = "ai_query_count = ai_query_count + 1";

    /**
     * 计数必须用 StringRedisTemplate：value 是裸字符串，INCR / GETDEL 才能工作
     * （用项目默认的 JSON RedisTemplate 会让 INCR 报 "value is not an integer"，原因见类注释）
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * 计数 +1（<b>事务外调用</b>，Redis 是外部系统，不进数据库事务）
     *
     * <p>{@code INCR} 本身是原子的：并发 20 个请求同时 +1，结果一定是 20，
     * 不存在「读改写」的竞态（这正是它替代 {@code SELECT → +1 → UPDATE} 的理由）。
     *
     * <p>Redis 抛异常时降级为直写 DB：宁可多一次 DB 写，也不能让计数丢掉。
     *
     * @param userId 登录用户 ID
     */
    public void increment(long userId) {
        String key = AiQueryCountConstant.keyOf(userId);
        try {
            stringRedisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            // 降级路径：Redis 挂了也要能计数（可用性优先），代价是又回到每次提问一次 DB 写
            log.error("Redis 计数失败，降级为直写 DB | userId={}", userId, e);
            try {
                sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        .setSql(SQL_INCREASE_QUERY_COUNT));
            } catch (Exception dbException) {
                // 两条路都失败：只能记日志。计数丢失不影响本次回答已经给出，
                // 不能因此让整个提问接口失败
                log.error("配额计数彻底失败（Redis 与 DB 均不可用）| userId={}", userId, dbException);
            }
        }
    }

    /**
     * 合并「DB 基准 + Redis 增量」得到真实用量
     *
     * <p>配额预检与「我的信息」都必须用它，否则用户看到的数字会比实际少
     * （增量还没回刷时尤其明显：提问一次后刷新页面，数字纹丝不动）。
     *
     * @param userId  用户 ID
     * @param dbValue DB 里的基准值（可为 null，按 0 处理）
     * @return 真实用量
     */
    public int merge(long userId, Integer dbValue) {
        int base = dbValue == null ? 0 : dbValue;
        return base + redisDelta(userId);
    }

    /**
     * 读 Redis 里的未落库增量
     *
     * <p>读取失败（Redis 不可用）时返回 0：<b>降级为「只认 DB 值」</b>。
     * 这会让预检偏松（可能略微超限），但不会让接口报错——与配额作为「防护性上限」
     * 的定位一致（预检本来就容忍并发窗口内的轻微超限）。
     *
     * @param userId 用户 ID
     * @return 增量；无 key 或 Redis 不可用时为 0
     */
    public int redisDelta(long userId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(AiQueryCountConstant.keyOf(userId));
            if (value == null) {
                return 0;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            log.warn("读取 Redis 配额增量失败，按 0 处理（可用性优先）| userId={}", userId, e);
            return 0;
        }
    }

    /**
     * 回刷：把 Redis 里的增量落到 DB（由定时任务驱动，也可手工/测试调用）
     *
     * <p><b>顺序必须是「先取走（GETDEL）再写 DB」</b>：
     * <ul>
     *     <li>GETDEL 后 Redis 已清零，此时即使 UPDATE 失败，也不会出现「同一份增量被写两次」
     *     （重复计数比短暂少计数严重得多——后者只是预检偏松）；</li>
     *     <li>UPDATE 失败时会 {@code INCRBY} 把增量还回 Redis，下一轮重试，
     *     因此「失败可容忍但不丢计数」。</li>
     * </ul>
     *
     * <p><b>多实例安全</b>：多个实例同时跑回刷也没问题——
     * {@code GETDEL} 是原子的，只有一个实例能取到值，其它实例取到 null 直接跳过。
     */
    // 用 fixedDelayString 而不是 fixedDelay：周期可配置，
    // 测试里可以把它调到 1 小时，避免定时任务在用例执行途中把待断言的增量清掉
    // （键与默认值见 AiQueryCountConstant.FLUSH_DELAY_PROPERTY / DEFAULT_FLUSH_DELAY_MS）
    @Scheduled(fixedDelayString = "${studio.ai.query-count-flush-delay-ms:60000}")
    public void flushToDb() {
        List<String> keys = scanKeys();
        if (keys.isEmpty()) {
            return;
        }

        int flushedUsers = 0;
        int flushedTotal = 0;
        for (String key : keys) {
            Long userId = parseUserId(key);
            if (userId == null) {
                log.warn("计数 key 无法解析出用户 ID，跳过 | key={}", key);
                continue;
            }
            // ① 原子取走并清零（GETDEL）
            Integer delta = takeAndDelete(key);
            if (delta == null || delta <= 0) {
                continue;
            }
            try {
                // ② 原子累加到 DB 基准值
                sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        // delta 来自 Redis 的整数（已 parseInt），不是用户输入，拼接安全
                        .setSql("ai_query_count = ai_query_count + " + delta));
                flushedUsers++;
                flushedTotal += delta;
            } catch (Exception e) {
                // ③ 写失败：把增量还回去，下一轮重试（否则这次的计数就真丢了）
                restore(key, delta);
                log.error("配额计数回刷失败，已回补 Redis 待下轮重试 | userId={} delta={}", userId, delta, e);
            }
        }
        if (flushedUsers > 0) {
            log.info("AI 配额计数回刷完成 | 用户数={} | 计数合计={}", flushedUsers, flushedTotal);
        }
    }

    /**
     * 原子取出并删除（Redis {@code GETDEL}）：避免「读到值、但删除前崩溃」导致的重复计数
     *
     * @param key 完整 key
     * @return 增量；key 不存在时为 null
     */
    private Integer takeAndDelete(String key) {
        String value;
        try {
            value = stringRedisTemplate.opsForValue().getAndDelete(key);
        } catch (Exception e) {
            // 单个 key 读失败（Redis 抖动）：跳过它，绝不中断整轮回刷
            log.warn("取出计数增量失败，跳过该 key | key={}", key, e);
            return null;
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // 脏值（被人手工写坏 / 序列化异常）：丢弃并告警。
            // 注意 key 已被 GETDEL 删除，所以这个脏值不会在下轮被反复处理；
            // 更重要的是——它不能中断整轮回刷，否则一个人的计数坏掉会拖累所有人
            log.warn("计数增量不是整数，丢弃该 key | key={} value={}", key, value);
            return null;
        }
    }

    /**
     * 回补：把未成功落库的增量还回 Redis
     *
     * @param key   完整 key
     * @param delta 增量
     */
    private void restore(String key, int delta) {
        try {
            stringRedisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            // 回补也失败：增量彻底丢失，只能告警（计数少一点不会造成安全问题）
            log.error("配额计数回补失败，本次增量可能丢失 | key={} delta={}", key, delta, e);
        }
    }

    /**
     * 按前缀增量扫描待回刷的 key（用 SCAN 而不是 KEYS，原因见常量注释）
     *
     * @return key 列表
     */
    private List<String> scanKeys() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(AiQueryCountConstant.KEY_PATTERN)
                .count(AiQueryCountConstant.SCAN_COUNT)
                .build();
        List<String> keys = stringRedisTemplate.execute((RedisCallback<List<String>>) connection -> {
            List<String> found = new ArrayList<>();
            // Cursor 必须关闭，否则连接不会归还
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    found.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return found;
        });
        return keys == null ? List.of() : keys;
    }

    /**
     * 从 key 里解析用户 ID
     *
     * @param key 完整 key
     * @return 用户 ID；解析失败返回 null
     */
    private Long parseUserId(String key) {
        String suffix = key.substring(AiQueryCountConstant.KEY_PREFIX.length());
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
