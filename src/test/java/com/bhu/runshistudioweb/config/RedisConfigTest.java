package com.bhu.runshistudioweb.config;

import com.bhu.runshistudioweb.model.entity.SysUser;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 配置验收测试
 *
 * author: shaoshing
 *
 * <p>这组用例专治「配置看着对、其实没生效」：序列化器是 Redis 接入里最容易被忽略的一环，
 * 配错了程序照常跑，只是 redis-cli 里全是二进制乱码、跨语言客户端读不了、重启后类型丢失。
 *
 * <p>注意：每个用例都自行清理写入的 key，不留垃圾数据（测试用的 Redis 往往和本地开发共用一个库）。
 */
@SpringBootTest
class RedisConfigTest {

    private static final long SNOWFLAKE_ID = 1790493804982123456L;
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 9, 27, 15, 44, 13);

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RedisConnectionFactory connectionFactory;

    @Test
    @DisplayName("连接可用：Redis 应能响应 PING")
    void connectionIsAlive() {
        String pong = assertDoesNotThrow(() -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                return connection.ping();
            }
        });
        assertEquals("PONG", pong);
    }

    @Test
    @DisplayName("序列化器配置：key/hashKey 必须是 String，value 不能是 JDK 序列化")
    void serializerConfiguration() {
        assertInstanceOf(StringRedisSerializer.class, redisTemplate.getKeySerializer(),
                "key 未使用 StringRedisSerializer，redis-cli 里会是二进制乱码");
        assertInstanceOf(StringRedisSerializer.class, redisTemplate.getHashKeySerializer(),
                "hashKey 未使用 StringRedisSerializer");
        assertFalse(redisTemplate.getValueSerializer() instanceof JdkSerializationRedisSerializer,
                "value 仍是 JDK 序列化，运维不可读且跨语言不通");
        assertFalse(redisTemplate.getHashValueSerializer() instanceof JdkSerializationRedisSerializer,
                "hashValue 仍是 JDK 序列化");
    }

    @Test
    @DisplayName("运维可读：写入 Redis 的原始字节必须是纯文本 JSON，且 key 是明文")
    void storedBytesAreReadableJson() {
        String key = "test:readable:" + System.nanoTime();
        SysUser user = new SysUser();
        user.setId(SNOWFLAKE_ID);
        user.setCreatedAt(FIXED_TIME);

        try {
            redisTemplate.opsForValue().set(key, user);

            try (RedisConnection connection = connectionFactory.getConnection()) {
                byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
                assertNotNull(connection.keyCommands().type(rawKey), "key 未写入成功");

                byte[] rawValue = connection.stringCommands().get(rawKey);
                assertNotNull(rawValue, "value 未写入成功");

                // JDK 序列化的字节流以 0xAC 0xED 开头，文本 JSON 则以 '{' 开头。
                // 这里刻意读「原始字节」而不是走 RedisTemplate 反序列化：
                // 走模板拿到的对象一定是正确的，只有看落库字节才能证明运维可见性
                assertFalse(rawValue[0] == (byte) 0xAC && rawValue[1] == (byte) 0xED,
                        "value 是 JDK 序列化的二进制流，redis-cli 中不可读");

                String stored = new String(rawValue, StandardCharsets.UTF_8);
                assertTrue(stored.startsWith("{"), "value 不是 JSON 对象。实际内容：" + stored);
                assertTrue(stored.contains("\"createdAt\":\"2026-09-27 15:44:13\""),
                        "缓存中的时间格式与接口约定不一致。实际内容：" + stored);
                assertTrue(stored.contains("\"id\":\"" + SNOWFLAKE_ID + "\""),
                        "缓存中的 Long 未序列化为字符串。实际内容：" + stored);
            }
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("类型保真：取出的对象必须是原实体类型，而不是 LinkedHashMap")
    void roundTripKeepsType() {
        String key = "test:type:" + System.nanoTime();
        SysUser user = new SysUser();
        user.setId(SNOWFLAKE_ID);
        user.setUserAccount("cache_probe");
        user.setCreatedAt(FIXED_TIME);

        try {
            redisTemplate.opsForValue().set(key, user);

            Object cached = redisTemplate.opsForValue().get(key);

            assertInstanceOf(SysUser.class, cached,
                    "未开启默认类型信息，取回的是 " + (cached == null ? "null" : cached.getClass().getName()));
            SysUser restored = (SysUser) cached;
            assertEquals(SNOWFLAKE_ID, restored.getId(), "缓存往返后 Long 精度丢失");
            assertEquals("cache_probe", restored.getUserAccount());
            assertEquals(FIXED_TIME, restored.getCreatedAt(), "缓存往返后时间不一致");
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("Hash 结构：hashValue 与 value 序列化器必须成对，否则写入后读不出")
    void hashStructureRoundTrip() {
        String key = "test:hash:" + System.nanoTime();
        SysUser user = new SysUser();
        user.setId(SNOWFLAKE_ID);

        try {
            redisTemplate.opsForHash().put(key, "user", user);

            Object cached = redisTemplate.opsForHash().get(key, "user");

            assertInstanceOf(SysUser.class, cached, "Hash 结构往返失败，取回类型："
                    + (cached == null ? "null" : cached.getClass().getName()));
            assertEquals(SNOWFLAKE_ID, ((SysUser) cached).getId());
        } finally {
            redisTemplate.delete(key);
        }
    }
}