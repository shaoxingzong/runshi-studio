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
 * 服务商持续故障时，每次提问都真实打一次外部调用（同步最坏等 60 秒）。熔断承认故障、
 * 快速失败、定期试探，把最坏等待从分钟级降到毫秒级。
 *
 * CLOSED 累计连续失败、到阈值转 OPEN 拒绝、到期转 HALF_OPEN 只放一个试探，成功回 CLOSED。
 * 状态放进程内是因为当前单实例部署；将来横向扩容需搬到 Redis，否则各实例各自为政。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class AiCircuitBreaker {

    /**
     * 连续失败多少次打开熔断，来自 studio.ai.circuit-breaker-failure-threshold（默认 3）
     *
     * 坑：构造器里用 Math.max(1, ...) 兜底。配成 0 或负数时「失败次数 >= 0」恒成立，
     * 熔断会在第一次失败前就被打开，表现为「AI 从来没成功过」。
     */
    private final int failureThreshold;

    /**
     * 熔断时长，来自 studio.ai.circuit-breaker-open-seconds（默认 300 秒）
     *
     * 短于故障自愈周期会反复打开，长于它则用户干等。
     * 同样兜底到至少 1：配成 0 会让熔断立刻到期，等于永远处于半开试探。
     */
    private final Duration openDuration;

    /** 当前状态。用 AtomicReference 是因为状态切换要在并发下保持一致（见 allowRequest 的 CAS） */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /** 闭合状态下的连续失败计数（打开熔断或成功时清零） */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /**
     * 进入熔断的时刻（毫秒），用于判断是否到期
     *
     * 用 volatile 而非 AtomicLong：只有整体写入与整体读取，没有复合操作。
     */
    private volatile long openedAtMillis = 0L;

    /** 半开状态下是否已放行过试探（保证只放行一个） */
    private final AtomicBoolean trialConsumed = new AtomicBoolean(false);

    /**
     * 构造器注入配置，并把两个数值兜底到至少 1
     *
     * 不因配置非法抛异常：熔断参数是稳定性调参项，一个写错的数字不该让应用起不来。
     *
     * @param aiProperties AI 模块配置；数值字段可能是 0 或负数，这里统一兜底
     */
    public AiCircuitBreaker(AiProperties aiProperties) {
        this.failureThreshold = Math.max(1, aiProperties.getCircuitBreakerFailureThreshold());
        this.openDuration = Duration.ofSeconds(Math.max(1, aiProperties.getCircuitBreakerOpenSeconds()));
    }

    /**
     * 熔断器的三种状态
     *
     * 用枚举而非 int 常量，让 switch 与日志都能直接读懂，非法状态在编译期就不可能出现。
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 是否允许本次调用放行
     *
     * 本方法带副作用：熔断到期时会把状态切到半开并消耗掉那唯一一次试探机会，
     * 因此必须与随后的 {@link #recordSuccess()} / {@link #recordFailure()} 成对调用，
     * 否则半开机会被无声消耗，熔断永远无法自愈。只读判断请用 {@link #isOpen()}。
     *
     * 坑：从 OPEN 切 HALF_OPEN 必须用 CAS。写成「判断到期就直接 set」会让多个线程
     * 同时拿到试探资格，「只放一个」变成「放一批」，把刚有起色的服务再打垮一次。
     *
     * @return true 表示放行；false 表示熔断中，调用方应立即快速失败
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
     * 用直接覆盖而非 CAS：成功是良性事件，无论当前什么状态都应立即生效。
     * 不加「只在 CLOSED 时才清零」的条件——半开试探成功必须能闭合，否则状态机没有出口。
     */
    public void recordSuccess() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
    }

    /**
     * 记录一次失败：累计计数，达到阈值打开熔断；半开试探失败则立即重新打开
     *
     * 半开下的失败不能再用「连续 N 次」的标准拖延，否则又要白等 N 次超时。
     * 用 incrementAndGet 而非读改写：丢计数会让熔断迟迟不打开，正是它要防的事。
     *
     * 坑：本方法不要吞异常。调用点都在 catch 块里，记录失败之后调用方仍需抛出业务异常。
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
     * 只打 warn 不打 error：熔断打开是「已知原因」的事件，后续每个被拒请求都会再记一条，
     * 这里也记 error 会把真正的新问题淹没。
     *
     * 坑：清零计数不能漏。半开试探成功后若还留着旧计数，下一次单次失败就会立即重新熔断，
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
     * 是否熔断中（只读，无副作用）
     *
     * 与 {@link #allowRequest()} 的关键区别是不消耗半开试探机会，
     * 适合「检索前先看一眼、熔断中就跳过 Embedding」这类提前绕开的场景。
     *
     * 坑：真正发起外部请求的调用点若用了本方法，熔断将永远无法自愈——
     * 没人消耗半开机会，也没人记录成功。
     *
     * @return true 表示熔断中；闭合、半开、已到期待试探都返回 false
     */
    public boolean isOpen() {
        return state.get() == State.OPEN && remainingSeconds() > 0;
    }

    /**
     * 熔断剩余秒数（0 表示未熔断），供调用方拼「请约 N 秒后再试」的文案
     *
     * 向上取整：(remainMillis + 999) / 1000。直接整除时剩余 0.4 秒会显示 0，
     * 文案变成「请约 0 秒后再试」。
     *
     * 本方法只读：不要顺手在这里把到期状态改成半开，那会消耗掉试探机会。
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
     * 重置为闭合状态（清零计数与熔断时刻），给测试隔离用
     *
     * 熔断器是进程内单例、跨测试类共享：某个用例故意让 AI 失败会把熔断打开，
     * 之后同一 JVM 里的其它 AI 用例全部快速失败，表现为「莫名其妙一批 C0200」。
     * 触发过 AI 失败的测试类必须在每个用例前复位。
     *
     * 刻意不提供运维手动恢复接口：半开机制会自动恢复，重启进程就是最彻底的 reset。
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
        openedAtMillis = 0L;
    }
}
