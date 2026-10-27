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
 * 缺数据库或 Redis 口令时应用照常启动（连接池懒建连，失败不冒泡到启动流程）；缺 AI 配置时
 * 属性绑定会把无法解析的占位符原样当字面量传进来，于是「为空就不调用」的判断被绕过。
 * 后果相同：运维看到「启动成功」就以为配置齐全，故障在第一个真实请求时才暴露。
 *
 * 只查连通性与配置形态，不查 AI 服务是否可用——服务商临时不可用是运行时问题（归熔断器）。
 * 失败必须抛异常而不是打 warn：warn 会被淹没在启动日志里，而服务带着坏配置照常对外服务。
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
     * 用 {@code @PostConstruct} 是刻意的：比它早拿不到注入好的 JdbcTemplate；比它晚
     * （例如 ApplicationReadyEvent）Web 端口已开始监听，检查失败时可能已经有请求进来了。
     * 数据库排第一位，因为绝大多数业务功能依赖它；Redis 的失败表现与数据库故障很像，必须分开报。
     *
     * 坑：抛出的异常会让整个应用启动失败，真正的原因藏在堆栈的 cause 里，
     * 所以每个检查都把「缺哪个变量、去哪里初始化」写进了异常消息。
     *
     * @throws IllegalStateException 数据库或 Redis 不可用、AI 配置是未解析的占位符时抛出；
     * 该异常由 Spring 包装后中断启动，不会被全局异常处理器接住
     */
    @PostConstruct
    public void checkOnStartup() {
        checkDatabase();
        checkRedis();
        checkAiConfigShape();
        log.info("启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法");
        logRequestThreadMode();
    }

    /**
     * 打印请求处理的线程模式
     *
     * 「配置写了」与「配置生效了」是两件事，而 Tomcat 更换执行器发生在启动早期且不留日志。
     * 这里读一下配置打一行，运维在启动日志里就能确认。
     *
     * 坑：用 {@code Boolean.TRUE.equals(...)} 而不是直接拆箱，属性缺失时不会空指针——
     * 一个打印日志的辅助方法把整个启动搞挂是不能接受的。另外它只反映配置值，
     * 不校验执行器是否真的换了；想确认后者要看线程名（虚拟线程不带 pool 前缀）。
     */
    private void logRequestThreadMode() {
        // 取不到（属性未配置）按 false 处理；用 TRUE.equals 而非直接拆箱，null 不会 NPE
        boolean virtual = Boolean.TRUE.equals(
                environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false));
        log.info("请求处理线程模式：{}（spring.threads.virtual.enabled={}）",
                virtual ? "虚拟线程（一请求一线程，I/O 等待型负载的并发手段）" : "平台线程池（Tomcat 默认 200）",
                virtual);
    }

    /**
     * 数据库连通检查：主动执行一次 SELECT 1
     *
     * 数据源创建成功不代表数据库可达——它只是一个「知道地址和口令的工厂」，不建立物理连接。
     * 这里执行一条最轻的查询，逼连接池真的建一次连接，把懒连接的失败点提前到启动期。
     * 不查业务表，避免「表还没建」被误判成「数据库连不上」。
     *
     * 取 URL 的那次尝试单独吞掉异常：走到这里连接本来就不可用，再抛一次会把真正的
     * 错误信息覆盖掉，排查时看到的是一个和问题无关的异常。
     */
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

    /**
     * Redis 连通检查：主动执行一次 PING
     *
     * 单独查这一项：Sa-Token 会话、接口限流、AI 配额全依赖 Redis，它连不上时登录会报系统错误，
     * 现象与数据库故障几乎一样，但修复方向完全不同。
     *
     * 坑：catch 的是 Exception 而不是某个具体 Redis 异常——连不上、认证失败、超时、驱动内部错误，
     * 对使用者都是「Redis 不可用」这一件事。原始异常会作为 cause 一并带出去。
     */
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

    /**
     * AI 配置形态检查：识别「没被解析的占位符字面量」
     *
     * 属性绑定遇到无法解析的 ${VAR} 时不报错，而是把这段文本原样当成配置值，
     * 于是「配置为空就不调用」的判断被绕过——值不是空，是 "${AI_API_KEY}" 这个字符串。
     * 检查形态是唯一能在启动期发现它的办法。
     *
     * 只拦字面量，不拦空值：本地不配 AI 是完全合法的形态。
     */
    private void checkAiConfigShape() {
        requireNotPlaceholderLiteral(aiProperties.getBaseUrl(), "studio.ai.base-url", "AI_BASE_URL");
        requireNotPlaceholderLiteral(aiProperties.getApiKey(), "studio.ai.api-key", "AI_API_KEY");
        requireNotPlaceholderLiteral(aiProperties.getModel(), "studio.ai.model", "AI_MODEL");
        requireNotPlaceholderLiteral(aiProperties.getEmbeddingModel(), "studio.ai.embedding-model", "AI_EMBEDDING_MODEL");
    }

    /**
     * 检查单个配置值是不是未解析的占位符，是则中断启动
     *
     * 判定条件是「非空且以 ${ 开头」两个条件同时成立，才等价于「这个值本该被解析掉，但没有」。
     *
     * 坑：用「以 ${ 开头」而不是「包含 ${」。正常的配置值理论上不该包含占位符，
     * 但用「包含」会把某些合法值（比如提示词模板里恰好写了 ${...}）误判成配置错误。
     *
     * @param value 待检查的配置值，可为 null（视为未配置，放行）
     * @param propertyKey 配置项全名，用于报错时指出是哪一项
     * @param envName 对应的环境变量名，用于告诉运维该注入什么
     * @throws IllegalStateException 值非空且以占位符形式开头时抛出
     */
    private void requireNotPlaceholderLiteral(String value, String propertyKey, String envName) {
        if (StrUtil.isNotBlank(value) && value.trim().startsWith("${")) {
            throw new IllegalStateException(
                    "启动自检失败：AI 配置 " + propertyKey + " 的值是未解析的占位符字面量「" + value + "」，"
                            + "说明环境变量 " + envName + " 没有注入。请补齐 "
                            + String.join(" / ", AI_CONFIG_NAMES) + " 后重启（见 DEPLOY.md 第 3.1 节）");
        }
    }
}
