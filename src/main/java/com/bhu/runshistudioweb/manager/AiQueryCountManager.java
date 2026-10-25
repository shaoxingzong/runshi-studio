package com.bhu.runshistudioweb.manager;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
 * <p>为什么把计数从数据库挪到 Redis：提问成功后要写「assistant 消息 + 会话活跃时间
 * + 配额计数」三处，前两处必须在数据库事务里，而计数若也放进去，就会让每次提问
 * 多一次对 sys_user 的 UPDATE。计数是高频、单点、可最终一致（短暂不准可以接受，
 * 过一会儿会自动对齐）的写入，用 Redis 的 INCR（内存原子自增）承接最合适：
 * 数据库只保留基准值，由定时任务批量回刷。
 *
 * <p>一致性模型是「数据库基准 + Redis 增量」：
 * <ul>
 *     <li>真实用量 = sys_user.ai_query_count（基准）+ Redis 里的增量；</li>
 *     <li>写：INCR，完全不碰数据库，因此没有热点行；</li>
 *     <li>读：两者相加（见 merge），所以预检不依赖回刷是否已经发生；</li>
 *     <li>落：定时任务用 GETDEL 取走增量，再 UPDATE ... + n 累加进基准值。</li>
 * </ul>
 *
 * <p>为什么注入 StringRedisTemplate 而不是项目里那个 RedisTemplate&lt;String, Object&gt;：
 * 后者给 value 配了 JSON 序列化器，写进去的是 {"@class":...,"value":"1"} 这种文本，
 * INCR 会直接报「value is not an integer」，读回来的也不是数字。
 * 计数需要的是裸字符串数字，只有 StringRedisTemplate 满足。
 *
 * <p>降级纪律：Redis 不可用时，写退化为「直接 UPDATE 数据库」、读退化为「只认数据库值」。
 * 计数可以短暂不准，但功能永远可用——这是「可用性优先于一致性」的明确取舍。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiQueryCountManager {

    /**
     * Redis 不可用时的降级自增 SQL（原子：交给数据库做「列 = 列 + 1」）
     *
     * <p>为什么写成 {@code 列 = 列 + 1} 而不是「先查出来再写回」：后者在两个请求
     * 并发时会互相覆盖，计数偏小——而计数偏小等于白送额度。
     * 交给数据库在同一条 UPDATE 里做加法，天然是原子的。
     *
     * <p>硬编码列名是安全的：它是常量，不来自用户输入。
     */
    private static final String SQL_INCREASE_QUERY_COUNT = "ai_query_count = ai_query_count + 1";

    /**
     * 计数必须用 StringRedisTemplate：value 是裸字符串，INCR / GETDEL 才能工作
     *
     * <p>用项目默认的 JSON RedisTemplate 会让 INCR 报「value is not an integer」，
     * 原因见类注释。
     */
    private final StringRedisTemplate stringRedisTemplate;

    private final SysUserMapper sysUserMapper;

    /**
     * 计数 +1（由提问成功后的落库逻辑在事务外调用）
     *
     * <p>为什么这样设计：INCR 本身是原子的——并发 20 个请求同时 +1，结果一定是 20，
     * 不存在「读改写」的竞态，这正是它替代「查出来 +1 再更新」的理由。
     * 更关键的是它被刻意放在数据库事务之外：Redis 是外部系统，事务回滚撤不回
     * 已经发出的 INCR，所以一旦放进事务里，就会出现「回答没存成功、额度却被扣了」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拼出该用户的计数键，键格式统一由常量类提供，避免两处写得不一致；</li>
     *     <li>② 执行 INCR。Redis 正常时到此结束，不产生任何数据库写入；</li>
     *     <li>③ Redis 抛异常则降级为直写数据库：宁可多一次数据库写，也不能让计数丢掉；</li>
     *     <li>④ 数据库也失败时只记日志，不再向外抛——计数丢失不影响本次回答已经给出，
     *     不能让整个提问接口因此失败。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法吞掉所有异常，调用方因此不能靠它判断「扣额度是否成功」。
     * 这是刻意的：额度是防护性上限，不是计费依据，丢一两次不会造成安全问题；
     * 而让「计数失败」导致用户拿不到已经生成好的回答，才是真的损失。
     *
     * @param userId 登录用户 ID（必须已存在）；不存在的 ID 在降级路径上会更新 0 行，
     *               但不会抛异常，也不会补建用户
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
     * <p>为什么这样设计：配额预检与「我的信息」都必须用它，否则用户看到的数字会比实际少
     * ——增量还没回刷时尤其明显：提问一次后刷新页面，数字纹丝不动。
     * 若只看数据库，回刷前的那些提问都不算数，配额就成了摆设。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 数据库基准值允许为 null（字段没初始化），按 0 处理；</li>
     *     <li>② 加上 Redis 里的未落库增量。加法的顺序无所谓，但两项都必须取——
     *     只取一项都会得到偏小的值。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法读的是「当前值」，并发下两个请求可能同时读到同一个数字，
     * 于是都能通过预检、最终略微超出上限。这是刻意接受的：配额是防护性上限，
     * 为此加锁得不偿失。真正不能错的是自增本身必须原子。
     *
     * @param userId 用户 ID
     * @param dbValue 数据库里的基准值，可为 null（按 0 处理）
     * @return 真实用量；Redis 不可用时等于基准值（可能偏小，导致预检偏松）
     */
    public int merge(long userId, Integer dbValue) {
        int base = dbValue == null ? 0 : dbValue;
        return base + redisDelta(userId);
    }

    /**
     * 读 Redis 里的未落库增量
     *
     * <p>为什么这样设计：读取失败（Redis 不可用）时返回 0，即降级为「只认数据库值」。
     * 这会让预检偏松（可能略微超限），但不会让接口报错——与配额作为「防护性上限」
     * 的定位一致：预检本来就容忍并发窗口内的轻微超限。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按统一键格式取值；key 不存在说明这个用户还没有未落库增量，直接返回 0；</li>
     *     <li>② 把字符串转成整数。先 trim 是因为值可能被人手工写入过带空格的文本；</li>
     *     <li>③ 任何异常（连不上、值不是数字）都按 0 处理，只记一条 warn。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里不能用 Integer.valueOf 的默认行为而不接住异常——
     * 一个被写坏的 key 会让每次提问都抛 NumberFormatException，
     * 表现成「某个用户永远问不了问题」，而且换个人就正常，极难排查。
     *
     * @param userId 用户 ID
     * @return 增量；无 key 或 Redis 不可用时为 0，不会是负数
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
     * <p>为什么这样设计：顺序必须是「先取走（GETDEL）再写数据库」。
     * GETDEL 之后 Redis 已清零，此时即使 UPDATE 失败，也不会出现「同一份增量被写两次」——
     * 重复计数比短暂少计数严重得多，后者只是预检偏松，前者会让用户白白损失额度。
     * 而 UPDATE 失败时会把增量 INCRBY 还回 Redis，下一轮重试，
     * 因此「失败可容忍但不丢计数」。
     * 多实例安全：GETDEL 是原子的，只有一个实例能取到值，其它实例取到 null 直接跳过。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 SCAN 按前缀找出所有待回刷的 key。没有 key 就直接返回，
     *     避免空转（这是绝大多数轮次的情况）；</li>
     *     <li>② 逐个 key 解析出用户 ID，解析不出来的跳过并告警；</li>
     *     <li>③ GETDEL 原子取走并清零。取到的值小于等于 0 时跳过——
     *     写一个 +0 到数据库没有意义；</li>
     *     <li>④ 用 {@code 列 = 列 + delta} 累加进基准值。这里必须用 setSql 做原子加法，
     *     不能「查出来 +delta 再 updateById」，否则并发回刷会互相覆盖；</li>
     *     <li>⑤ 写失败则把增量还回 Redis，等下一轮重试；</li>
     *     <li>⑥ 全部处理完打一条汇总日志（没有成功项时不打，避免刷屏）。</li>
     * </ol>
     *
     * <p>容易踩的坑：用 fixedDelayString 而不是 fixedDelay，是为了让周期可配置——
     * 测试里可以把它调到 1 小时，避免定时任务在用例执行途中把待断言的增量清掉。
     * 另外单个 key 的异常绝不能向上抛：一个人的计数坏掉不该拖累整轮回刷。
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
     * 原子取出并删除（Redis GETDEL）：避免「读到值、但删除前崩溃」导致的重复计数
     *
     * <p>为什么这样设计：若拆成「先 GET 再 DEL」，两步之间进程崩溃就会出现
     * 「值已读到、但没删掉」，下一轮又把它加一遍——用户的额度凭空少了一截。
     * GETDEL 把这两步合成一个原子操作，没有中间状态。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 执行 GETDEL。单个 key 读失败（Redis 抖动）时返回 null 并告警，
     *     绝不中断整轮回刷；</li>
     *     <li>② key 不存在返回 null，调用方直接跳过；</li>
     *     <li>③ 把值转成整数。脏值（被人手工写坏、序列化异常）丢弃并告警——
     *     此时 key 已经被删掉，脏值不会在下轮被反复处理。</li>
     * </ol>
     *
     * <p>容易踩的坑：脏值分支必须返回 null 而不是抛异常。它一旦抛出，
     * 整轮回刷就会中断，排在后面的用户全部得不到回刷——一个人的脏数据
     * 拖累所有人，是本方法最需要防的事。
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
     * <p>为什么这样设计：数据库写失败时增量已经从 Redis 取走了，若不还回去，
     * 这部分计数就彻底消失。用 INCRBY（increment 带增量）而不是 SET，
     * 是因为在「取走」到「回补」之间可能又有新的提问把同一个 key 加了起来，
     * SET 会把那些新计数直接覆盖掉，而 INCRBY 是累加，不会丢。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 increment(key, delta) 把增量加回去；</li>
     *     <li>② 回补本身也失败时只记 error：计数少一点不会造成安全问题，
     *     但必须留痕，否则「账对不上」时无从追查。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这里顺手把 key 的过期时间重置。计数键不该有过期时间——
     * 过期会让未回刷的增量凭空消失；而回补时若加 TTL，又会引入
     * 「同一份增量在过期前后被算两次」的诡异时序。
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
     * <p>为什么这样设计：用 SCAN 而不是 KEYS。KEYS 会一次性遍历整个键空间，
     * 期间阻塞 Redis 的单线程，key 一多就会拖垮其它所有命令；
     * SCAN 是游标式的增量遍历，代价只是多几次往返。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 组装扫描参数：匹配模式与每批条数都来自常量类，
     *     避免「这里写前缀、那里写通配」的不一致；</li>
     *     <li>② 在 RedisCallback 里拿到底层连接执行扫描。用底层连接是因为
     *     模板没有直接暴露 SCAN 的游标接口；</li>
     *     <li>③ 游标必须用 try-with-resources 关闭，否则连接不会归还，
     *     跑几轮之后连接池就耗尽了；</li>
     *     <li>④ 把字节数组按 UTF-8 转成字符串。不能依赖平台默认编码——
     *     换个环境就可能解析出乱码 key，导致解析用户 ID 失败、静默漏刷。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值可能是 null（模板在某些失败场景下会返回 null），
     * 必须兜成空列表，否则调用方遍历时会空指针。
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
     * <p>为什么这样设计：回刷时数据库 UPDATE 需要的是用户 ID，而 Redis 里只有
     * 「前缀 + ID」拼出来的 key。前缀长度由常量类提供，这里不能硬编码字面量——
     * 前缀一改，硬编码的长度就会截错位置，解析出完全错误的 ID，
     * 然后把别人的额度加错人，而且不会报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按前缀长度截掉前半段，剩下的就是 ID 文本；</li>
     *     <li>② 转成 Long。解析失败返回 null，由调用方跳过并告警。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这里抛异常。一个格式不对的 key 不该中断整轮回刷，
     * 它多半是人为误写或历史遗留，跳过即可。
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
}
