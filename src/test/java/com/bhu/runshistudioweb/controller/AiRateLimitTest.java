package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.AiRateLimitConstant;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 游客 AI 提问的 IP 级限流验收测试
 *
 * author: shaoshing
 *
 * <p><b>为什么本类要用 {@code @DynamicPropertySource} 把开关覆盖为 {@code true}</b>：
 * pom 的 surefire 里已经把 {@code studio.ai.guest-ip-limits-enabled} 置为 {@code false}
 * （否则既有匿名用例会在跑到第 6 个时集体 A0501——那是假失败）。
 * 而 {@code @DynamicPropertySource} 注册的属性源优先级<b>高于</b>系统属性，
 * 因此本类可以在不影响其它测试类的前提下单独打开限流。
 *
 * <p><b>关于「窗口过期」的验证方式</b>：真实等一分钟太慢（会让整套测试变慢，
 * 慢到没人愿意跑）。这里用「删除当前分钟键」来等价模拟窗口翻页——
 * 限流的正确性体现在「键带时间后缀 + TTL」，这两点由常量与 Lua 脚本保证，
 * 时间是否真的流逝不影响逻辑。
 *
 * <p>类名以 {@code Ai} 开头：本类新建 Spring 测试上下文，排序约束见 AiKnowledgeManageTest 类注释。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiRateLimitTest {

    /** MockMvc 请求的来源 IP（默认就是它，限流键按它聚合） */
    private static final String TEST_IP = "127.0.0.1";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    private final java.util.List<Long> createdUserIds = new CopyOnWriteArrayList<>();

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
        // 覆盖 surefire 里的 false：本类就是要验证限流
        registry.add("studio.ai.guest-ip-limits-enabled", () -> "true");
        // 分钟 5（贴近生产口径 5/分钟）、日 50：
        // 用例 1 验证分钟窗口（第 6 次被拦），用例 2 用「直接把日键压到上限」验证日窗口——
        // 后者不需要真的连发 50 次，否则测试会跑很久且必然先撞分钟窗口
        registry.add("studio.ai.guest-ip-minute-limit", () -> "5");
        registry.add("studio.ai.guest-ip-daily-limit", () -> "50");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void resetRateKeys() {
        clearRateKeys();
    }

    @Test
    @DisplayName("AC1：分钟窗口——第 6 次被限，窗口翻页后恢复")
    void minuteWindowBlocksAfterLimitAndRecovers() throws Exception {
        // 分钟上限 5：前 5 次放行
        for (int i = 0; i < 5; i++) {
            assertEquals("00000", code(askAnonymous("T30-min-" + i)), "第 " + (i + 1) + " 次应放行");
        }
        String blocked = askAnonymous("T30-min-第6次");
        assertEquals("A0501", code(blocked), "超过分钟上限应 A0501：" + blocked);
        assertTrue(message(blocked).contains("登录"), "提示应引导登录：" + blocked);

        // 模拟窗口翻页：删掉当前分钟的键
        clearRateKeys();
        assertEquals("00000", code(askAnonymous("T30-min-翻页后")), "窗口翻页后应恢复");
    }

    @Test
    @DisplayName("AC2：日窗口——当日累计达上限即被限（不必真的连发 50 次）")
    void dailyWindowBlocksAfterLimit() throws Exception {
        // 把日键直接压到 50（daily 上限）：此刻提问必然触发日窗口。
        // 分钟计数才第 1 次，因此这一定是**日窗口**拦下的，不是分钟窗口
        stringRedisTemplate.opsForValue().set(
                AiRateLimitConstant.dayKey(TEST_IP, AiRateLimitConstant.currentDay()), "50");

        String blocked = askAnonymous("T30-day-超限");
        assertEquals("A0501", code(blocked), "当日累计达上限应 A0501：" + blocked);
        assertTrue(message(blocked).contains("每天"), "提示应说明是哪个窗口超限：" + blocked);
    }

    @Test
    @DisplayName("AC3：登录用户不受 IP 限流（走用户配额）")
    void loggedInUserIsNotRateLimitedByIp() throws Exception {
        // 先把日键压到远超上限：同一 IP 的游客此刻必然被限
        stringRedisTemplate.opsForValue().set(
                AiRateLimitConstant.dayKey(TEST_IP, AiRateLimitConstant.currentDay()), "999");
        assertEquals("A0501", code(askAnonymous("T30-游客被限")), "游客此时应超限");

        long userId = createUser();
        String token = login(userId);
        assertEquals("00000", code(postJson("/ai/chat", "{\"message\":\"T30-登录用户\"}", token)),
                "登录用户走用户配额，不应被 IP 限流");
    }

    // ==================== 工具 ====================

    private String askAnonymous(String message) throws Exception {
        return postJson("/ai/chat", "{\"message\":\"" + message + "\"}", null);
    }

    private String postJson(String path, String json, String tokenValue) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String code(String body) throws Exception {
        return jsonMapper.readTree(body).get("code").asText();
    }

    private String message(String body) throws Exception {
        return jsonMapper.readTree(body).get("message").asString();
    }

    /**
     * 清理本类产生的限流键
     *
     * <p>这里用 KEYS 而不是 SCAN：测试环境键数量极少，可读性与简洁优先；
     * 生产代码必须用 SCAN（见 AiQueryCountManager#scanKeys），切勿照抄此处。
     */
    private void clearRateKeys() {
        Set<String> keys = stringRedisTemplate.keys(AiRateLimitConstant.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private long createUser() {
        String account = "T30rl_" + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.USER);
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

    @AfterEach
    void cleanUp() {
        clearRateKeys();
        // 匿名提问会创建 user_id 为 NULL 的会话（不受 createdUserIds 覆盖）：
        // 按标题前缀清理（与 AiKnowledgeRetrievalTest 同一做法，先子后父）
        jdbcTemplate.update("DELETE FROM studio_ai_message WHERE session_id IN "
                + "(SELECT id FROM studio_ai_session WHERE user_id IS NULL AND title LIKE 'T30%')");
        jdbcTemplate.update("DELETE FROM studio_ai_session WHERE user_id IS NULL AND title LIKE 'T30%'");
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM studio_ai_message WHERE session_id IN "
                    + "(SELECT id FROM studio_ai_session WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM studio_ai_session WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }
}
