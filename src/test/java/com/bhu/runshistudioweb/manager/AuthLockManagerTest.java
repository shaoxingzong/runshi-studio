package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AuthLockoutProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账号级登录失败锁定单测（纯 Mockito，不启 Spring、不连 Redis）
 *
 * 与 IpRateLimitManagerTest 同一套理由：本类只依赖
 * StringRedisTemplate 与配置对象，mock 掉即可覆盖全部分支；
 * 能不起容器就不起（多个测试类对上下文停止顺序敏感，见 pom 里 surefire 的记载）。
 *
 * 覆盖重点：
 * - 阈值边界：达到阈值即锁（判定是 >=，与 IP 限流的 > 相反——
 * 限流是「超过上限才拒」，锁定是「失败够次数就锁」，语义不同，边界也不同）；
 * - 键与 TTL：recordFailure 必须带账号名键与锁定时长换算出的秒数；
 * - 降级：Redis 故障时全部放行——锁定是防护手段，不能变成故障源；
 * - 开关：关闭时不碰 Redis。
 */
@ExtendWith(MockitoExtension.class)
class AuthLockManagerTest {

    private static final String ACCOUNT = "locktester";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /** 默认配置：开锁、5 次锁定、15 分钟 */
    private AuthLockManager newManager() {
        return new AuthLockManager(stringRedisTemplate, new AuthLockoutProperties());
    }

    private AuthLockManager newManager(AuthLockoutProperties properties) {
        return new AuthLockManager(stringRedisTemplate, properties);
    }

    // ==================== assertNotLocked：阈值与边界 ====================

    @Test
    @DisplayName("未达阈值：放行")
    void belowThreshold_passes() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("studio:auth:lock:" + ACCOUNT)).thenReturn("3");

        assertDoesNotThrow(() -> newManager().assertNotLocked(ACCOUNT));
    }

    @Test
    @DisplayName("从未失败过（key 不存在）：放行")
    void noKey_passes() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("studio:auth:lock:" + ACCOUNT)).thenReturn(null);

        assertDoesNotThrow(() -> newManager().assertNotLocked(ACCOUNT));
    }

    @Test
    @DisplayName("边界：恰好达到阈值即锁定（判定是 >= 而不是 >）")
    void exactlyAtThreshold_locked() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("studio:auth:lock:" + ACCOUNT)).thenReturn("5");

        BusinessException e = assertThrows(BusinessException.class,
                () -> newManager().assertNotLocked(ACCOUNT));

        assertEquals("A0501", e.getCode(), "锁定应走「请求过多」错误码");
        assertEquals("登录失败次数过多，账号已锁定，请 15 分钟后再试", e.getMessage(),
                "文案必须带锁定时长——用户需要知道等多久");
    }

    @Test
    @DisplayName("超过阈值：同样锁定")
    void aboveThreshold_locked() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("studio:auth:lock:" + ACCOUNT)).thenReturn("7");

        assertThrows(BusinessException.class, () -> newManager().assertNotLocked(ACCOUNT));
    }

    // ==================== 降级与开关 ====================

    @Test
    @DisplayName("Redis 读取故障：放行（防护手段不能变成故障源）")
    void redisFailure_passes() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> newManager().assertNotLocked(ACCOUNT));
    }

    @Test
    @DisplayName("总开关关闭：直接放行且不碰 Redis")
    void disabled_passesWithoutRedis() {
        AuthLockoutProperties props = new AuthLockoutProperties();
        props.setEnabled(false);

        assertDoesNotThrow(() -> newManager(props).assertNotLocked(ACCOUNT));
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("账号为空：直接放行（没有锁定对象）")
    void blankAccount_passes() {
        assertDoesNotThrow(() -> newManager().assertNotLocked(""));
        verifyNoInteractions(stringRedisTemplate);
    }

    // ==================== recordFailure：键与 TTL ====================

    @Test
    @DisplayName("记录失败：按账号键自增，TTL = 锁定时长换算的秒数")
    void recordFailure_usesAccountKeyAndTtl() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        newManager().recordFailure(ACCOUNT);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), ttlCaptor.capture());

        assertEquals(List.of("studio:auth:lock:" + ACCOUNT), keysCaptor.getValue(),
                "计数键必须带账号名——锁定以账号为对象");
        assertEquals("900", ttlCaptor.getValue(), "TTL 必须是锁定时长（分钟）换算的秒数");
    }

    @Test
    @DisplayName("记录失败时 Redis 故障：不抛异常（本次失败仍由密码错误挡下）")
    void recordFailure_redisFailure_silent() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> newManager().recordFailure(ACCOUNT));
    }

    @Test
    @DisplayName("开关关闭时不记录失败")
    void recordFailure_disabled_noRedis() {
        AuthLockoutProperties props = new AuthLockoutProperties();
        props.setEnabled(false);

        newManager(props).recordFailure(ACCOUNT);
        verifyNoInteractions(stringRedisTemplate);
    }

    // ==================== clear：成功清零 ====================

    @Test
    @DisplayName("登录成功：删除该账号的计数键")
    void clear_deletesKey() {
        newManager().clear(ACCOUNT);
        verify(stringRedisTemplate).delete("studio:auth:lock:" + ACCOUNT);
    }

    @Test
    @DisplayName("清零时 Redis 故障：不抛异常（凭据已验对，不影响本次登录）")
    void clear_redisFailure_silent() {
        when(stringRedisTemplate.delete("studio:auth:lock:" + ACCOUNT))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> newManager().clear(ACCOUNT));
    }

    @Test
    @DisplayName("clear 对空账号不发 Redis 命令")
    void clear_blankAccount_noRedis() {
        newManager().clear(null);
        // 用 anyString 而不是 any：delete 有 (K) 与 (Collection<K>) 两个重载，any() 会歧义
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("阈值可配置：maxFailures=3 时第 3 次失败即锁")
    void customThreshold_respected() {
        AuthLockoutProperties props = new AuthLockoutProperties();
        props.setMaxFailures(3);
        props.setLockMinutes(30);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("studio:auth:lock:" + ACCOUNT)).thenReturn("3");

        BusinessException e = assertThrows(BusinessException.class,
                () -> newManager(props).assertNotLocked(ACCOUNT));
        assertEquals("登录失败次数过多，账号已锁定，请 30 分钟后再试", e.getMessage(),
                "文案必须用配置的锁定时长，不是硬编码");
    }

    @Test
    @DisplayName("recordFailure 的 Lua 每次调用都带 TTL 参数（首次设置由 Lua 内部判断）")
    void recordFailure_alwaysPassesTtl() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(2L);

        newManager().recordFailure(ACCOUNT);
        newManager().recordFailure(ACCOUNT);

        // 两次调用、每次都带 TTL：Lua 脚本内部判断「首次才 EXPIRE」，
        // Java 侧无条件传参——TTL 判断放 Redis 是为了原子性
        verify(stringRedisTemplate, times(2)).execute(any(RedisScript.class), anyList(), eq("900"));
    }
}
