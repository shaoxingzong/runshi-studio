package com.bhu.runshistudioweb.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDao;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sa-Token 配置生效验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
class SaTokenConfigTest {

    @Resource
    private SaTokenConfig saTokenConfig;

    @Resource
    private RedisConnectionFactory connectionFactory;

    @Test
    @DisplayName("yml 中的 sa-token 配置必须真实生效（写错的键会被静默忽略，这里专查是否生效）")
    void configTakesEffect() {
        assertEquals("satoken", saTokenConfig.getTokenName(), "token-name 未生效");
        assertEquals(604800L, saTokenConfig.getTimeout(), "timeout 未生效");
        assertEquals(1800L, saTokenConfig.getActiveTimeout(), "active-timeout 未生效");
        assertTrue(saTokenConfig.getIsConcurrent(), "is-concurrent 未生效");
        assertFalse(saTokenConfig.getIsShare(), "is-share 未生效");
        assertTrue(saTokenConfig.getIsLog(), "is-log 未生效");
    }

    @Test
    @DisplayName("会话持久化：SaTokenDao 必须是 Redis 实现，不能是默认的内存实现")
    void daoIsRedisBacked() {
        SaTokenDao dao = SaManager.getSaTokenDao();
        assertNotNull(dao, "SaTokenDao 未装配");
        String daoClass = dao.getClass().getName();

        assertTrue(daoClass.contains("Redis"),
                "SaTokenDao 不是 Redis 实现（当前 " + daoClass
                        + "），会话只存在内存里：服务重启即全部掉线、多实例之间不共享");
    }

    @Test
    @DisplayName("会话持久化：通过 SaTokenDao 写入的数据必须真实落到 Redis")
    void daoWritesIntoRedis() {
        SaTokenDao dao = SaManager.getSaTokenDao();
        String key = "verify:redis:dao:" + System.nanoTime();
        String value = "session-value-" + System.nanoTime();

        try {
            dao.set(key, value, 120);
            assertEquals(value, dao.get(key), "Dao 自身读写不一致");

            // 绕开 Sa-Token，直接从 Redis 里找这个 key，证明数据真的进了 Redis 而不是内存
            try (RedisConnection connection = connectionFactory.getConnection()) {
                Set<byte[]> keys = connection.keyCommands().keys(("*" + key + "*").getBytes(StandardCharsets.UTF_8));
                assertNotNull(keys, "未能在 Redis 中检索到会话 key");
                assertFalse(keys.isEmpty(),
                        "通过 SaTokenDao 写入的数据没有落到 Redis（只存在于内存），重启即丢登录态");
            }
        } finally {
            dao.delete(key);
        }
    }

    @Test
    @DisplayName("会话持久化：注册后更新会话（SaTokenDao.update）必须可用 —— 登录链路的关键调用")
    void daoUpdateKeepsTtlAndWorks() {
        SaTokenDao dao = SaManager.getSaTokenDao();
        String key = "verify:redis:update:" + System.nanoTime();

        try {
            dao.set(key, "v1", 120);
            dao.update(key, "v2");

            assertEquals("v2", dao.get(key), "update 后读到的值不对");
            assertTrue(dao.getTimeout(key) > 0, "update 后 TTL 丢失了");
        } finally {
            dao.delete(key);
        }
    }
}