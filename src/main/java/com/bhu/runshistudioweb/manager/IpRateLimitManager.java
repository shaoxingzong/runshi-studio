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
 * 为什么有这一个类：此前只有游客 AI 提问有 IP 限流（ AiRateLimitManager），
 * 登录与注册裸奔——无验证码、无失败锁定、无频率限制。现在把两者收敛到同一套实现：
 * 一套 Lua、一套降级纪律、一套键规范，按业务前缀区分，不出现第二个限流器各自为政。
 *
 * 为什么是「双窗口」而不是单个窗口：
 * 单窗口只能表达一种语义。分钟窗口挡的是脚本连续刷，
 * 日窗口挡的是慢速低频但长期占用——后者靠分钟窗口永远发现不了
 * （每分钟 4 次、持续一整天，也是近 5000 次调用）。两者缺一都有明显漏洞。
 *
 * 为什么用 Lua 而不是「INCR 后判断 ==1 再 EXPIRE」（本类最重要的选择）：
 * 两步写法在进程崩溃于两步之间时会留下一个没有 TTL 的 key——
 * 那个 IP 会被永久限流，而且没有任何报错，只能靠运维手动删 key 恢复。
 * 这比「偶尔多放几个请求」严重得多。Lua 让 INCR 与 EXPIRE 在 Redis 侧原子完成，
 * 不存在中间状态。代价是要维护一段脚本，值得。
 *
 * 降级纪律：Redis 不可用时放行（可用性优先），只告警。
 * 限流是防护手段，不能反过来变成故障源——为了防刷把自己的服务搞挂，是本末倒置。
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
     * KEYS[1] = 限流键  ARGV[1] = TTL 秒数
     * 返回自增后的值
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
     * 余量不能省：key 是在窗口内任意时刻创建的。
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
     * 做一次限流检查：分钟窗口与日窗口任意一个超限就拒绝
     *
     * <p>为什么这样设计：本方法必须被调用在业务逻辑的<b>最外层</b>——
     * 查库、加密、调用外部服务之前。超限的请求不该产生任何后续开销，
     * 这正是限流存在的意义。如果放在查库之后，攻击者依然能靠高频请求把数据库压垮，
     * 限流就只剩「事后告知」的作用。
     *
     * <p>为什么是双窗口：单窗口只能表达一种语义。分钟窗口挡脚本连续刷，
     * 日窗口挡「每分钟 4 次、持续一整天」这种慢速长期占用——
     * 后者在分钟窗口看来完全合规，一天下来却接近 5000 次。两者缺一都有明显漏洞。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>IP 为空直接放行。拿不到 IP（例如 MockMvc 没设置 remoteAddr）时，
     *     宁可漏限也不能误杀——误杀会让整个接口对所有人不可用，比少挡几个请求严重得多；</li>
     *     <li>先查分钟窗口。放在日窗口之前，是因为它更容易先触发：
     *     一旦这里就拒绝了，后面那次 Redis 往返完全可以省掉；</li>
     *     <li>再查日窗口。走到这一步说明分钟窗口还没满，但日累计可能已经到顶。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法<b>不区分「这是第几次」与「这是不是合法请求」</b>，
     * 它对每一次调用都计数，包括那些最终因为密码错误而失败的尝试。
     * 这是刻意的——如果只对成功请求计数，爆破就完全不受限了。
     * 代价是共享出口 IP（例如同一个 NAT 下的整个工作室）会共用配额，
     * 因此阈值不能设得太紧。
     *
     * @param ip 客户端 IP，可为 null 或空白；为空时直接放行
     * @param keyPrefix 业务键前缀（如 {@code studio:auth:rate:login:ip:}），
     *                  决定这份计数按哪个业务算。登录与注册必须用不同前缀，
     *                  否则登录被限流会连带堵死注册
     * @param minuteLimit 分钟窗口上限，null 或 &lt;= 0 表示关闭该窗口
     * @param dayLimit 日窗口上限，null 或 &lt;= 0 表示关闭该窗口
     * @param action 动作名，只用于拼接提示文案（如「登录」），不影响计数
     * @throws BusinessException 超过任一窗口上限时抛出，错误码 A0501，
     *                           提示里带上窗口与上限，便于用户判断要等多久
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
     * <p>为什么这样设计：两个窗口的判定逻辑完全一样，差别只在键、上限与 TTL。
     * 抽成一个方法后，「超限就抛 A0501」「Redis 故障就放行」这两条纪律只需要写一遍，
     * 不会出现「分钟窗口降级了、日窗口忘了降级」这种半拉子状态。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>上限为 null 或 &lt;= 0 时直接返回。这是「关掉某个窗口」的开关，
     *     比在配置里写一个很大的数字更明确；</li>
     *     <li>执行自增脚本拿到当前次数。Redis 不可用时告警并放行——
     *     限流是防护手段，不能反过来变成故障源；</li>
     *     <li>判断是否超过上限。<b>注意判断发生在自增之后</b>：
     *     被拒绝的这一次同样已经计数了。这样攻击者的每一次尝试都在消耗自己的配额，
     *     不存在「疯狂重试反正不计数」的空子；</li>
     *     <li>超限时打 warn 并抛异常。warn 是刻意留的审计线索：
     *     限流触发意味着可能正在被爆破，运维能据此发现攻击，
     *     而不是只看到用户侧一句「请稍后再试」。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步的 {@code current > limit} 不能写成
     * {@code current >= limit}。前者表示「允许恰好 limit 次，第 limit + 1 次被拒」，
     * 后者会让实际可用次数比配置值少一次——配置写 10 却只能成功 9 次，
     * 这种差一错误在排查时极难发现。
     *
     * @param key 已经拼好的窗口键（含时间片），由调用方生成
     * @param ip 客户端 IP，仅用于日志；直接传入比从键里反解更可靠
     * @param limit 上限，null 或 &lt;= 0 表示不限制
     * @param ttl 该键的过期秒数
     * @param window 窗口名（「每分钟」/「每天」），只用于提示文案与日志
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
     * <p>为什么这样设计：键里带上「年月日时分」而不是只带 IP，是为了让窗口
     * <b>自然滑动</b>——每分钟换一个键，上一个键自然过期，不需要任何定时任务去清零。
     * 这是固定窗口计数最省事的实现方式，代价是窗口边界处的毛刺
     * （例如 10:00:59 与 10:01:00 各打满一次，跨边界的一秒内实际放行了双倍请求），
     * 对防爆破这个场景完全够用。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>用固定前缀 + IP + {@code :min:} + 当前分钟拼成键。
     *     中间那一段分隔符不能省，否则 {@code 1.1.1.1} 与 {@code 1.1.1.10}
     *     在某些拼接方式下可能撞键；</li>
     *     <li>时间片取服务器本地时间。这里不显式指定时区：窗口只要求「同一台机器上一致」，
     *     不需要与业务时区（东八区）对齐——跨时区部署时各节点窗口错开，反而分散了计数。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法的输出必须与 {@link #MINUTE_KEY_TTL_SECONDS} 配套理解。
     * 键按分钟切换，但 TTL 是 120 秒而不是 60 秒——余量用来覆盖「窗口末尾创建的键」
     * 活过窗口结束时刻的那一小段时间。
     *
     * @param keyPrefix 业务前缀（如 {@code studio:auth:rate:login:ip:}），调用方保证非空
     * @param ip 客户端 IP，调用方保证非空白
     * @return 形如 {@code studio:auth:rate:login:ip:1.2.3.4:min:202601011230} 的键
     */
    private String minuteKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":min:" + LocalDateTime.now().format(MINUTE_FORMATTER);
    }

    /**
     * 拼出日窗口的键
     *
     * <p>为什么这样设计：与分钟键同一个思路——把日期写进键里，跨天自动换键，
     * 不需要任何清零动作。日窗口存在的理由是分钟窗口<b>结构性看不见</b>慢速爆破：
     * 每分钟 4 次在分钟维度上永远合规，一天累积下来却是数千次尝试。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>用固定前缀 + IP + {@code :day:} + 当前日期拼成键；</li>
     *     <li>日期格式只到「天」，因此同一自然日内的所有请求都落在同一个键上。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法的键切换点取决于<b>服务器本地时间</b>的零点，
     * 而 TTL 由 {@link #dayTtlSeconds()} 单独计算。两者必须保持同一时间基准，
     * 否则会出现「键已经换了、旧键还没过期」的错位——虽然不影响正确性（旧键不再被读写），
     * 但会让 Redis 里多留一批无用键。
     *
     * @param keyPrefix 业务前缀，调用方保证非空
     * @param ip 客户端 IP，调用方保证非空白
     * @return 形如 {@code studio:auth:rate:login:ip:1.2.3.4:day:20260101} 的键
     */
    private String dayKey(String keyPrefix, String ip) {
        return keyPrefix + ip + ":day:" + LocalDateTime.now().format(DAY_FORMATTER);
    }

    /**
     * 算出日窗口键应该活多久
     *
     * <p>为什么这样设计：这里刻意<b>不用固定的 24 小时</b>。若 TTL 固定为 24 小时，
     * 早上 9 点创建的键会活到第二天早上 9 点，覆盖了新一天窗口的开端——
     * 结果是「今天的第一次请求就撞上了昨天的配额」，用户会觉得莫名其妙。
     * 改成「距次日零点 + 余量」后，键的生命周期与自然日对齐。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>用一天的总秒数减去「今天已过去的秒数」，得到距次日零点的秒数；</li>
     *     <li>再加 {@link #DAY_KEY_TTL_MARGIN_SECONDS} 的余量。
     *     余量不能省：跨日那一瞬间若新旧窗口之间出现空档，
     *     攻击者只要卡在零点前后就能拿到一整份额外的配额；</li>
     *     <li>返回总秒数作为 TTL。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法用的是 {@link LocalTime#now()}，即<b>服务器本地时区</b>，
     * 与业务时区（东八区）无关。部署在 UTC 机器上时，「日」的切换点在 UTC 零点。
     * 这对限流没有影响（只是配额重置时刻不同），但排查时不要拿北京时间去对日志，
     * 否则会怀疑「为什么配额在下午 4 点重置了」。
     *
     * @return 距次日零点的秒数再加 60 秒余量，恒为正数
     */
    private long dayTtlSeconds() {
        long secondsToMidnight = SECONDS_PER_DAY - LocalTime.now().toSecondOfDay();
        return secondsToMidnight + DAY_KEY_TTL_MARGIN_SECONDS;
    }
}
