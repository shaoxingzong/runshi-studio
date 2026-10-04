package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.AiQueryCountConstant;
import com.bhu.runshistudioweb.manager.AiQueryCountManager;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AI 提问配额计数验收测试
 *
 * author: shaoshing
 *
 * <p><b>为什么本类不加 {@code @Transactional}</b>（改动前必读）：
 * 计数已经不走数据库事务了（Redis INCR 在事务外），加事务不但保护不了 Redis 数据，
 * 反而会让「DB 写入」与「Redis 写入」处在两套回滚语义下，断言变得没法解释。
 * 更关键的是：本类要验证的正是「DB 与 Redis 两个独立存储最终一致」，
 * 用事务把 DB 包起来就等于回避了这个真实边界。
 * 因此数据真实提交，靠 {@link #cleanUp()} 按账号前缀清理。
 *
 * <p><b>类名以 {@code Ai} 开头</b>：本类通过 {@code @DynamicPropertySource} 指向自己的
 * 本地 AI Stub，会新建一个 Spring 测试上下文；排序约束详见 AiKnowledgeManageTest 类注释
 * （新建上下文的测试类必须排在 CertificateCrudTest 之前）。
 *
 * <p><b>回刷周期被调成 1 小时</b>：否则 {@code @Scheduled} 会在用例执行途中
 * 把 Redis 里的增量清掉，让「提问后 Redis 应为 1」这种断言变成偶发失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiQueryCountRedisTest {

    private static final String ACCOUNT_PREFIX = "T29cnt_";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AiQueryCountManager aiQueryCountManager;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /** 本类创建的用户 ID，用于 @AfterEach 精确清理（避免误删其它数据） */
    private final List<Long> createdUserIds = new CopyOnWriteArrayList<>();

    private static HttpServer stub;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"stub-回答\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        stub.start();
    }

    @DynamicPropertySource
    static void stubProps(DynamicPropertyRegistry registry) {
        int port = stub.getAddress().getPort();
        registry.add("studio.ai.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("studio.ai.api-key", () -> "test-key");
        registry.add("studio.ai.model", () -> "test-model");
        // 配额上限：单用户 5 次（用例里会按需改写 DB/Redis 的值来制造边界）
        registry.add("studio.ai.query-limit", () -> 5);
        // 回刷周期调到 1 小时：让「Redis 增量」在断言期间不被定时任务清走
        registry.add(AiQueryCountConstant.FLUSH_DELAY_PROPERTY, () -> "3600000");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void prepare() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("AC1：提问后计数进 Redis，DB 基准不变")
    void incrementGoesToRedisAndLeavesDbUntouched() throws Exception {
        long userId = createUser();
        String token = login(userId);

        assertEquals(0, code(postJson("/ai/chat", "{\"message\":\"T29-第一次提问\"}", token)),
                "提问应成功");

        assertEquals(1, redisDelta(userId), "提问一次后 Redis 增量应为 1");
        assertEquals(0, dbCount(userId), "DB 基准值此时不应变化（要等回刷）");
    }

    @Test
    @DisplayName("AC2：回刷后 DB +n、Redis key 消失；重复回刷不重复计数")
    void flushWritesBackOnceAndIsIdempotent() throws Exception {
        long userId = createUser();
        String token = login(userId);
        postJson("/ai/chat", "{\"message\":\"T29-回刷用\"}", token);

        aiQueryCountManager.flushToDb();
        assertEquals(1, dbCount(userId), "回刷后 DB 应 +1");
        assertEquals(0, redisDelta(userId), "回刷后 Redis 增量应清零（GETDEL）");

        // 再跑一轮：没有增量了，绝不能重复累加
        aiQueryCountManager.flushToDb();
        assertEquals(1, dbCount(userId), "重复回刷不得重复计数");
    }

    @Test
    @DisplayName("AC3：预检用合并值（DB=limit-1 + Redis=1），不依赖回刷")
    void quotaPrecheckUsesMergedValueWithoutFlush() throws Exception {
        long userId = createUser();
        String token = login(userId);

        // 构造边界：DB 基准 4（limit=5 的前一位）+ Redis 增量 1 → 合并 5，已达上限
        jdbcTemplate.update("UPDATE sys_user SET ai_query_count = 4 WHERE id = ?", userId);
        stringRedisTemplate.opsForValue().set(AiQueryCountConstant.keyOf(userId), "1");

        String body = postJson("/ai/chat", "{\"message\":\"T29-超限\"}", token);
        assertEquals(42900, code(body), "合并值已达上限应返回 42900：" + body);
        // 关键：此时并没有发生回刷，DB 仍是 4 ——证明预检读的是合并值而不是只看 DB
        assertEquals(4, dbCount(userId), "本用例不应依赖回刷，DB 基准应保持 4");
        assertEquals(1, redisDelta(userId), "Redis 增量仍在那里");
    }

    @Test
    @DisplayName("AC4：并发 20 次自增精确为 20（INCR 原子），回刷后 DB 也是 20")
    void concurrentIncrementsAreExact() throws Exception {
        long userId = createUser();

        // 直接并发调用 Manager.increment，**刻意绕过 HTTP 层的配额预检**：
        // 配额预检是「读合并值 + 判断」，并发下按设计允许轻微超限/拒绝，
        // 用它来测并发会让断言取决于线程调度时机（偶发失败）。
        // 本用例要证明的是「计数自增在并发下不丢」——那正是 Manager.increment 的职责。
        int threads = 20;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    // 同时起跑：真正制造并发写 INCR 的窗口
                    start.await();
                    aiQueryCountManager.increment(userId);
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发自增未在 60s 内完成");
        pool.shutdown();
        assertTrue(errors.isEmpty(), "并发自增出现异常：" + errors);

        assertEquals(threads, redisDelta(userId), "并发 20 次自增，Redis 增量应精确为 20（INCR 原子）");
        assertEquals(0, dbCount(userId), "回刷前 DB 不应变化");

        aiQueryCountManager.flushToDb();
        assertEquals(threads, dbCount(userId), "回刷后 DB 应为 20");
        assertEquals(0, redisDelta(userId), "回刷后 Redis 应清零");
    }

    @Test
    @DisplayName("登录信息里的 aiQueryCount 是合并值（前端看到的与实际一致）")
    void loginInfoReturnsMergedValue() throws Exception {
        long userId = createUser();
        String token = login(userId);

        postJson("/ai/chat", "{\"message\":\"T29-看数字\"}", token);
        String body = mockMvc.perform(get("/user/current").header("satoken", token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, code(body), "查询当前用户应成功：" + body);
        int shown = jsonMapper.readTree(body).get("data").get("aiQueryCount").asInt();
        assertEquals(1, shown, "当前用户信息应返回合并值 1（DB 0 + Redis 1），实际：" + body);
    }

    @Test
    @DisplayName("AC5：回刷遇脏值不中断，正常 key 照常落库")
    void flushToleratesDirtyValueAndKeepsGoodKeys() throws Exception {
        long goodUser = createUser();
        long dirtyUser = createUser();

        // 正常增量 2
        stringRedisTemplate.opsForValue().set(AiQueryCountConstant.keyOf(goodUser), "2");
        // 脏值：不是整数（被人手工写坏 / 序列化异常都可能造成）
        stringRedisTemplate.opsForValue().set(AiQueryCountConstant.keyOf(dirtyUser), "not-a-number");

        // 整轮回刷不应因为一个脏 key 而中断
        aiQueryCountManager.flushToDb();

        assertEquals(2, dbCount(goodUser), "正常 key 应照常落库为 2");
        assertEquals(0, redisDelta(goodUser), "落库后正常 key 应清零");
        assertEquals(0, dbCount(dirtyUser), "脏值不应被计入 DB");
        assertEquals(0, redisDelta(dirtyUser), "脏值 key 已被 GETDEL 删除，不会下轮反复处理");
    }

    // ==================== 工具 ====================

    private long createUser() {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(com.bhu.runshistudioweb.model.enums.UserRoleEnum.USER.getValue());
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        createdUserIds.add(user.getId());
        return user.getId();
    }

    private String login(long userId) throws Exception {
        String account = jdbcTemplate.queryForObject(
                "SELECT user_account FROM sys_user WHERE id = ?", String.class, userId);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    private String postJson(String path, String json, String tokenValue) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private int code(String body) throws Exception {
        return jsonMapper.readTree(body).get("code").asInt();
    }

    private int redisDelta(long userId) {
        String value = stringRedisTemplate.opsForValue().get(AiQueryCountConstant.keyOf(userId));
        return value == null ? 0 : Integer.parseInt(value.trim());
    }

    private int dbCount(long userId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT ai_query_count FROM sys_user WHERE id = ?", Integer.class, userId);
        return value == null ? 0 : value;
    }

    /**
     * 清理本类创建的数据（真实提交，必须自己收尾）
     *
     * <p>只按「记录下来的 userId」精确删除，绝不按前缀批量删 Redis——
     * 后者会清掉其它用户在测试期间产生的计数。
     */
    private void cleanUp() {
        for (Long userId : createdUserIds) {
            stringRedisTemplate.delete(AiQueryCountConstant.keyOf(userId));
            jdbcTemplate.update("DELETE FROM studio_ai_message WHERE session_id IN "
                    + "(SELECT id FROM studio_ai_session WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM studio_ai_session WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }
}
