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
 * 一套 Lua、一套降级纪律、一套键规范，按业务前缀区分，不出现第二个限流器各自为政。
 * 双窗口：分钟窗口挡脚本连续刷，日窗口挡「每分钟 4 次、持续一整天」的慢速占用。
 *
 * 用 Lua 而不是「INCR 后判断 ==1 再 EXPIRE」：两步写法在进程崩溃于两步之间时会留下没有 TTL 的 key，
 * 那个 IP 被永久限流且没有任何报错。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitManager {

    /**
     * 原子自增 + 首次设置过期时间（Lua）
     *
     * KEYS[1] = 限流键，ARGV[1] = TTL 秒数，返回自增后的值
     */
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

    /**
     * 分钟窗口键的 TTL（秒）：120 = 窗口 60s + 余量 60s
     *
     * 余量不能省：key 是在窗口内任意时刻创建的，TTL 正好 60s 时，10:00:01 创建的 key
     * 会在 10:01:01 过期，而它代表的 10:00 窗口要到 10:01:00 才结束。
     */
    private static final long MINUTE_KEY_TTL_SECONDS = 120L;

    /**
     * 日窗口键 TTL 的额外余量（秒）：在「距次日零点」之上再加 60s，
     * 避免跨日瞬间因时钟误差出现「新窗口还没建、旧窗口已过期」的空档
     */
    private static final long DAY_KEY_TTL_MARGIN_SECONDS = 60L;

    /** 一天的秒数 */
    private static final long SECONDS_PER_DAY = 24 * 60 * 60L;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 做一次限流检查：分钟窗口与日窗口任意一个超限就拒绝
     *
     * 必须调用在业务逻辑的最外层——查库、加密、调用外部服务之前。放在查库之后，
     * 攻击者依然能靠高频请求把数据库压垮，限流就只剩「事后告知」的作用。
     *
     * 坑：本方法对每次调用都计数，包括因密码错误而失败的尝试——只统计成功请求，爆破就不受限了。
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
            // 拿不到 IP（例如 MockMvc 没设置 remoteAddr）：放行。
            // 宁可漏限也不能误杀——误杀会让整个接口不可用
            return;
        }

        checkWindow(minuteKey(keyPrefix, ip), ip, minuteLimit, MINUTE_KEY_TTL_SECONDS, "每分钟", action);
        checkWindow(dayKey(keyPrefix, ip), ip, dayLimit, dayTtlSeconds(), "每天", action);
    }

    /**
     * 检查单个窗口是否超限
     *
     * 两个窗口的逻辑完全一样，抽出来是为了让「超限抛 A0501」「Redis 故障放行」只写一遍，
     * 不出现「分钟窗口降级了、日窗口忘了降级」的半拉子状态。坑：判断必须写成
     * {@code current > limit} 而不是 {@code >=}，后者会让实际可用次数少一次。
     *
     * @param key 已拼好的窗口键（含时间片）
     * @param ip 客户端 IP，仅用于日志
     * @param limit 上限，null 或 &lt;= 0 表示不限制
     * @param ttl 该键的过期秒数
     * @param window 窗口名，只用于提示文案与日志
     * @param action 动作名，只用于提示文案
     * @throws BusinessException 当前次数超过上限时抛出 A0501
     */
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
            // 被拒绝的这次也计数（INCR 先于判断）：攻击者的每一次尝试都在消耗他的配额，
            // 不存在「疯狂重试反正不计数」的空子。
            // warn 级日志是刻意留的审计线索：限流触发意味着可能正在被爆破，
            // 运维可以按它发现攻击，而不是只看到用户侧一句「请稍后再试」。
            log.warn("IP 限流触发，拒绝请求 | ip={} | key={} | 当前第 {} 次 | 上限 {} 次/{}",
                    ip, key, current, limit, window);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    action + "太频繁了（" + window + "最多 " + limit + " 次），请稍后再试");
        }
    }

    /**
     * 拼出分钟窗口的键
     *
     * 键里带「年月日时分」而不是只带 IP，每分钟换一个键，上一个自然过期，
     * 不需要任何定时任务去清零。代价是窗口边界处的毛刺，对防爆破完全够用。
     *
     * 坑：中间的分隔符不能省，否则 {@code 1.1.1.1} 与 {@code 1.1.1.10} 可能撞键。
     *
     * @param keyPrefix 业务前缀，调用方保证非空
     * @param ip 客户端 IP，调用方保证非空白
     * @return 形如 {@code studio:auth:rate:login:ip:1.2.3.4:min:202601011230} 的键
     */
    private String minuteKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":min:" + LocalDateTime.now().format(MINUTE_FORMATTER);
    }

    /**
     * 拼出日窗口的键（与分钟键同一思路：把日期写进键里，跨天自动换键）
     *
     * 日窗口存在的理由是分钟窗口结构性看不见慢速爆破：每分钟 4 次在分钟维度上永远合规，
     * 一天累积下来却是数千次尝试。
     *
     * @param keyPrefix 业务前缀，调用方保证非空
     * @param ip 客户端 IP，调用方保证非空白
     * @return 形如 {@code studio:auth:rate:login:ip:1.2.3.4:day:20260101} 的键
     */
    private String dayKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":day:" + LocalDateTime.now().format(DAY_FORMATTER);
    }

    /**
     * 算出日窗口键的 TTL：距次日零点的秒数再加 60 秒余量
     *
     * 刻意不用固定的 24 小时：早上 9 点创建的键会活到第二天早上 9 点，
     * 覆盖新一天窗口的开端，用户会觉得「今天的第一次请求就撞上了昨天的配额」。
     *
     * 坑：用的是 {@link LocalTime#now()}，即服务器本地时区，与业务时区无关。
     * 部署在 UTC 机器上时「日」的切换点在 UTC 零点，排查时别拿北京时间去对日志。
     *
     * @return 距次日零点的秒数再加 60 秒余量，恒为正数
     */
    private long dayTtlSeconds() {
        long secondsToMidnight = SECONDS_PER_DAY - LocalTime.now().toSecondOfDay();
        return secondsToMidnight + DAY_KEY_TTL_MARGIN_SECONDS;
    }
}
