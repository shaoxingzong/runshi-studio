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
 * @author shaoshing
 */
@Slf4j
@Component
public class AiCircuitBreaker {

    /** 连续失败多少次打开熔断，来自 studio.ai.circuit-breaker-failure-threshold（默认 3） */
    private final int failureThreshold;

    /** 熔断时长，来自 studio.ai.circuit-breaker-open-seconds（默认 300 秒） */
    private final Duration openDuration;

    /** 当前状态。用 AtomicReference 是因为状态切换要在并发下保持一致（见 allowRequest 的 CAS） */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /** 闭合状态下的连续失败计数（打开熔断或成功时清零） */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** 进入熔断的时刻（毫秒），用于判断是否到期 */
    private volatile long openedAtMillis = 0L;

    /** 半开状态下是否已放行过试探（保证只放行一个） */
    private final AtomicBoolean trialConsumed = new AtomicBoolean(false);

    /**
     * 构造器注入配置，并把两个数值兜底到至少 1
     *
     * @param aiProperties AI 模块配置；数值字段可能是 0 或负数，这里统一兜底
     */
    public AiCircuitBreaker(AiProperties aiProperties) {
        this.failureThreshold = Math.max(1, aiProperties.getCircuitBreakerFailureThreshold());
        this.openDuration = Duration.ofSeconds(Math.max(1, aiProperties.getCircuitBreakerOpenSeconds()));
    }

    /** 熔断器的三种状态 */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

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

    /** 记录一次成功：闭合熔断并清零计数 */
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

    /** 打开熔断：记录时刻、清零计数，并打一条 warn */
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
     * @return true 表示熔断中；闭合、半开、已到期待试探都返回 false
     */
    public boolean isOpen() {
        return state.get() == State.OPEN && remainingSeconds() > 0;
    }

    /**
     * 熔断剩余秒数（0 表示未熔断），供调用方拼「请约 N 秒后再试」的文案
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

    /** 重置为闭合状态（清零计数与熔断时刻），给测试隔离用 */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        trialConsumed.set(false);
        openedAtMillis = 0L;
    }
}
