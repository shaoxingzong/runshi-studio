package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.exception.BusinessException;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创建型接口幂等令牌测试
 *
 * <p>用真实 Redis（与限流/配额测试同一纪律），验证 SETNX 语义、
 * 域与用户隔离、TTL、空令牌兼容旧客户端、以及重复请求被拒。
 *
 * @author shaoshing
 */
@SpringBootTest
class IdempotencyManagerTest {

    @Resource
    private IdempotencyManager idempotencyManager;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        // 每个用例换一个用户，避免相互污染；TTL 30s，用例本身远小于窗口
        userId = System.nanoTime();
    }

    private String token() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("同一令牌只放行一次；重复请求抛业务异常")
    void sameTokenIsRejected() {
        String token = token();
        assertTrue(idempotencyManager.acquire(userId, "post", token));
        assertFalse(idempotencyManager.acquire(userId, "post", token), "窗口内重复请求必须被挡");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> idempotencyManager.assertFirstRequest(userId, "post", token));
        assertTrue(ex.getMessage().contains("重复提交"), ex.getMessage());
    }

    @Test
    @DisplayName("不同域 / 不同用户 / 不同令牌互不干扰")
    void isolation() {
        String token = token();
        assertTrue(idempotencyManager.acquire(userId, "post", token));
        // 同一个人在另一个表单用同一个令牌串值也不该被挡（域隔离）
        assertTrue(idempotencyManager.acquire(userId, "comment", token));
        // 别人的同一个令牌不受影响
        assertTrue(idempotencyManager.acquire(userId + 1, "post", token));
        // 新令牌当然放行
        assertTrue(idempotencyManager.acquire(userId, "post", token()));
    }

    @Test
    @DisplayName("令牌按 30s 过期，不会永久占用")
    void ttlIsSet() {
        String token = token();
        assertTrue(idempotencyManager.acquire(userId, "post", token));
        String key = "studio:idem:post:" + userId + ":" + token;
        Long ttl = stringRedisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 30, "TTL 应为 30s 以内，实际=" + ttl);
    }

    @Test
    @DisplayName("空令牌放行：兼容旧版本客户端 / 直接调接口")
    void blankTokenPassesThrough() {
        assertDoesNotThrow(() -> idempotencyManager.assertFirstRequest(userId, "post", null));
        assertDoesNotThrow(() -> idempotencyManager.assertFirstRequest(userId, "post", ""));
        assertDoesNotThrow(() -> idempotencyManager.assertFirstRequest(userId, "post", "   "));
        assertEquals(true, idempotencyManager.acquire(userId, "post", null));
    }

    @Test
    @DisplayName("令牌首尾空白被归一化，不至于因格式差异漏挡")
    void tokenIsTrimmed() {
        String token = token();
        assertTrue(idempotencyManager.acquire(userId, "post", "  " + token + "  "));
        assertFalse(idempotencyManager.acquire(userId, "post", token));
    }
}
