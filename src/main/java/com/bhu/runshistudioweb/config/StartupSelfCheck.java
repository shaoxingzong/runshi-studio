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
 * <p>为什么需要它：application-prod.yml 的设计意图是「密钥类环境变量不给默认值，缺了就启动失败」。
 * 但 Spring 的实际行为让这个意图三处落空：
 * <ul>
 *     <li>缺数据库口令：应用正常启动。连接池是懒建连的，第一次真正需要连接才去连，
 *     失败也不会冒泡到启动流程，于是第一个用户请求才收到系统错误；</li>
 *     <li>缺 Redis 口令：同理，登录、限流、配额这些依赖 Redis 的功能在运行时才炸；</li>
 *     <li>缺 AI 四项配置：属性绑定会把无法解析的占位符原样当字面量传进来
 *     （即 "${AI_API_KEY}" 这一串文本本身），于是调用方那句「为空就不调用」的判断被绕过，
 *     AI 客户端看起来「初始化完成」，但每次调用必然失败。</li>
 * </ul>
 * 三者后果相同：运维看到「启动成功」就以为配置齐全，带着坏配置上线，
 * 故障在第一个真实用户请求时才暴露——那时的排查成本远高于启动期。
 *
 * <p>自检什么、不自检什么：
 * <ul>
 *     <li>数据库连通：执行 SELECT 1。连接池是懒连接，这里主动触发一次建连，
 *     失败即启动失败——把「第一次请求时才失败」提前到「启动时失败」；</li>
 *     <li>Redis 连通：执行 PING。同理，Redis 客户端也是懒连接；</li>
 *     <li>AI 配置形态：只做占位符字面量检测（值以 "${" 开头即说明没被解析）。
 *     只查形态、不查连通——服务商临时不可用是运行时问题（由熔断器负责），
 *     配置没注入是启动期问题，两者混在一起会让启动变得脆弱且不可预测。</li>
 * </ul>
 *
 * <p>为什么失败要抛异常而不是打 warn：本类的全部价值就在于「让错误配置无法启动」。
 * 降级为 warn 等于没做——warn 会被淹没在启动日志里，而服务带着坏配置照常对外服务。
 * 本地开发同样受益：忘配数据库口令时立刻知道缺什么，而不是等到登录时才看到一个笼统的错误码。
 *
 * <p>与测试环境的兼容：接口测试同样会启动容器、也就同样执行本自检，
 * 而那些测试本来就要求 MySQL / Redis 可达，不会新增约束；
 * AI 配置在测试里留空是允许的（空串不是字面量，不触发检测）。
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
     * <p>为什么这样设计：@PostConstruct 标在方法上，意味着它在「本 Bean 的依赖都注入完成、
     * 但整个容器还没启动完毕」时执行。选这个时机是刻意的——比它早，拿不到注入好的
     * JdbcTemplate；比它晚（例如 ApplicationReadyEvent），Web 端口已经开始监听，
     * 检查失败时可能已经有请求进来了。要在「能检查」和「还没对外服务」之间取交集，
     * @PostConstruct 是合适的位置。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>检查数据库连通：放在第一位，因为绝大多数业务功能都依赖它，
     *     连不上时后面所有检查都没有意义；</li>
     *     <li>检查 Redis 连通：会话、限流、配额全靠它，且它的失败表现
     *     （登录接口报系统错误）与数据库故障很像，必须分开报；</li>
     *     <li>检查 AI 配置形态：只验证「配置有没有真的注入」，不发起网络请求；</li>
     *     <li>打印通过日志：这行日志本身就是排障依据，看到它就能排除掉一大类配置问题；</li>
     *     <li>打印请求处理的线程模式：它不是检查项，只是一条运维需要的信息（见下个方法）。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法抛出的异常会让整个应用启动失败，日志里通常只看到
     * 「Application run failed」加一长串堆栈，真正的原因藏在 cause 里。
     * 所以下面每个检查都把「缺哪个变量、去哪里初始化」写进了异常消息——
     * 这条消息是写给半夜被叫起来的人看的，不是写给编译器看的。
     *
     * @throws IllegalStateException 数据库或 Redis 不可用、AI 配置是未解析的占位符时抛出；
     *         该异常由 Spring 包装后中断启动，不会被全局异常处理器接住
     *         （那时容器还没起来，全局处理器也没生效）
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
     * <p>为什么需要它：虚拟线程是本项目在低配服务器上的关键并发手段（面向 I/O 等待型负载），
     * 但「配置写了」和「配置生效了」是两件事——Tomcat 更换执行器发生在启动早期，
     * 且不会留下任何显式日志。这里读一下配置状态打一行，
     * 运维在启动日志里就能确认，不必去翻框架的调试日志或连上机器用工具查。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>读取配置并转成布尔值：取不到（属性未配置）时按 false 处理；</li>
     *     <li>按结果打印两种模式之一的说明，让日志本身就能自解释。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用 Boolean.TRUE.equals(...) 而不是直接对结果拆箱，
     * 是为了避开 null——当属性缺失且没有给默认值时，拆箱会抛空指针，
     * 一个「打印日志」的辅助方法把整个启动搞挂，是很难接受的。
     * 另外它只反映配置值，不校验执行器是否真的换成了虚拟线程；
     * 想确认后者要看线程名（虚拟线程的名字不带 pool 前缀）。
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
     * <p>为什么不能只靠「数据源 Bean 创建成功」：数据源在创建时并不建立物理连接，
     * 它只是一个「知道地址和口令的工厂」。所以 Bean 注入成功完全不代表数据库可达。
     * 这里执行一条最轻的查询，逼连接池真的建一次连接，把懒连接的失败点提前到启动期。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>执行 SELECT 1：不查任何业务表，避免因为「表还没建」被误判成「数据库连不上」；</li>
     *     <li>失败时再尝试取一次连接元数据里的 URL：这不是判定手段，纯粹是为了报错时
     *     能带上真实地址，省去排障时猜「连的到底是哪个库」；</li>
     *     <li>抛出带排查指引的异常，中断启动。</li>
     * </ol>
     *
     * <p>容易踩的坑：取 URL 的那次尝试单独包了 try-catch 并吞掉异常——
     * 因为走到这里说明连接本来就不可用，再取一次很可能同样失败。
     * 如果让它把异常抛出去，真正的错误信息（连不上）就会被「取 URL 失败」覆盖，
     * 排查时看到的是一个和问题无关的异常。
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
     * <p>为什么单独查这一项：Sa-Token 的会话、接口限流、AI 配额全部依赖 Redis。
     * 它连不上时，登录接口会报系统错误——现象和数据库故障几乎一样，
     * 但两者的修复方向完全不同。启动期就把它区分出来，能省掉一次误判。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>用回调形式执行 PING：回调形式对所有 Redis 驱动通用，换驱动时不用改代码；</li>
     *     <li>校验返回的确实是 PONG：不校验的话，任何「命令发出去了但服务端行为异常」的情况
     *     都会被当成连接成功；</li>
     *     <li>任何异常都包装成带指引的启动失败。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的 catch 是 Exception 而不是某个具体的 Redis 异常类型。
     * 刻意的——连不上、认证失败、超时、驱动内部错误，对使用者来说都是
     * 「Redis 不可用」这一件事，没有必要在启动期区分得那么细；
     * 原始异常会作为 cause 一并带出去，需要细节时仍然看得到。
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
     * <p>为什么需要它：属性绑定遇到无法解析的 ${VAR} 时不会报错，
     * 而是把这段文本原样当成配置值。于是调用方那句「配置为空就不调用」的判断被绕过——
     * 值不是空，是 "${AI_API_KEY}" 这个字符串。检查形态是唯一能在启动期发现它的办法，
     * 因为此时还没有任何一次真实的 AI 调用。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个检查四项 AI 配置：地址、密钥、对话模型、向量模型。
     *     四项各自独立，缺任何一项都会让某个功能不可用；</li>
     *     <li>任一项命中占位符字面量即抛出异常，中断启动。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只拦「字面量」，不拦空值。
     * 本地开发不配 AI 是完全合法的形态（应用要能启动，只有调用 AI 时才报错），
     * 若把空值也拦掉，本地开发就必须先申请密钥才能启动，这是本末倒置。
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
     * <p>为什么这样设计：判定条件必须是「非空 且 以 ${ 开头」两个条件同时成立。
     * 少了非空判断，空串会走到 trim 后判断、逻辑上没问题但语义不清；
     * 少了 ${ 判断，任何正常值都会被误伤。两个条件一起才等价于
     * 「这个值本该被解析掉，但没有」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先判非空：空值代表「本地没配」，是允许的形态，直接放行；</li>
     *     <li>trim 后判断是否以 ${ 开头：占位符解析失败时留下的就是这段文本本身；</li>
     *     <li>命中则抛出异常，消息里写清「哪个配置项、哪个环境变量、去哪里看部署说明」。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的判断是「以 ${ 开头」而不是「包含 ${」。
     * 一个正常的配置值理论上不该包含占位符，但用「包含」判断会把
     * 某些合法值（比如提示词模板里恰好写了 ${...}）误判成配置错误。
     * 解析失败的特征是「整个值就是占位符」，所以用开头匹配更准。
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
