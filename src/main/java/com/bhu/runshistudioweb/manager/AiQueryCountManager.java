package com.bhu.runshistudioweb.manager;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bhu.runshistudioweb.constant.AiQueryCountConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import lombok.RequiredArgsConstructor;
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
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiQueryCountManager {

    /** Redis 不可用时的降级自增 SQL：交给数据库做原子的「列 = 列 + 1」 */
    private static final String SQL_INCREASE_QUERY_COUNT = "ai_query_count = ai_query_count + 1";

    /** 计数必须用 StringRedisTemplate：value 是裸字符串，INCR / GETDEL 才能工作 */
    private final StringRedisTemplate stringRedisTemplate;

    private final SysUserMapper sysUserMapper;

    /**
     * 计数 +1（由提问成功后的落库逻辑在事务外调用）
     *
     * @param userId 登录用户 ID；不存在的 ID 在降级路径上会更新 0 行，不抛异常
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
                log.error("配额计数彻底失败（Redis 与 DB 均不可用）| userId={}", userId, dbException);
            }
        }
    }

    /**
     * 合并「数据库基准 + Redis 增量」得到真实用量
     *
     * @param userId 用户 ID
     * @param dbValue 数据库基准值，可为 null（按 0 处理）
     * @return 真实用量；Redis 不可用时等于基准值（可能偏小，预检偏松）
     */
    public int merge(long userId, Integer dbValue) {
        int base = dbValue == null ? 0 : dbValue;
        return base + redisDelta(userId);
    }

    /**
     * 读 Redis 里的未落库增量
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
     * 回刷：把 Redis 里的增量落进数据库（定时任务驱动，也可手工或测试调用）
     *
     * 多实例安全：GETDEL 是原子的，只有一个实例能取到值，其它实例取到 null 直接跳过。
     */
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
            // 原子取走并清零（GETDEL）
            Integer delta = takeAndDelete(key);
            if (delta == null || delta <= 0) {
                continue;
            }
            try {
                // 原子累加到 DB 基准值
                sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        // delta 来自 Redis 的整数（已 parseInt），不是用户输入，拼接安全
                        .setSql("ai_query_count = ai_query_count + " + delta));
                flushedUsers++;
                flushedTotal += delta;
            } catch (Exception e) {
                restore(key, delta);
                log.error("配额计数回刷失败，已回补 Redis 待下轮重试 | userId={} delta={}", userId, delta, e);
            }
        }
        if (flushedUsers > 0) {
            log.info("AI 配额计数回刷完成 | 用户数={} | 计数合计={}", flushedUsers, flushedTotal);
        }
    }

    /** 原子取出并删除（GETDEL），避免「读到值、但删除前崩溃」导致的重复计数 */
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
            log.warn("计数增量不是整数，丢弃该 key | key={} value={}", key, value);
            return null;
        }
    }

    /** 回补：把未成功落库的增量还回 Redis */
    private void restore(String key, int delta) {
        try {
            stringRedisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            // 回补也失败：增量彻底丢失，只能告警（计数少一点不会造成安全问题）
            log.error("配额计数回补失败，本次增量可能丢失 | key={} delta={}", key, delta, e);
        }
    }

    /** 按前缀增量扫描待回刷的 key */
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

    /** 从 key 里解析用户 ID */
    private Long parseUserId(String key) {
        String suffix = key.substring(AiQueryCountConstant.KEY_PREFIX.length());
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把某个用户的配额清零（管理端「重置次数」用）
     *
     * @param userId 用户 ID
     * @return 重置前的真实用量（便于管理端提示「已从 N 次清零」）
     */
    public int reset(long userId) {
        // 先记下重置前的用量：必须在清空之前读，否则返回值恒为 0
        int usedBefore = merge(userId, readDbCount(userId));
        clearOnce(userId);
        // 复核：仍大于 0 说明撞上了回刷，再清一次（此时回刷已结束，一定成功）
        if (merge(userId, readDbCount(userId)) > 0) {
            log.warn("配额重置后复核仍有残留，再清一次（可能撞上了回刷）| userId={}", userId);
            clearOnce(userId);
        }
        log.info("AI 配额已重置 | userId={} | 重置前用量={}", userId, usedBefore);
        return usedBefore;
    }

    /** 清一次：DB 基准置 0 + 删除 Redis 增量键 */
    private void clearOnce(long userId) {
        try {
            sysUserMapper.update(null, new UpdateWrapper<SysUser>()
                    .eq("id", userId)
                    .set("ai_query_count", 0));
        } catch (Exception e) {
            log.error("重置配额时清 DB 失败 | userId={}", userId, e);
        }
        try {
            stringRedisTemplate.delete(AiQueryCountConstant.keyOf(userId));
        } catch (Exception e) {
            log.error("重置配额时删 Redis 键失败 | userId={}", userId, e);
        }
    }

    /** 读 DB 里的配额基准值（读不到按 0 处理） */
    private int readDbCount(long userId) {
        try {
            SysUser user = sysUserMapper.selectById(userId);
            return user == null || user.getAiQueryCount() == null ? 0 : user.getAiQueryCount();
        } catch (Exception e) {
            log.warn("读取配额基准值失败，按 0 处理 | userId={}", userId, e);
            return 0;
        }
    }
}
