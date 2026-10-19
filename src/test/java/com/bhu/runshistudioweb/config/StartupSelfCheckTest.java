package com.bhu.runshistudioweb.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 启动自检单测（纯 Mockito）
 *
 * author: shaoshing
 *
 * <p>这个类的存在本身是上线前验证的产物（见 {@link StartupSelfCheck} 类注释：
 * 实测发现缺环境变量时应用照常启动），所以它的测试重点是**三种坏配置都必须被拦下**，
 * 以及**本地开发的合法形态（AI 留空）不能被误伤**——后者决定了测试环境能否启动上下文。
 */
@ExtendWith(MockitoExtension.class)
class StartupSelfCheckTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private AiProperties aiProperties;

    @Mock
    private DataSource dataSource;

    private StartupSelfCheck selfCheck;

    @BeforeEach
    void setUp() {
        selfCheck = new StartupSelfCheck(jdbcTemplate, stringRedisTemplate, aiProperties, dataSource);
    }

    /** 让 Redis mock 对任意回调返回 PONG */
    private void givenRedisPong() {
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<Object> callback = invocation.getArgument(0);
            RedisConnection connection = org.mockito.Mockito.mock(RedisConnection.class);
            when(connection.ping()).thenReturn("PONG");
            return callback.doInRedis(connection);
        });
    }

    private void givenAiConfigured() {
        when(aiProperties.getBaseUrl()).thenReturn("https://api.example.com");
        when(aiProperties.getApiKey()).thenReturn("sk-real");
        when(aiProperties.getModel()).thenReturn("m");
        when(aiProperties.getEmbeddingModel()).thenReturn("e");
    }

    @Test
    @DisplayName("全部就绪：不抛异常")
    void allReady_passes() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        givenRedisPong();
        givenAiConfigured();

        assertDoesNotThrow(selfCheck::checkOnStartup);
    }

    @Test
    @DisplayName("数据库不可用：启动失败，错误信息带 URL 与修复指引")
    void databaseDown_fails() throws SQLException {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        // 失败路径会尝试从 DataSource 拿 URL 用于报错
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn("jdbc:mysql://127.0.0.1:3306/studio_db");
        when(dataSource.getConnection()).thenReturn(connection);

        IllegalStateException e = assertThrows(IllegalStateException.class, selfCheck::checkOnStartup);
        assertTrue(e.getMessage().contains("数据库不可用"), "错误信息应说明是数据库问题：" + e.getMessage());
        assertTrue(e.getMessage().contains("studio_db"), "错误信息应带上真实 URL：" + e.getMessage());
        assertTrue(e.getMessage().contains("MYSQL_PASSWORD"), "错误信息应给出修复指引：" + e.getMessage());
    }

    @Test
    @DisplayName("Redis 不可用：启动失败（Sa-Token/限流/配额全依赖它）")
    void redisDown_fails() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        when(stringRedisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("connection refused"));

        IllegalStateException e = assertThrows(IllegalStateException.class, selfCheck::checkOnStartup);
        assertTrue(e.getMessage().contains("Redis 不可用"), e.getMessage());
        assertTrue(e.getMessage().contains("REDIS_PASSWORD"), "错误信息应给出修复指引：" + e.getMessage());
    }

    @Test
    @DisplayName("AI 配置是占位符字面量：启动失败（Boot 4 绑定路径的实测坑）")
    void aiPlaceholderLiteral_fails() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        givenRedisPong();
        // 按检查顺序按需 stub：baseUrl 先过，apiKey 是字面量即抛——
        // model / embeddingModel 在抛错点之后，不会被读取，不 stub（严格模式下避免 UnnecessaryStubbing）
        when(aiProperties.getBaseUrl()).thenReturn("https://api.example.com");
        // 模拟「环境变量没注入、占位符被字面化传入」的实测形态
        when(aiProperties.getApiKey()).thenReturn("${AI_API_KEY}");

        IllegalStateException e = assertThrows(IllegalStateException.class, selfCheck::checkOnStartup);
        assertTrue(e.getMessage().contains("AI_API_KEY"), "错误信息应指出缺哪个变量：" + e.getMessage());
        assertTrue(e.getMessage().contains("占位符"), e.getMessage());
    }

    @Test
    @DisplayName("AI 留空（本地开发合法态）：不拦——空串不是字面量")
    void aiBlank_notBlocked() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        givenRedisPong();
        // 本地不配 AI：全局默认值是空串，这是刻意保留的容错（调用时才报 C0200）
        when(aiProperties.getBaseUrl()).thenReturn("");
        when(aiProperties.getApiKey()).thenReturn(null);
        when(aiProperties.getModel()).thenReturn("deepseek-chat");
        when(aiProperties.getEmbeddingModel()).thenReturn("text-embedding-3-small");

        assertDoesNotThrow(selfCheck::checkOnStartup);
    }

    @Test
    @DisplayName("PING 未返回 PONG：判失败（不把「连上了但行为异常」当通过）")
    void redisPingNotPong_fails() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<Object> callback = invocation.getArgument(0);
            RedisConnection connection = org.mockito.Mockito.mock(RedisConnection.class);
            when(connection.ping()).thenReturn("WEIRD");
            return callback.doInRedis(connection);
        });

        assertThrows(IllegalStateException.class, selfCheck::checkOnStartup);
    }
}
