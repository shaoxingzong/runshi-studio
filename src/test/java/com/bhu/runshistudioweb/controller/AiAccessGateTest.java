package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.AiQueryCountConstant;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AI 访问门槛验收测试
 *
 * author: shaoshing
 *
 * <p><b>验收的规则</b>：
 * <ul>
 *     <li>游客（未登录）<b>不能</b>使用 AI 服务，提问返回 A0201；</li>
 *     <li>普通用户：可提问 {@code studio.ai.query-limit} 次（本类配为 5），超出返回 A0501；</li>
 *     <li>成员与管理员：<b>不限额</b>，且不计数。</li>
 * </ul>
 *
 * <p>AI 调用用本地 Stub（JDK {@link HttpServer}）代替真实服务商，
 * 通过 {@code @DynamicPropertySource} 把 {@code studio.ai.base-url} 指过来。
 *
 * <p>类名以 {@code Ai} 开头：本类新建 Spring 测试上下文，排序约束见 AiKnowledgeManageTest 类注释。
 *
 * <p>本类不加 {@code @Transactional}：配额计数走 Redis INCR（外部系统），
 * 数据库事务回滚对它无效，因此数据真实提交、由 {@link #cleanUp()} 按记录的用户 id 清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiAccessGateTest {

    private static final String ACCOUNT_PREFIX = "T31gate_";

    /** 普通用户配额上限（与 DynamicPropertySource 配的 query-limit 保持一致） */
    private static final int USER_QUOTA = 5;

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /** 本类创建的用户 ID，用于精确清理 */
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
        registry.add("studio.ai.query-limit", () -> USER_QUOTA);
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

    // ==================== 访问门槛 ====================

    @Test
    @DisplayName("游客不可用：匿名提问一律 A0201")
    void anonymousIsRejected() throws Exception {
        assertEquals("A0201", code(ask(null, "T31gate-匿名")), "匿名提问应 A0201（未登录）");
    }

    @Test
    @DisplayName("普通用户：可提问 5 次，第 6 次 A0501")
    void normalUserHasFiveQuota() throws Exception {
        String token = loginWithRole(UserRoleConstant.USER);
        for (int i = 1; i <= USER_QUOTA; i++) {
            assertEquals("00000", code(ask(token, "T31gate-普通-" + i)), "第 " + i + " 次应成功");
        }
        String blocked = ask(token, "T31gate-普通-" + (USER_QUOTA + 1));
        assertEquals("A0501", code(blocked), "超出配额应 A0501：" + blocked);
    }

    @Test
    @DisplayName("成员：不限额，连续 7 次提问全部成功")
    void memberIsUnlimited() throws Exception {
        String token = loginWithRole(UserRoleConstant.MEMBER);
        for (int i = 1; i <= USER_QUOTA + 2; i++) {
            assertEquals("00000", code(ask(token, "T31gate-成员-" + i)),
                    "成员第 " + i + " 次应成功（不限额）");
        }
    }

    @Test
    @DisplayName("管理员：不限额，连续 7 次提问全部成功")
    void adminIsUnlimited() throws Exception {
        String token = loginWithRole(UserRoleConstant.ADMIN);
        for (int i = 1; i <= USER_QUOTA + 2; i++) {
            assertEquals("00000", code(ask(token, "T31gate-管理员-" + i)),
                    "管理员第 " + i + " 次应成功（不限额）");
        }
    }

    // ==================== 工具 ====================

    private String ask(String token, String message) throws Exception {
        var request = post("/ai/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}");
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String code(String body) throws Exception {
        return jsonMapper.readTree(body).get("code").asText();
    }

    /** 注册指定角色的用户并登录，返回 token（用户 id 记入 createdUserIds 供清理） */
    private String loginWithRole(String role) throws Exception {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        createdUserIds.add(user.getId());

        String body = mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 清理本类产生的数据：先删 Redis 计数 key，再按 userId 清会话/消息/用户 */
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
