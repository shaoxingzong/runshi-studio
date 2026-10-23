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
 * 解决什么问题：AI 服务出现持续性故障时（api-key 被冻结、欠费、服务商整体宕机），
 * 每一次用户提问都会真实打一次外部调用——同步接口最坏等 60s 超时，RAG 还要再打一次 embedding，
 * 日志被同样的堆栈刷爆，而用户除了反复看到「暂时不可用」什么也做不了。
 * 熔断的思想是：承认故障存在，快速失败，定期试探，把最坏等待从分钟级降到毫秒级。
 *
 * 三态语义：
 * - State#CLOSED 闭合：正常放行，连续失败累计计数；
 * - State#OPEN 熔断：一律拒绝，到期后转入半开；
 * - State#HALF_OPEN 半开：只放行一个试探请求——
 * 成功即闭合，失败立即重新打开（试探失败必须立刻回到熔断，
 * 否则又要连续失败 N 次才能重新打开，期间用户在白等）。
 *
 * 为什么是进程内而不是 Redis：当前是单实例部署，进程内状态零依赖、零延迟。
 * 若将来横向扩容，多个实例各自的熔断器会「各自为政」（一个实例熔断了，别的还在打外部服务），
 * 届时应把状态搬到 Redis（可参考 AiQueryCountManager 的范式）。
 * 迁移前这里就是单点，改动时别忘了同步 AiManager 的注释。
 *
 * 不计入熔断的情形：「未配置 base-url / api-key」是配置问题而非服务故障，
 * 调用方（ AiManager）对此不调用 recordFailure——
 * 否则本地没配 AI 也会被「熔断」，而那是启动时就已经知道的事。
 */
@Slf4j
@Component
public class AiCircuitBreaker {

    /**
     * 连续失败多少次打开熔断
     *
     * 来自配置 studio.ai.circuit-breaker-failure-threshold（默认 3）：
     * 单次失败可能是瞬时抖动，连续 N 次才认定为持续性故障。做成可配置是因为
     * 「多敏感才算故障」取决于服务商的稳定性，不该写死在代码里。
     */
    private final int failureThreshold;

    /**
     * 熔断时长
     *
     * 来自配置 studio.ai.circuit-breaker-open-seconds（默认 300）：
     * 短于故障自愈周期会反复打开，长于它则用户干等。
     */
    private final Duration openDuration;

    /** 当前状态 */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /** 闭合状态下的连续失败计数（打开熔断或成功时清零） */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** 进入熔断的时刻（毫秒时间戳），用于判断是否到期 */
    private volatile long openedAtMillis = 0L;

    /** 半开状态下是否已放行过试探（保证只放行一个） */
    private final AtomicBoolean trialConsumed = new AtomicBoolean(false);

    /**
     * 构造器注入配置（阈值与时长）
     *
     * 注入 AiProperties 而不是两个裸值：配置项集中在一处，
     * 将来加「熔断开关」之类的 sibling 配置时不必改构造器签名。
     */
    public AiCircuitBreaker(AiProperties aiProperties) {
        this.failureThreshold = Math.max(1, aiProperties.getCircuitBreakerFailureThreshold());
        this.openDuration = Duration.ofSeconds(Math.max(1, aiProperties.getCircuitBreakerOpenSeconds()));
    }

    /** 三态 */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 是否允许本次调用放行
     *
     * 副作用：熔断到期时会把状态切到半开并消耗掉那唯一一次试探机会。
     * 因此本方法必须与随后的 recordSuccess / recordFailure 成对调用——
     * 只调用不记录会让半开机会被无声消耗，熔断永远无法自愈。
     *
     * @return true 放行；false 熔断中，调用方应立即快速失败
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
     * 用直接覆盖而非 CAS：成功是良性事件，无论当前是什么状态，
     * 「服务已经能用了」这个事实都应当立即生效。
     */
    public void recordSuccess() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
    }

    /** 记录一次失败：累计计数，达到阈值打开熔断；半开试探失败则立即重新打开 */
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

    /** 打开熔断：记录时刻、清零计数，并打一条 warn（而不是每次失败一条 error） */
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
     * 与 allowRequest 的关键区别：本方法不消耗半开试探机会。
     * 因此它适合「只想提前绕开一次注定失败的调用」的场景（如
     * AiChatServiceImpl 跳过 RAG 检索）；而真正要发起外部请求的调用点
     * 必须用 allowRequest，且随后务必配对调用
     * recordSuccess / recordFailure——
     * 用错方法会让半开机会被无声消耗，熔断永远无法自愈。
     *
     * 「已到期但还没人调用 allowRequest」算未熔断：下一秒就可能有
     * 请求来试探，这里返回 true 会让它被无谓地挡掉。
     *
     * @return true 表示熔断中；闭合、半开、以及已到期待试探都返回 false
     */
    public boolean isOpen() {
        return state.get() == State.OPEN && remainingSeconds() > 0;
    }

    /**
     * 熔断剩余秒数（0 表示未熔断）
     *
     * 给调用方拼友好文案用：「恢复中，请 N 秒后再试」比干巴巴一句「不可用」
     * 更能让用户知道该做什么。
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
     * 当前唯一用途是测试隔离：熔断器是进程内单例，跨测试类共享。
     * 某个测试类的「故意让 AI 失败」用例会把熔断打开，之后同一 JVM 里的其它
     * AI 测试全部快速失败——表现为「莫名其妙一批 C0200」。因此触发过 AI 失败的
     * 测试类必须在 @BeforeEach 调本方法（已接入 AiChatCrudTest、
     * AiChatStreamTest、AiPromptFallbackTest、AiKnowledgeRetrievalTest、
     * PostSelfUpdateTest）。
     *
     * 为什么没有「运维手动恢复」的入口：半开机制会让熔断自动恢复，
     * 再多一个手动端点属于给运维幻觉（按下去也不知道服务商是否真的好了）。
     * 真需要人工介入时，重启进程就是最彻底的 reset。若将来要加，
     * 应与「AI 健康探活」一起设计，而不是单独塞一个按钮。
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
        openedAtMillis = 0L;
    }
}
