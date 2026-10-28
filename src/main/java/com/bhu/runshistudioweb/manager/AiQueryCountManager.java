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
 * 计数放 Redis 而不是数据库：提问成功后要写三处，计数若也在事务里，每次提问都会多一次
 * 对 sys_user 的 UPDATE。计数高频、可最终一致，用 INCR 承接最合适。
 *
 * 一致性模型是「数据库基准 + Redis 增量」：写只 INCR，读时两者相加，定时任务 GETDEL 取走再原子累加。
 * 必须用 StringRedisTemplate：项目的 RedisTemplate 配了 JSON 序列化器，INCR 会报「value is not an integer」。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiQueryCountManager {

    /**
     * Redis 不可用时的降级自增 SQL：交给数据库做原子的「列 = 列 + 1」
     *
     * 写成「先查出来再写回」会在并发时互相覆盖，计数偏小等于白送额度。
     * 列名硬编码是安全的，它不来自用户输入。
     */
    private static final String SQL_INCREASE_QUERY_COUNT = "ai_query_count = ai_query_count + 1";

    /**
     * 计数必须用 StringRedisTemplate：value 是裸字符串，INCR / GETDEL 才能工作
     */
    private final StringRedisTemplate stringRedisTemplate;

    private final SysUserMapper sysUserMapper;

    /**
     * 计数 +1（由提问成功后的落库逻辑在事务外调用）
     *
     * 坑一：必须在数据库事务之外。Redis 是外部系统，事务回滚撤不回已发出的 INCR，
     * 放进事务里就会出现「回答没存成功、额度却被扣了」。
     * 坑二：本方法吞掉所有异常，调用方不能靠它判断「扣额度是否成功」。额度是防护性上限、
     * 不是计费依据，丢一两次不会造成安全问题；而让计数失败导致用户拿不到已生成的回答，才是真损失。
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
                // 两条路都失败：只能记日志。计数丢失不影响本次回答已经给出，
                // 不能因此让整个提问接口失败
                log.error("配额计数彻底失败（Redis 与 DB 均不可用）| userId={}", userId, dbException);
            }
        }
    }

    /**
     * 合并「数据库基准 + Redis 增量」得到真实用量
     *
     * 预检与「我的信息」都必须用它，否则增量还没回刷时用户看到的数字会比实际少，
     * 提问一次后刷新页面数字纹丝不动。
     *
     * 坑：读的是「当前值」，并发下两个请求可能读到同一数字、都能通过预检而略微超限。
     * 这是刻意接受的：配额是防护性上限，为此加锁得不偿失。
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
     * 读取失败返回 0，即降级为只认数据库值：预检偏松但接口不报错。
     *
     * 坑：不能只调 Integer.parseInt 而不接住异常——一个被写坏的 key 会让该用户每次提问
     * 都抛 NumberFormatException，换个人就正常，极难排查。
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
     * 顺序必须是「先 GETDEL 取走再写库」：GETDEL 之后 Redis 已清零，即使 UPDATE 失败
     * 也不会把同一份增量写两次——重复计数会让用户白白损失额度，比短暂少计数严重得多。
     * UPDATE 失败时把增量 INCRBY 还回去，下一轮重试。
     *
     * 多实例安全：GETDEL 是原子的，只有一个实例能取到值，其它实例取到 null 直接跳过。
     *
     * 坑：单个 key 的异常绝不能向上抛，一个人的计数坏掉不该拖累整轮回刷。
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
     * 原子取出并删除（GETDEL），避免「读到值、但删除前崩溃」导致的重复计数
     *
     * 拆成 GET + DEL 时，两步之间崩溃会让下一轮再加一遍，用户的额度凭空少一截。
     *
     * 坑：脏值分支必须返回 null 而不是抛异常，它一旦抛出，排在后面的用户全部得不到回刷。
     *
     * @param key 完整 key
     * @return 增量；key 不存在、读取失败或值不是整数时为 null
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
     * 用 INCRBY 而不是 SET：从「取走」到「回补」之间可能又有新的提问加了起来，
     * SET 会覆盖掉那些新计数。
     *
     * 坑：不要顺手重置 key 的过期时间。计数键不该有 TTL，否则未回刷的增量会凭空消失。
     *
     * @param key 完整 key
     * @param delta 增量（调用方已保证大于 0）
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
     * 按前缀增量扫描待回刷的 key
     *
     * 用 SCAN 而不是 KEYS：KEYS 遍历整个键空间并阻塞 Redis 单线程，key 一多就拖垮所有命令。
     *
     * 坑：游标必须用 try-with-resources 关闭，否则连接不归还，跑几轮连接池就耗尽。
     *
     * @return key 列表；没有待回刷的 key 或扫描失败时返回空列表（不为 null）
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
     * 前缀长度取自常量类，不能硬编码：前缀一改，硬编码的长度就会截错位置，
     * 把别人的额度加错人且不报错。
     *
     * @param key 完整 key（调用方已保证以约定前缀开头）
     * @return 用户 ID；后半段不是数字时返回 null
     */
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
     * 真实用量是「DB 基准 + Redis 未回刷增量」，所以两处都要清：
     * 只清 DB 的话，回刷前的那些提问仍会被 merge 算进用量，用户重置完还是满的。
     *
     * 清完立刻复核一次，是为了覆盖一个窄窗口：回刷任务可能正好在
     * 「GETDEL 取走增量」与「UPDATE 写回 DB」之间，导致刚清掉的额度又被写回来。
     * 这个窗口只有毫秒级、且只在用户已经用完额度时才会触发（此时他不会同时提问），
     * 所以不必上分布式锁——复核一次就够，代价只是一次读。
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

    /**
     * 清一次：DB 基准置 0 + 删除 Redis 增量键
     *
     * 顺序是「先 DB 后 Redis」：反过来的话，清完 Redis 若 DB 写失败，
     * 用量会退回到基准值，看起来像「重置了一半」。
     * 两处各自吞异常：任何一处失败都不该让整个重置接口报错，
     * 管理员看列表刷新后的数字就知道成没成。
     *
     * 这里用 UpdateWrapper（字符串列名）而不是 LambdaUpdateWrapper：
     * lambda 写法需要 MyBatis-Plus 的实体元数据缓存，在纯单元测试里没有容器初始化，
     * 构造 wrapper 时就会抛「can not find lambda cache for this entity」——
     * 而 reset 又吞异常，于是测试里表现为「静默什么都没做」。
     * 列名是硬编码常量、不来自用户输入，没有注入面。
     *
     * @param userId 用户 ID
     */
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

    /**
     * 读 DB 里的配额基准值（读不到按 0 处理）
     *
     * @param userId 用户 ID
     * @return 基准值；用户不存在或查询失败时为 0
     */
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
