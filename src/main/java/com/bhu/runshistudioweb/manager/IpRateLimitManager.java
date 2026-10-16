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
 * author: shaoshing
 *
 * <p><b>为什么有这一个类</b>：此前只有游客 AI 提问有 IP 限流（{@code AiRateLimitManager}），
 * 登录与注册裸奔——无验证码、无失败锁定、无频率限制。现在把两者收敛到同一套实现：
 * 一套 Lua、一套降级纪律、一套键规范，按业务前缀区分，不出现第二个限流器各自为政。
 *
 * <p><b>为什么是「双窗口」而不是单个窗口</b>：
 * 单窗口只能表达一种语义。分钟窗口挡的是<b>脚本连续刷</b>，
 * 日窗口挡的是<b>慢速低频但长期占用</b>——后者靠分钟窗口永远发现不了
 * （每分钟 4 次、持续一整天，也是近 5000 次调用）。两者缺一都有明显漏洞。
 *
 * <p><b>为什么用 Lua 而不是「INCR 后判断 ==1 再 EXPIRE」</b>（本类最重要的选择）：
 * 两步写法在<b>进程崩溃于两步之间</b>时会留下一个<b>没有 TTL 的 key</b>——
 * 那个 IP 会被<b>永久限流</b>，而且没有任何报错，只能靠运维手动删 key 恢复。
 * 这比「偶尔多放几个请求」严重得多。Lua 让 INCR 与 EXPIRE 在 Redis 侧<b>原子完成</b>，
 * 不存在中间状态。代价是要维护一段脚本，值得。
 *
 * <p><b>降级纪律</b>：Redis 不可用时<b>放行</b>（可用性优先），只告警。
 * 限流是防护手段，不能反过来变成故障源——为了防刷把自己的服务搞挂，是本末倒置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitManager {

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

    /** 分钟窗口键的时间格式（yyyyMMddHHmm，窗口 = 自然分钟） */
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 日窗口键的时间格式（yyyyMMdd，窗口 = 自然日） */
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 分钟窗口键的 TTL（秒）：120 = 窗口 60s + 余量 60s
     *
     * <p><b>余量不能省</b>：key 是在窗口内<b>任意时刻</b>创建的。
     * 若 TTL 正好等于 60s，一个 10:00:01 创建的 key 会在 10:01:01 过期，
     * 而它代表的 10:00 窗口要到 10:01:00 才结束——末尾那一分钟的请求会失去限流保护。
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
     * 限流检查：任一窗口超限即拒绝
     *
     * <p><b>调用位置必须在最外层</b>（查库、加密、调用外部服务之前）：
     * 超限的请求不该产生任何后续开销——防爆破的意义正在于此。
     *
     * @param ip          客户端 IP（为 null 或空时直接放行：拿不到 IP 不能变成「拒绝所有人」）
     * @param keyPrefix   业务键前缀（如 {@code studio:auth:rate:login:ip:}），决定计数按哪个业务算
     * @param minuteLimit 分钟窗口上限（null 或 {@code <= 0} 表示关闭该窗口）
     * @param dayLimit    日窗口上限（null 或 {@code <= 0} 表示关闭该窗口）
     * @param action      动作名，用于提示文案（如「登录」「注册」「提问」）
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
     * 检查单个窗口
     *
     * @param key     窗口键
     * @param ip      客户端 IP（仅用于日志，直接传入比从键里反推更可靠）
     * @param limit   上限（null 或 {@code <= 0} 表示不限制）
     * @param ttl     TTL 秒数
     * @param window  文案用的窗口名（「每分钟」/「每天」）
     * @param action  文案用的动作名
     */
    private void checkWindow(String key, String ip, Integer limit, long ttl, String window, String action) {
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
     * 分钟窗口键：{@code {keyPrefix}{ip}:min:{yyyyMMddHHmm}}
     *
     * @param keyPrefix 业务前缀
     * @param ip        客户端 IP
     */
    private String minuteKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":min:" + LocalDateTime.now().format(MINUTE_FORMATTER);
    }

    /**
     * 日窗口键：{@code {keyPrefix}{ip}:day:{yyyyMMdd}}
     *
     * @param keyPrefix 业务前缀
     * @param ip        客户端 IP
     */
    private String dayKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":day:" + LocalDateTime.now().format(DAY_FORMATTER);
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
        long secondsToMidnight = SECONDS_PER_DAY - LocalTime.now().toSecondOfDay();
        return secondsToMidnight + DAY_KEY_TTL_MARGIN_SECONDS;
    }
}
