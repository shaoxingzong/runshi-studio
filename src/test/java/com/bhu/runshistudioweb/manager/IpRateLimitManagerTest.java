package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IP 双窗口限流器单测（纯 Mockito，不启 Spring、不连 Redis）
 *
 * author: shaoshing
 *
 * <p><b>为什么用纯单测而不是 MockMvc</b>：本类只依赖 {@link StringRedisTemplate}，
 * mock 掉它就能覆盖全部分支；走 Spring 上下文会新建一个上下文，
 * 而多个测试类对上下文停止顺序敏感（pom 的 surefire 注释记载过相关教训），
 * 能不起容器就不起。
 *
 * <p>覆盖重点：超限判定边界（等于上限放行、超过才拒）、双窗口各自的键与文案、
 * 拿不到 IP 放行、Redis 故障降级放行——降级是本类最重要的安全属性之一。
 */
@ExtendWith(MockitoExtension.class)
class IpRateLimitManagerTest {

    private static final String KEY_PREFIX = "studio:auth:rate:login:ip:";

    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 被测对象：手动 new，只注入 mock 的模板（Lua 脚本字段有初始值，不是构造参数） */
    private IpRateLimitManager newManager() {
        return new IpRateLimitManager(stringRedisTemplate);
    }

    @Test
    @DisplayName("未超限：两个窗口都放行")
    void underLimit_passes() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        IpRateLimitManager manager = newManager();
        assertDoesNotThrow(() ->
                manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));
    }

    @Test
    @DisplayName("边界：恰好等于上限放行（判定是 > 而不是 >=）")
    void exactlyAtLimit_passes() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(10L);

        IpRateLimitManager manager = newManager();
        assertDoesNotThrow(() ->
                manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));
    }

    @Test
    @DisplayName("分钟窗口超限：抛 A0501，文案带窗口名与上限")
    void minuteWindowExceeded_rejected() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(11L);

        IpRateLimitManager manager = newManager();
        BusinessException e = assertThrows(BusinessException.class, () ->
                manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));

        assertEquals("A0501", e.getCode());
        assertEquals("登录太频繁了（每分钟最多 10 次），请稍后再试", e.getMessage());
    }

    @Test
    @DisplayName("日窗口超限：分钟窗口放行后仍被日窗口拒绝")
    void dayWindowExceeded_rejected() {
        // 第一次调用是分钟窗口（放行），第二次是日窗口（超限）
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L, 101L);

        IpRateLimitManager manager = newManager();
        BusinessException e = assertThrows(BusinessException.class, () ->
                manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));

        assertEquals("A0501", e.getCode());
        assertEquals("登录太频繁了（每天最多 100 次），请稍后再试", e.getMessage());
    }

    @Test
    @DisplayName("窗口键格式：前缀 + IP + :min:/:day: + 时间串，两个窗口各查一次")
    void keyFormat_correct() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        IpRateLimitManager manager = newManager();
        manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate, times(2))
                .execute(any(RedisScript.class), keysCaptor.capture(), any());

        String minute = LocalDateTime.now().format(MINUTE_FORMATTER);
        String day = LocalDateTime.now().format(DAY_FORMATTER);
        // 第一次 = 分钟窗口，第二次 = 日窗口（调用顺序即窗口顺序）
        assertEquals(List.of(KEY_PREFIX + "10.0.0.5:min:" + minute), keysCaptor.getAllValues().get(0));
        assertEquals(List.of(KEY_PREFIX + "10.0.0.5:day:" + day), keysCaptor.getAllValues().get(1));
    }

    @Test
    @DisplayName("分钟窗口 TTL 传 120（窗口 60s + 余量 60s，防窗口末尾失去保护）")
    void minuteWindowTtl_is120() {
        // 用精确 TTL 做桩：生产代码若传错 TTL，桩不匹配会返回 null（计数为 0）而不超限，
        // 下面的超限断言就会失败——以此间接锁定 TTL 取值
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), eq("120"))).thenReturn(11L);

        IpRateLimitManager manager = newManager();
        assertThrows(BusinessException.class, () ->
                manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));
    }

    @Test
    @DisplayName("日窗口 TTL 在 [60, 86460] 区间（距次日零点 + 60s 余量）")
    void dayWindowTtl_withinExpectedRange() {
        ArgumentCaptor<Object> ttlCaptor = ArgumentCaptor.forClass(Object.class);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        IpRateLimitManager manager = newManager();
        manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录");

        // 第二次调用是日窗口；varargs 位置的 captor 逐个捕获每个可变参数
        verify(stringRedisTemplate, times(2))
                .execute(any(RedisScript.class), anyList(), ttlCaptor.capture());
        long ttl = Long.parseLong(ttlCaptor.getAllValues().get(0).toString());
        // 最小 60（刚好跨日执行的极端余量），最大 86400 + 60（一天刚开始时）
        if (ttl < 60 || ttl > 86460) {
            throw new AssertionError("日窗口 TTL 越界: " + ttl);
        }
    }

    @Test
    @DisplayName("拿不到 IP：直接放行，不碰 Redis（宁可漏限也不能误杀）")
    void blankIp_passesWithoutRedis() {
        IpRateLimitManager manager = newManager();
        assertDoesNotThrow(() -> manager.assertAllowed("  ", KEY_PREFIX, 10, 100, "登录"));
        assertDoesNotThrow(() -> manager.assertAllowed(null, KEY_PREFIX, 10, 100, "登录"));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("窗口上限为 null：该窗口跳过，只查另一个")
    void nullLimit_skipsWindow() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        IpRateLimitManager manager = newManager();
        // 分钟上限关闭、日上限 100：只应发生一次 Redis 调用（日窗口）
        assertDoesNotThrow(() -> manager.assertAllowed("10.0.0.5", KEY_PREFIX, null, 100, "登录"));

        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("Redis 故障：放行 + 不抛（防护手段不能变成故障源）")
    void redisFailure_degradesToAllow() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        IpRateLimitManager manager = newManager();
        assertDoesNotThrow(() -> manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));
    }

    @Test
    @DisplayName("动作名进入文案：注册场景显示「注册太频繁了」")
    void actionName_inMessage() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(6L);

        IpRateLimitManager manager = newManager();
        BusinessException e = assertThrows(BusinessException.class, () ->
                manager.assertAllowed("10.0.0.5", "studio:auth:rate:register:ip:", 5, 20, "注册"));

        assertEquals("注册太频繁了（每分钟最多 5 次），请稍后再试", e.getMessage());
    }

    @Test
    @DisplayName("分钟窗口先于日窗口判定：分钟超限时不产生日窗口的 Redis 调用")
    void minuteRejection_shortCircuitsDayWindow() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(99L);

        IpRateLimitManager manager = newManager();
        assertThrows(BusinessException.class, () ->
                manager.assertAllowed("10.0.0.5", KEY_PREFIX, 10, 100, "登录"));

        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any());
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class),
                eq(List.of(KEY_PREFIX + "10.0.0.5:day:" + LocalDateTime.now().format(DAY_FORMATTER))), any());
    }
}
