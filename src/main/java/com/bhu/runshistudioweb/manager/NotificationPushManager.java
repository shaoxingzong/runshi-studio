package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 站内通知的实时推送通道（SSE 长连接）
 *
 * <p>与 AI 流式接口不同：那条是「一次性流、回答结束就 complete」，这里是**长期保持**的
 * 单向通道，按用户 ID 维护 emitter 注册表，通知落库后由业务侧触发 {@link #pushUnread}。
 * 事件体只带未读数，不含身份、正文、IP。
 *
 * <p>已知局限：注册表是**单实例内存态**。多实例部署时「连接在 A、通知写在 B」会漏推，
 * 因此前端必须保留低频轮询兜底；未来可换 Redis Pub/Sub 广播。单实例也要限制连接数，
 * 避免 2C2G 上长连接堆积。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class NotificationPushManager {

    /** 单条连接最长存活时间：到期后由前端重连，避免僵尸连接永久占用 */
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

    /** 心跳间隔：必须小于 Nginx 的 proxy_read_timeout（120s），否则连接会被代理掐断 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30L;

    /** 每个用户最多同时保持的连接数（多标签页场景） */
    private static final int MAX_PER_USER = 3;

    /** 全实例连接总数上限：2C2G 下留足余量又不至于堆积 */
    private static final int MAX_TOTAL = 500;

    /** 事件名：前端只认这个，其它事件（心跳注释）忽略 */
    private static final String EVENT_UNREAD = "unread";

    /** userId → 该用户的全部在线连接；CopyOnWriteArraySet 保证遍历时增删安全 */
    private final Map<Long, CopyOnWriteArraySet<SseEmitter>> online = new ConcurrentHashMap<>();

    private final AtomicInteger totalConnections = new AtomicInteger();

    /** SSE 负载序列化：复用容器里被 JsonConfig 定制过的 JsonMapper */
    private final JsonMapper jsonMapper;

    /** 心跳线程：daemon，不阻塞应用关闭 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "notification-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationPushManager(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 订阅：注册一条连接并立刻推送当前未读数（首帧，避免前端等到下一次事件才知道红点）
     *
     * @throws BusinessException 连接数超限时抛出，前端按退避重连；不会静默拒绝
     */
    public SseEmitter subscribe(long userId, long unread) {
        if (totalConnections.get() >= MAX_TOTAL) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "在线连接过多，请稍后重试");
        }
        CopyOnWriteArraySet<SseEmitter> connections =
                online.computeIfAbsent(userId, key -> new CopyOnWriteArraySet<>());
        if (connections.size() >= MAX_PER_USER) {
            online.remove(userId, connections);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该账号打开的通知连接过多");
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        connections.add(emitter);
        totalConnections.incrementAndGet();

        // 三条回调都要注销：超时/异常未必会再触发 onCompletion，漏一个就是连接泄漏
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(throwable -> cleanup.run());

        sendUnread(emitter, unread);
        log.debug("通知 SSE 已连接 | userId={} | 当前连接数={}", userId, totalConnections.get());
        return emitter;
    }

    /** 向某用户的全部在线连接广播最新未读数；没有在线连接时静默返回（轮询会兜底） */
    public void pushUnread(long receiverId, long unread) {
        CopyOnWriteArraySet<SseEmitter> connections = online.get(receiverId);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : connections) {
            if (!sendUnread(emitter, unread)) {
                remove(receiverId, emitter);
            }
        }
    }

    /** 应用关闭时收尾：停心跳、结束全部连接，避免请求线程挂在已销毁的 emitter 上 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        online.values().forEach(connections -> connections.forEach(SseEmitter::complete));
        online.clear();
        totalConnections.set(0);
    }

    // 私有工具

    /** 发送一个 unread 事件；失败返回 false 由调用方移除连接，不抛到业务线程 */
    private boolean sendUnread(SseEmitter emitter, long unread) {
        try {
            String payload = jsonMapper.writeValueAsString(Map.of("unread", unread));
            emitter.send(SseEmitter.event().name(EVENT_UNREAD).data(payload));
            return true;
        } catch (Exception e) {
            log.debug("通知 SSE 推送失败（客户端可能已断开）| unread={}", unread);
            return false;
        }
    }

    /** 定时发 SSE 注释帧保活；注释不是事件，前端解析器按空行跳过即可 */
    private void sendHeartbeats() {
        online.forEach((userId, connections) -> {
            for (SseEmitter emitter : connections) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(userId, emitter);
                }
            }
        });
    }

    /** 注销连接；集合空了就移除 key，防止用户维度无限累积 */
    private void remove(long userId, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> connections = online.get(userId);
        if (connections != null && connections.remove(emitter)) {
            totalConnections.decrementAndGet();
            if (connections.isEmpty()) {
                online.remove(userId, connections);
            }
        }
    }
}
