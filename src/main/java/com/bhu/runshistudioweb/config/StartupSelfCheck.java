package com.bhu.runshistudioweb.config;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;

// 启动自检：把「配置坏了但服务照样起」堵死在启动期
//
// 为什么需要它（2026-10-18 上线前验证实测发现）： application-prod.yml
// 的设计意图是「密钥类环境变量不给默认值，缺了就启动失败」。但实测三条全部落空——
// - 缺 MYSQL_PASSWORD：应用正常启动，Hikari 的连接失败没有冒泡到启动流程
// （Boot 4 数据源懒建连），第一个请求才 B0001；
// - 缺 REDIS_PASSWORD：同理，登录 / 限流 / 配额在运行时才炸；
// - 缺 AI 四项： @ConfigurationProperties 绑定路径把无法解析的占位符
// 传字面量（ "${AI_API_KEY}" 原文），AiManager 的 isBlank 检查被绕过，
// AI 客户端「初始化完成」但调用必然失败。
// 后果是同一类事故：运维看「启动成功」就以为配置齐全，实际带着坏配置上线，
// 故障在第一个真实用户请求时才暴露——那时排查成本是启动期的十倍。
//
// 自检什么、不自检什么：
// - 数据库连通： SELECT 1。Hikari 是懒连接，这里主动触发一次建连，
// 失败即启动失败——把 Hikari 的 fail-fast 从「第一次请求时」提前到「启动时」；
// - Redis 连通： PING。同理，Lettuce 也是懒连接；
// AI 配置形态：占位符字面量检测（值以  "${} 开头即未被解析）。
// 只查形态不查连通——AI 服务商临时不可用是运行时问题（熔断器的职责），
// 配置没注入是启动期问题，两者不能混为一谈；
//
// 为什么失败要抛异常而不是 warn：本类的全部价值就在于「让错误配置无法启动」。
// 降级为 warn 就等于没做—— warn 会被淹没在启动日志里，而服务带着坏配置照常服务。
// 本地开发同样受益：忘配数据库口令时立刻知道缺什么，而不是登录时看到 B0001 才反应过来。
//
// 与测试环境的兼容：MockMvc 上下文启动同样会执行本自检，而既有上下文测试
// 本来就要求 MySQL / Redis 可达（否则业务断言全挂），不会新增约束；
// AI 配置在测试里留空是刻意允许的本地态（空串不是字面量，不触发检测）。
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSelfCheck {

    // AI 相关配置项名：错误提示里列出，运维知道该注入哪几个变量
    private static final String[] AI_CONFIG_NAMES = { "AI_BASE_URL", "AI_API_KEY", "AI_MODEL", "AI_EMBEDDING_MODEL" };

    private final JdbcTemplate jdbcTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    private final AiProperties aiProperties;

    private final DataSource dataSource;

    @PostConstruct
    public void checkOnStartup() {
        checkDatabase();
        checkRedis();
        checkAiConfigShape();
        log.info("启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法");
    }

    // 数据库连通检查
    //
    // 拿 DataSource 的元数据只是为了失败时报出真实地址（排障时不用猜连的是哪个库），
    // 真正的连通判定是 SELECT 1——元数据能拿到不代表连接可用。
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

    // Redis 连通检查：Sa-Token 会话、限流、AI 配额全部依赖它，连不上时这些功能会在运行时成片失败
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

    // AI 配置形态检查：占位符字面量检测
    //
    // Boot 4 的 @ConfigurationProperties 绑定路径对无法解析的 ${VAR}
    // 会原样传入字面量（这是本类存在的直接原因，见类注释）。字面量以  "${} 开头，
    // 是明确的配置错误信号；而本地开发的合法形态是空串（不配 AI 也能起，调用时才报错），
    // 所以只拦字面量、不拦空值——本地容错语义不受影响。
    private void checkAiConfigShape() {
        requireNotPlaceholderLiteral(aiProperties.getBaseUrl(), "studio.ai.base-url", "AI_BASE_URL");
        requireNotPlaceholderLiteral(aiProperties.getApiKey(), "studio.ai.api-key", "AI_API_KEY");
        requireNotPlaceholderLiteral(aiProperties.getModel(), "studio.ai.model", "AI_MODEL");
        requireNotPlaceholderLiteral(aiProperties.getEmbeddingModel(), "studio.ai.embedding-model", "AI_EMBEDDING_MODEL");
    }

    private void requireNotPlaceholderLiteral(String value, String propertyKey, String envName) {
        if (StrUtil.isNotBlank(value) && value.trim().startsWith("${")) {
            throw new IllegalStateException(
                    "启动自检失败：AI 配置 " + propertyKey + " 的值是未解析的占位符字面量「" + value + "」，"
                            + "说明环境变量 " + envName + " 没有注入。请补齐 "
                            + String.join(" / ", AI_CONFIG_NAMES) + " 后重启（见 DEPLOY.md 第 3.1 节）");
        }
    }
}
