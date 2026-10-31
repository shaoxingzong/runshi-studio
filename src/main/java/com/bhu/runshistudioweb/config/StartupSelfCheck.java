package com.bhu.runshistudioweb.config;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;

/**
 * 启动自检：把「配置坏了但服务照样起得来」这类问题堵死在启动期
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSelfCheck {

    /** AI 相关配置项名：错误提示里列出，运维知道该注入哪几个变量 */
    private static final String[] AI_CONFIG_NAMES = { "AI_BASE_URL", "AI_API_KEY", "AI_MODEL", "AI_EMBEDDING_MODEL" };

    private final JdbcTemplate jdbcTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    private final AiProperties aiProperties;

    private final DataSource dataSource;

    private final Environment environment;

    /**
     * 启动期自检入口：按「先连通性、后配置形态」的顺序检查，任一不通过就中断启动
     *
     * @throws IllegalStateException 数据库或 Redis 不可用、AI 配置是未解析的占位符时抛出；该异常由 Spring 包装后中断启动，不会被全局异常处理器接住
     */
    @PostConstruct
    public void checkOnStartup() {
        checkDatabase();
        checkRedis();
        checkAiConfigShape();
        log.info("启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法");
        logRequestThreadMode();
    }

    /** 打印请求处理的线程模式 */
    private void logRequestThreadMode() {
        // 取不到（属性未配置）按 false 处理；用 TRUE.equals 而非直接拆箱，null 不会 NPE
        boolean virtual = Boolean.TRUE.equals(
                environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false));
        log.info("请求处理线程模式：{}（spring.threads.virtual.enabled={}）",
                virtual ? "虚拟线程（一请求一线程，I/O 等待型负载的并发手段）" : "平台线程池（Tomcat 默认 200）",
                virtual);
    }

    /** 数据库连通检查：主动执行一次 SELECT 1 */
    private void checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException e) {
            String url;
            try {
                url = dataSource.getConnection().getMetaData().getURL();
            } catch (Exception ignored) {
                // 连接都拿不到时 URL 也无从谈起，用占位符即可——真正的错误信息在 e 里
                url = "(无法获取连接，详见下方异常)";
            }
            throw new IllegalStateException(
                    "启动自检失败：数据库不可用（" + url + "）。"
                            + "请检查 MYSQL_URL / MYSQL_USERNAME / MYSQL_PASSWORD 是否注入、MySQL 是否可达、"
                            + "账号是否有 studio_db 的权限（初始化见 db/init-prod.sql）", e);
        }
    }

    /** Redis 连通检查：主动执行一次 PING */
    private void checkRedis() {
        try {
            // RedisCallback 形式对所有 Redis 驱动通用（Lettuce / Jedis 都支持 ping）
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                String pong = connection.ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    throw new IllegalStateException("PING 未返回 PONG: " + pong);
                }
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "启动自检失败：Redis 不可用。请检查 REDIS_PASSWORD 是否注入、Redis 是否可达；"
                            + "注意 Sa-Token 需要 Redis 6.2+（KEEPTTL / GETDEL），低版本登录接口会直接 500", e);
        }
    }

    /** AI 配置形态检查：识别「没被解析的占位符字面量」 */
    private void checkAiConfigShape() {
        requireNotPlaceholderLiteral(aiProperties.getBaseUrl(), "studio.ai.base-url", "AI_BASE_URL");
        requireNotPlaceholderLiteral(aiProperties.getApiKey(), "studio.ai.api-key", "AI_API_KEY");
        requireNotPlaceholderLiteral(aiProperties.getModel(), "studio.ai.model", "AI_MODEL");
        requireNotPlaceholderLiteral(aiProperties.getEmbeddingModel(), "studio.ai.embedding-model", "AI_EMBEDDING_MODEL");
    }

    /** 检查单个配置值是不是未解析的占位符，是则中断启动 */
    private void requireNotPlaceholderLiteral(String value, String propertyKey, String envName) {
        if (StrUtil.isNotBlank(value) && value.trim().startsWith("${")) {
            throw new IllegalStateException(
                    "启动自检失败：AI 配置 " + propertyKey + " 的值是未解析的占位符字面量「" + value + "」，"
                            + "说明环境变量 " + envName + " 没有注入。请补齐 "
                            + String.join(" / ", AI_CONFIG_NAMES) + " 后重启（见 DEPLOY.md 第 3.1 节）");
        }
    }
}
