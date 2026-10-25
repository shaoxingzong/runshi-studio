package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AuthLockoutProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 登录失败锁定（账号级，累积计数 + 固定窗口）
 *
 * 为什么是独立的一个类，不并进 IpRateLimitManager：两者模型不同。
 * IP 限流是「固定窗口内的频率控制」（INCR 到上限即拒，窗口过期计数归零）；
 * 账号锁定是「连续失败累积到阈值后锁一段时间，登录成功立即清零」。
 * 把两种语义塞进一个类，方法签名与注释很快就会互相腐化，各自独立才说得清。
 *
 * 与 IP 限流的分工：IP 限流挡「单 IP 高频试多个账号」，但挡不住
 * 分布式慢速爆破（海量 IP 各试几次，频次远低于任何单 IP 上限）。
 * 本类以「同一账号连续失败 N 次」触发，补的正是这个盲区（见 AuthLockoutProperties）。
 *
 * 三个方法的调用时机（顺序不能乱）：
 * - assertNotLocked：登录最开始——被锁的请求不查库、不比对密码；
 * - recordFailure：仅在「账号存在但密码错误」时——账号不存在无可锁对象，
 * 且对不存在的账号锁定会让锁定提示变成账号存在性探测器；
 * - clear：登录成功时——合法用户不该被自己几分钟前的失误一直挡在门外。
 *
 * 降级纪律与 IP 限流完全一致：Redis 不可用时全部放行（只告警）。
 * 锁定是防护手段，不能反过来变成故障源——为了防刷把自己的登录功能搞挂，是本末倒置。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthLockManager {

    /**
     * 失败计数键前缀：studio:auth:lock:{account}
     *
     * 带上账号名而不是用户 ID：登录时手里只有账号（ID 要查库才知道），
     * 且账号是攻击者直接接触的标识，按它计数不需要先查库。
     */
    private static final String KEY_PREFIX = "studio:auth:lock:";

    /**
     * 原子自增 + 首次设置过期时间（Lua）
     *
     * 与 IpRateLimitManager 同一段脚本、同一个理由：两步写法
     * （先 INCR、判断等于 1 再 EXPIRE）在进程崩溃于两步之间时会留下没有 TTL 的 key，
     * 那个账号会被永久锁定且没有任何报错。必须让 Redis 侧原子完成。
     */
    private static final String LUA_INCR_WITH_EXPIRE =
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current";

    private final DefaultRedisScript<Long> incrScript =
            new DefaultRedisScript<>(LUA_INCR_WITH_EXPIRE, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final AuthLockoutProperties authLockoutProperties;

    /**
     * 登录前先看一眼这个账号是不是已经被锁了，锁着就直接拒绝
     *
     * <p>为什么这样设计：本方法必须是登录流程里<b>最早</b>的一道闸，早于查库与密码比对。
     * 原因不是「快」，而是「被锁的请求根本不该消耗任何资源」——一个正在被爆破的账号，
     * 每分钟可能有成百上千次尝试，如果每次都放它去查库、去跑一次 BCrypt 比对，
     * 防护措施反而成了压垮数据库与 CPU 的那只手。
     *
     * <p>为什么锁定用「固定窗口」而不是「每次失败续期」：续期方案下，
     * 攻击者只要每隔十几分钟试一次，就能让目标账号永远处于锁定状态——
     * 这等于把防护工具改造成了拒绝服务工具。固定窗口最坏也会在窗口结束后自动恢复。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>空账号或总开关关闭时直接放行。账号为空说明调用方还没拿到可判定的对象，
     *     这里拒绝只会变成一个莫名其妙的 A0501；</li>
     *     <li>读 Redis 里的失败计数。<b>读失败不抛异常</b>，只告警并放行——
     *     锁定是防护手段，不能反过来成为故障源。Redis 挂了就让登录继续可用，
     *     此时少的是防护力，不是可用性；</li>
     *     <li>计数达到阈值就抛 A0501。这里用 {@code >=} 而不是 {@code ==}：
     *     并发下计数可能一次跳好几格（多个请求同时 INCR），用等号会漏掉「已经超过」的情况。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法<b>只读不写</b>，它不会因为「检查到已锁定」而延长锁定时间。
     * 若把它写成「每次检查都续期」，攻击者随便发几个请求就能把账号永久锁死。
     * 另外，抛出的提示里带上了锁定时长，这是刻意给正常用户的指引——
     * 对攻击者而言，这条信息没有任何价值，因为他本来就知道自己在爆破。
     *
     * @param account 登录账号，可为 null 或空白；为空时直接放行（没有可锁定的对象）
     * @throws BusinessException 失败次数已达阈值时抛出，错误码 A0501，提示中带剩余锁定时长
     */
    public void assertNotLocked(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            // 空账号没有可锁对象；开关关闭时整体跳过（测试环境靠它避免用例互相干扰）
            return;
        }

        long failures;
        try {
            String value = stringRedisTemplate.opsForValue().get(key(account));
            failures = value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            // Redis 不可用：放行 + 告警（与 IP 限流同一条降级纪律）
            log.warn("账号锁定状态读取失败，本次放行 | account={}", account, e);
            return;
        }

        if (failures >= authLockoutProperties.getMaxFailures()) {
            // warn 级日志是刻意留的审计线索：账号被锁定意味着正在被针对性爆破，
            // 运维可以按它发现攻击，而不是只看到用户侧一句「请稍后再试」
            log.warn("账号登录锁定触发，拒绝请求 | account={} | 已失败 {} 次 | 阈值 {}",
                    account, failures, authLockoutProperties.getMaxFailures());
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    "登录失败次数过多，账号已锁定，请 " + authLockoutProperties.getLockMinutes() + " 分钟后再试");
        }
    }

    /**
     * 记录一次登录失败（只应在「账号存在但密码错误」时调用）
     *
     * <p>为什么这样设计：这条限制是本类最容易被忽略、也最关键的调用约定。
     * 若对「账号不存在」也计数，会同时坏两件事：<b>其一</b>，攻击者可以拿一批
     * 猜出来的账号名去把别人的账号刷成锁定状态；<b>其二</b>，「已锁定」这个提示
     * 本身就变成了账号存在性探测器——能锁定说明账号存在，不能锁定说明不存在。
     * 因此调用方必须先用「账号是否存在」把这两种情况分开。
     *
     * <p>为什么用 Lua 而不是「INCR 之后判断等于 1 再 EXPIRE」：两步写法如果在
     * 进程崩溃于两步之间，会留下一个<b>没有 TTL 的 key</b>，那个账号就永久锁定、
     * 而且没有任何报错，只能靠人工删 key 恢复。Lua 让两步在 Redis 侧原子完成，
     * 不存在中间状态。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>空账号或开关关闭时直接返回；</li>
     *     <li>算出 TTL（分钟换算成秒）。这里只用来做参数，不是「续期」的依据；</li>
     *     <li>执行自增脚本。注意脚本只在 <b>计数等于 1</b>（也就是窗口内第一次失败）
     *     时才设置过期时间，后续失败不续期——这正是「固定窗口」的实现方式；</li>
     *     <li>写失败只告警，不向上抛。本次失败仍会被「账号或密码错误」挡下，
     *     丢的只是累计进度，可用性优先。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回 void，调用方无法知道「这次是否刚好达到阈值」。
     * 这是刻意的——是否拒绝登录由下一次 {@link #assertNotLocked(String)} 决定，
     * 本次请求本来就该失败（密码已经错了），不需要额外的分支。
     *
     * @param account 登录账号，可为 null 或空白；为空时直接返回，不做任何计数
     */
    public void recordFailure(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            return;
        }
        long ttlSeconds = (long) authLockoutProperties.getLockMinutes() * 60;
        try {
            Long current = stringRedisTemplate.execute(
                    incrScript, List.of(key(account)), String.valueOf(ttlSeconds));
            // info 而非 warn：单次失败是正常业务事件，真正的告警在「锁定被触发」那一刻
            log.info("登录失败已计数 | account={} | 窗口内第 {} 次 | 上限 {}",
                    account, current, authLockoutProperties.getMaxFailures());
        } catch (Exception e) {
            // 计数丢失（Redis 故障）不阻塞登录：本次失败仍会被「账号或密码错误」挡下，
            // 丢的只是累计进度，可用性优先
            log.warn("登录失败计数写入失败，本次仅放行计数 | account={}", account, e);
        }
    }

    /**
     * 登录成功时把该账号的失败计数清零
     *
     * <p>为什么这样设计：计数表达的是「连续失败」，而不是「历史上失败过几次」。
     * 用户昨天输错两次、今天输对一次，就证明他持有正确凭据，昨天的账应当一笔勾销。
     * 若不清零，正常用户会在几天内被自己零星的失误慢慢推向锁定阈值——
     * 那是把防护做成了对用户的惩罚。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>空账号或开关关闭时直接返回；</li>
     *     <li>直接 DELETE 计数 key，而不是把它置 0。置 0 会留下一个空壳 key，
     *     下一次失败自增到 1 时脚本会重新设置 TTL，看着也没问题，
     *     但库里会堆积大量「值为 0、却在占内存」的键。删除最干净；</li>
     *     <li>删除失败只告警：凭据已经验证正确，不能因为清不了计数就不让用户登录。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法要放在「密码校验通过」之后调用。
     * 若放在校验之前，攻击者只要随便提交一次正确的格式就能清掉自己的失败计数，
     * 整个锁定机制就形同虚设。
     *
     * @param account 登录账号，可为 null 或空白；为空时直接返回
     */
    public void clear(String account) {
        if (StrUtil.isBlank(account) || Boolean.FALSE.equals(authLockoutProperties.getEnabled())) {
            return;
        }
        try {
            stringRedisTemplate.delete(key(account));
        } catch (Exception e) {
            // 清零失败不影响本次登录（凭据已验证正确），仅告警
            log.warn("登录成功计数清零失败（不影响本次登录）| account={}", account, e);
        }
    }

    /**
     * 拼出该账号的计数键
     *
     * <p>为什么这样设计：键里放<b>账号名</b>而不是用户 ID，因为登录时手里只有账号，
     * ID 得先查库才知道。若按 ID 计数，本方法就得先查一次库，
     * 「被锁的请求不碰数据库」这条设计前提立刻失效。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>用固定前缀拼接账号名。前缀里的 {@code studio:} 是项目统一的键命名空间，
     *     避免和同一 Redis 里其它应用的键混在一起。</li>
     * </ol>
     *
     * <p>容易踩的坑：账号名大小写敏感，{@code Admin} 与 {@code admin} 会得到两个不同的键。
     * 这与登录查库的匹配方式保持一致（数据库默认排序规则下账号比较同样区分大小写），
     * 因此不会出现「换个大小写就能绕过锁定」的漏洞——那种情况本来也登不进去。
     *
     * @param account 登录账号，调用方保证非空白
     * @return 形如 {@code studio:auth:lock:zhangsan} 的 Redis 键
     */
    private String key(String account) {
        return KEY_PREFIX + account;
    }
}
