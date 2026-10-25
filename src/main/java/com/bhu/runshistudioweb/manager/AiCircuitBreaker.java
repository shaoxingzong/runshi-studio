package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 服务熔断器（进程内，三态状态机）
 *
 * <p>解决什么问题：AI 服务出现持续性故障时（api-key 被冻结、欠费、服务商整体宕机），
 * 每一次用户提问都会真实打一次外部调用——同步接口最坏等 60 秒超时，
 * 带 RAG 的提问还要再打一次 Embedding，日志被同样的堆栈刷爆，
 * 而用户除了反复看到「暂时不可用」什么也做不了。
 * 熔断的思路是：承认故障存在，快速失败，定期试探，把最坏等待从分钟级降到毫秒级。
 *
 * <p>三态怎么流转（本类所有方法都在维护这张图）：
 * <ul>
 *     <li>CLOSED（闭合）：正常放行，连续失败累计计数。成功一次即清零，
 *     因为「偶发失败」不该被记成「服务挂了」；</li>
 *     <li>CLOSED → OPEN：连续失败达到阈值（默认 3 次）。
 *     单次失败可能只是网络抖动，连续 N 次才认定为持续性故障；</li>
 *     <li>OPEN（熔断）：一律拒绝，不再发起外部请求。到期后允许转入半开；</li>
 *     <li>OPEN → HALF_OPEN：熔断时长（默认 300 秒）到期后，放行唯一一个试探请求。
 *     之所以只放一个，是因为此刻服务商可能还没恢复，放开全部流量等于把故障再来一遍；</li>
 *     <li>HALF_OPEN → CLOSED：试探成功，说明服务已恢复，立即闭合并清零计数；</li>
 *     <li>HALF_OPEN → OPEN：试探失败，立即重新打开。这里不能再用「连续 N 次」的标准
 *     拖延——否则又要等 N 次失败才熔断，期间用户全在白等。</li>
 * </ul>
 *
 * <p>为什么是进程内状态而不是放 Redis：当前是单实例部署，进程内状态零依赖、零延迟。
 * 若将来横向扩容，多个实例各自为政（一个实例熔断了，别的还在打外部服务），
 * 届时应把状态搬到 Redis，参照配额计数的做法。迁移前这里是单点。
 *
 * <p>不计入熔断的情形：「未配置 base-url / api-key」是配置问题而非服务故障，
 * 调用方（AiManager）对此不调用 recordFailure——否则本地没配 AI 也会被熔断，
 * 而那是启动时就已经知道的事。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class AiCircuitBreaker {

    /**
     * 连续失败多少次打开熔断
     *
     * <p>来自配置 studio.ai.circuit-breaker-failure-threshold（默认 3）。
     * 做成可配置是因为「多敏感才算故障」取决于服务商的稳定性，不该写死在代码里。
     *
     * <p>容易踩的坑：构造器里用 Math.max(1, ...) 兜底。若配成 0 或负数而不兜底，
     * 「失败次数 >= 0」恒成立，熔断会在第一次失败（甚至第一次成功之前）就被打开，
     * 表现为「AI 从来没成功过」，而且很难联想到是配置问题。
     */
    private final int failureThreshold;

    /**
     * 熔断时长
     *
     * <p>来自配置 studio.ai.circuit-breaker-open-seconds（默认 300 秒）。
     * 短于故障自愈周期会反复打开（每次都白等一次超时），长于它则用户干等。
     *
     * <p>同样用 Math.max(1, ...) 兜底：配成 0 会让熔断「立刻到期」，
     * 等于永远处于半开试探，熔断形同虚设。
     */
    private final Duration openDuration;

    /** 当前状态。用 AtomicReference 是因为状态切换要在并发下保持一致（见 allowRequest 的 CAS） */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /** 闭合状态下的连续失败计数（打开熔断或成功时清零） */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /**
     * 进入熔断的时刻（毫秒时间戳），用于判断是否到期
     *
     * <p>用 volatile 而不是 AtomicLong：它只有「整体写入、整体读取」，
     * 没有「读出来加一再写回」这类复合操作，volatile 的可见性保证已经足够。
     */
    private volatile long openedAtMillis = 0L;

    /** 半开状态下是否已放行过试探（保证只放行一个） */
    private final AtomicBoolean trialConsumed = new AtomicBoolean(false);

    /**
     * 构造器注入配置（阈值与时长）
     *
     * <p>为什么这样设计：注入 AiProperties 而不是两个裸值，是因为配置项集中在一处，
     * 将来加「熔断开关」之类的兄弟配置时不必改构造器签名。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 读阈值并兜底到至少 1：0 或负数会让熔断立刻打开，见字段注释；</li>
     *     <li>② 读熔断秒数并兜底到至少 1：0 会让熔断立刻到期，等于永不熔断。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里不做「配置非法就抛异常」。熔断参数是稳定性调参项，
     * 一个写错的数字不该让整个应用起不来——与 AiProperties 全字段有默认值是同一条纪律。
     *
     * @param aiProperties AI 模块配置；Spring 容器保证非 null，
     *                     但其中的数值字段可能是 0 或负数，这里统一兜底
     */
    public AiCircuitBreaker(AiProperties aiProperties) {
        this.failureThreshold = Math.max(1, aiProperties.getCircuitBreakerFailureThreshold());
        this.openDuration = Duration.ofSeconds(Math.max(1, aiProperties.getCircuitBreakerOpenSeconds()));
    }

    /**
     * 熔断器的三种状态
     *
     * <p>为什么这样设计：用枚举而不是 int 常量，是为了让 switch 与日志都能直接读懂。
     * 三个值是闭集，状态之间的合法流转只有四条（见类注释），
     * 枚举让「非法状态」在编译期就不可能出现。
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 是否允许本次调用放行
     *
     * <p>为什么这样设计：本方法带有副作用——熔断到期时会把状态切到半开，
     * 并消耗掉那唯一一次试探机会。因此它必须与随后的 recordSuccess / recordFailure
     * 成对调用：只调用不记录，半开机会就会被无声消耗，熔断永远无法自愈。
     * 反过来，如果只是想「提前绕开一次注定失败的调用」而并不真正发起请求，
     * 应该用只读的 isOpen()，不要用本方法。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① CLOSED 直接放行：这是绝大多数请求走的分支，不产生任何 CAS 开销；</li>
     *     <li>② OPEN 时先看是否到期：未到期一律拒绝，调用方应立即快速失败；</li>
     *     <li>③ 已到期则用 CAS 从 OPEN 切到 HALF_OPEN，只有切成功的那一个线程
     *     获得试探资格并返回 true；</li>
     *     <li>④ CAS 失败说明并发下已有别的线程切走了，按最新状态重判一次
     *     （此时状态多半已是 HALF_OPEN，会走到第 ⑤ 步）；</li>
     *     <li>⑤ HALF_OPEN 时用 CAS 把「已放行」标记从 true 翻成 false：
     *     只有第一个进来的线程能翻成功，其余线程直接失败。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ③ 步的 CAS 不能省。若写成「判断到期就直接 set 成 HALF_OPEN」，
     * 并发下会有多个线程同时认为自己拿到了试探资格，于是「只放一个」变成「放一批」——
     * 服务商还没恢复时，这一批请求会把刚有起色的服务再打垮一次。
     *
     * @return true 表示放行；false 表示熔断中，调用方应立即快速失败（不要再发外部请求）
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            // 到期 → 转入半开，放行唯一一次试探
            if (System.currentTimeMillis() - openedAtMillis >= openDuration.toMillis()) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    trialConsumed.set(true);
                    log.info("AI 熔断到期，进入半开试探 | 将放行一个请求探活");
                    return true;
                }
                // CAS 失败说明并发下已有别的线程切走了，按最新状态重判一次
                return allowRequest();
            }
            return false;
        }
        // HALF_OPEN：只放行一个试探，其余直接失败
        return trialConsumed.compareAndSet(true, false);
    }

    /**
     * 记录一次成功：闭合熔断并清零计数
     *
     * <p>为什么这样设计：用直接覆盖（set）而不是 CAS，是因为成功是良性事件——
     * 无论当前处于什么状态，「服务已经能用了」这个事实都应当立即生效。
     * 这里也没有「只在 CLOSED 时才清零」之类的条件：半开试探成功必须能闭合熔断，
     * 否则这个状态机就没有出口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 状态置为 CLOSED：一次成功就足以否定「持续性故障」的判断；</li>
     *     <li>② 失败计数清零：连续失败的定义是「中间没有成功过」，成功即断开；</li>
     *     <li>③ 复位试探标记，为下一次熔断做好准备。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法必须与 allowRequest 配对。若某条调用路径放行了却忘了
     * 在成功时调用它，半开状态会一直卡着——试探机会已经被消耗，又没人把它闭合，
     * 之后所有请求都会被拒，直到熔断再次到期。
     */
    public void recordSuccess() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
    }

    /**
     * 记录一次失败：累计计数，达到阈值打开熔断；半开试探失败则立即重新打开
     *
     * <p>为什么这样设计：失败分两种，处理方式必须不同。半开状态下的失败是
     * 「试探结果表明服务还没好」，要立刻回到熔断，不能再用「连续 N 次」的标准拖延——
     * 否则又要白等 N 次超时。闭合状态下的失败才是「可能只是抖动」，累计计数即可。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先读当前状态，判断是不是半开试探失败；</li>
     *     <li>② 是半开：立即打开熔断并返回，不再累加计数（计数在这里没有意义）；</li>
     *     <li>③ 不是半开：失败计数 +1，达到阈值就打开熔断。
     *     用 incrementAndGet 而不是「读出来加一再写回」，避免并发下丢失计数——
     *     丢计数会让熔断迟迟不打开，正是它要防的那件事。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不要吞异常。它的调用点都在 catch 块里，
     * 记录失败之后调用方仍需抛出业务异常——「记了熔断」不等于「这次调用成功了」。
     *
     * <p>本方法没有并发/边界上的陷阱：计数与状态都用原子类型，
     * 最坏情况只是阈值附近的判断略有偏差（例如两个线程同时把计数推到阈值），
     * 而这对「快速失败」的语义没有影响。
     */
    public void recordFailure() {
        State current = state.get();
        // 试探失败：立刻重新打开，不能再用「连续 N 次」的标准拖延
        if (current == State.HALF_OPEN) {
            openCircuit();
            return;
        }
        if (failureCount.incrementAndGet() >= failureThreshold) {
            openCircuit();
        }
    }

    /**
     * 打开熔断：记录时刻、清零计数，并打一条 warn
     *
     * <p>为什么这样设计：只打一条 warn 而不是每次失败一条 error，是因为熔断打开本身
     * 就是一个「已经知道原因」的事件——后续每个被拒绝的请求都会再记一条 warn，
     * 若这里也记 error，日志会被同一件事刷爆，真正的新问题反而被淹没。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 状态置为 OPEN，后续请求在 allowRequest 里直接被拒；</li>
     *     <li>② 记录打开时刻，它是「何时可以试探」的唯一依据；</li>
     *     <li>③ 清零失败计数并复位试探标记：下一轮从零开始判断，
     *     否则刚打开时计数就已经超过阈值，恢复后会立刻再次打开；</li>
     *     <li>④ 打一条 warn，把阈值与熔断时长一起写进日志，便于事后核对配置。</li>
     * </ol>
     *
     * <p>容易踩的坑：清零计数不能漏。它看起来「刚打开，计数马上会被覆盖」，
     * 但半开试探成功后如果计数还留着旧值，下一次单次失败就会立即重新熔断，
     * 表现为「服务恢复了却频繁闪断」。
     */
    private void openCircuit() {
        state.set(State.OPEN);
        openedAtMillis = System.currentTimeMillis();
        failureCount.set(0);
        trialConsumed.set(false);
        log.warn("AI 服务熔断已打开 | 连续失败达到阈值 {}，接下来 {} 秒内的 AI 调用将快速失败（不发起外部请求）",
                failureThreshold, openDuration.toSeconds());
    }

    /**
     * 是否熔断中（只读判断，无副作用）
     *
     * <p>为什么这样设计：与 allowRequest 的关键区别是——本方法不消耗半开试探机会。
     * 因此它适合「只想提前绕开一次注定失败的调用」的场景，例如问答链路在检索前
     * 先看一眼熔断状态，熔断中就直接跳过 Embedding，省掉一次必然失败的外部往返。
     * 真正要发起外部请求的调用点必须用 allowRequest，并配对 recordSuccess / recordFailure。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先判状态是否为 OPEN：只有 OPEN 才可能是「熔断中」，
     *     CLOSED 与 HALF_OPEN 都返回 false；</li>
     *     <li>② 再判剩余时间是否大于 0：已到期但还没人调用 allowRequest 的情况
     *     算作「未熔断」——下一秒就可能有请求来试探，这里返回 true 会让它被无谓地挡掉。</li>
     * </ol>
     *
     * <p>容易踩的坑：把本方法与 allowRequest 混用是这里最容易犯的错。
     * 若在真正发起外部请求的调用点用了 isOpen()，熔断将永远无法自愈——
     * 因为没人去消耗半开机会，也没人去记录成功。
     *
     * @return true 表示熔断中；闭合、半开、以及已到期待试探都返回 false
     */
    public boolean isOpen() {
        return state.get() == State.OPEN && remainingSeconds() > 0;
    }

    /**
     * 熔断剩余秒数（0 表示未熔断）
     *
     * <p>为什么这样设计：给调用方拼友好文案用。「AI 服务恢复中，请约 30 秒后再试」
     * 比一句「暂时不可用」有用得多——前者告诉用户该做什么，后者只让人反复点击。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 非 OPEN 一律返回 0：半开与闭合都没有「还要等多久」的概念；</li>
     *     <li>② 用「打开时刻 + 时长 - 当前时刻」算出剩余毫秒；</li>
     *     <li>③ 向上取整到秒：(remainMillis + 999) / 1000。
     *     若直接整除，剩余 0.4 秒会显示 0，前端文案变成「请约 0 秒后再试」。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里不做任何状态切换。它只是个只读计算，
     * 在它里面「顺便」把到期状态改成半开，会让调用方以为只是查了一下剩余时间，
     * 实际上却消耗了试探机会。
     *
     * @return 剩余秒数（向上取整）；未熔断或已到期时返回 0
     */
    public long remainingSeconds() {
        if (state.get() != State.OPEN) {
            return 0L;
        }
        long remainMillis = openedAtMillis + openDuration.toMillis() - System.currentTimeMillis();
        return remainMillis > 0 ? (remainMillis + 999) / 1000 : 0L;
    }

    /**
     * 重置为闭合状态（清零计数与熔断时刻）
     *
     * <p>为什么这样设计：本方法给测试隔离用。熔断器是进程内单例，跨测试类共享；
     * 某个测试类里「故意让 AI 失败」的用例会把熔断打开，之后同一 JVM 里的其它 AI 测试
     * 全部快速失败，表现为「莫名其妙一批 C0200」。因此触发过 AI 失败的测试类
     * 必须在每个用例开始前调用本方法复位。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 状态置回 CLOSED，恢复放行；</li>
     *     <li>② 失败计数清零，否则下一个用例只要失败一次就可能直接熔断；</li>
     *     <li>③ 复位试探标记并把打开时刻归零，让「是否到期」的判断回到初始状态。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里刻意没有做「运维手动恢复」的接口。半开机制会让熔断自动恢复，
     * 再多一个手动端点属于给运维幻觉——按下去也不知道服务商是否真的好了。
     * 真需要人工介入时，重启进程就是最彻底的 reset。
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
        openedAtMillis = 0L;
    }
}
