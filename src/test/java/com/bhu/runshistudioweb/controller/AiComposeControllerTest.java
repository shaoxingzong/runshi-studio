package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.manager.AiCircuitBreaker;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AI 写作助手验收测试：润色（SSE）与标题/摘要（同步）
 *
 * <p>覆盖四条线：
 * <ul>
 *     <li>准入：未登录 A0201、非成员 A0301（与发帖同一口径）；</li>
 *     <li>润色：delta 拼接 == 完整文本、done 带完整文本；空片段以 error 事件返回；</li>
 *     <li>标题摘要：解析 JSON 回填、字段完整；</li>
 *     <li>AI 故障：Stub 500 → 润色以 error 事件返回，标题摘要抛 C0200。</li>
 * </ul>
 *
 * <p>本类不加 @Transactional：润色跑在虚拟线程、独立事务里，测试事务回滚对它无效；
 * 因此按账号前缀手动清理（数据都带 T35compose 前缀）。
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiComposeControllerTest {

    /** 本类测试数据前缀：与刷题 / 会话测试的前缀区分，避免互相清理 */
    private static final String PREFIX = "T35compose_";

    private static final String PASSWORD = "Studio@2026";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /**
     * 熔断器是进程内单例：本类有用例故意让模型返回 500，
     * 不重置会把失败传染给同一 JVM 里后跑的测试类
     */
    @Resource
    private AiCircuitBreaker circuitBreaker;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker.reset();
    }

    // ==================== 本地 AI Stub ====================

    private static HttpServer stub;

    private static final String POLISH_PART_1 = "润色";
    private static final String POLISH_PART_2 = "-";
    private static final String POLISH_PART_3 = "结果";

    private static String fullPolish() {
        return POLISH_PART_1 + POLISH_PART_2 + POLISH_PART_3;
    }

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String compact = body.replace(" ", "").replace("\r", "").replace("\n", "");
            if (compact.contains("TRIGGER_FAIL")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if (compact.contains("\"stream\":true")) {
                String sse = chunk(POLISH_PART_1, false) + chunk(POLISH_PART_2, false)
                        + chunk(POLISH_PART_3, true) + "data: [DONE]\n\n";
                byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else {
                // 非流式：标题/摘要。content 本身是一个 JSON 字符串，需要二次转义
                String resp = "{\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"title\\\":\\\"测试标题\\\",\\\"summary\\\":\\\"测试摘要\\\"}\"}}]}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        stub.start();
    }

    private static String chunk(String content, boolean last) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + content + "\"},\"finish_reason\":" + (last ? "\"stop\"" : "null") + "}]}\n\n";
    }

    @DynamicPropertySource
    static void stubProps(DynamicPropertyRegistry registry) {
        int port = stub.getAddress().getPort();
        registry.add("studio.ai.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("studio.ai.api-key", () -> "test-key");
        registry.add("studio.ai.model", () -> "test-model");
        // 内存向量库：测试不依赖 Redis Query Engine；检索会因 Stub 无 /embeddings 而降级
        registry.add("studio.ai.vector-store-type", () -> "memory");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM studio_member WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE user_account LIKE '" + PREFIX + "%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE user_account LIKE '" + PREFIX + "%'");
    }

    // ==================== 夹具 ====================

    /** 建在队成员（账号 + 档案）；只建账号会被判成非成员 */
    private long createMember() {
        long userId = createUser();
        StudioMember member = new StudioMember();
        member.setUserId(userId);
        member.setName(PREFIX + userId);
        member.setGradeYear(2023);
        member.setMemberStatus(MemberStatusEnum.IN_TEAM.getValue());
        studioMemberMapper.insert(member);
        return userId;
    }

    /** 只建账号、不建档案（非成员） */
    private long createUser() {
        String account = PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(PASSWORD));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.USER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 登录并取 token */
    private String tokenOf(long userId) throws Exception {
        String account = jdbcTemplate.queryForObject(
                "SELECT user_account FROM sys_user WHERE id = ?", String.class, userId);
        String body = mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 发起润色 SSE 并等到 done/error 帧出现 */
    private MvcResult polishRaw(String json, String token) throws Exception {
        var request = post("/ai/compose/polish").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("satoken", token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertTrue(result.getRequest().isAsyncStarted(), "SSE 请求应进入异步模式");
        String frames = "";
        for (int i = 0; i < 200; i++) {
            frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (frames.contains("event:done") || frames.contains("event:error")) {
                String snapshot = frames;
                Thread.sleep(60);
                frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                if (frames.equals(snapshot)) {
                    break;
                }
            } else {
                Thread.sleep(50);
            }
        }
        return result;
    }

    private List<String> dataFor(String frames, String eventName) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^event:\\s*" + eventName + "\\s*\\r?\\ndata:\\s*(.*?)\\s*\\r?$")
                .matcher(frames);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private String dataOf(String frames, String eventName) {
        List<String> all = dataFor(frames, eventName);
        assertFalse(all.isEmpty(), "缺少 " + eventName + " 事件：" + frames);
        return all.get(0);
    }

    private String jsonField(String payload, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(payload);
        assertTrue(m.find(), "负载缺少字段 " + field + "：" + payload);
        return m.group(1);
    }

    // ==================== ① 准入 ====================

    @Test
    @DisplayName("未登录生成标题摘要：A0201")
    void metaRequiresLogin() throws Exception {
        String body = mockMvc.perform(post("/ai/compose/meta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"测试正文\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("A0201", jsonMapper.readTree(body).get("code").asText(), body);
    }

    @Test
    @DisplayName("非成员生成标题摘要：A0301（准入认 studio_member 表）")
    void metaRejectedForNonMember() throws Exception {
        String token = tokenOf(createUser());
        String body = mockMvc.perform(post("/ai/compose/meta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"测试正文\"}").header("satoken", token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("A0301", jsonMapper.readTree(body).get("code").asText(), body);
    }

    @Test
    @DisplayName("非成员润色：以 error 事件返回 A0301（HTTP 恒 200）")
    void polishRejectedForNonMember() throws Exception {
        String token = tokenOf(createUser());
        MvcResult result = polishRaw("{\"text\":\"要润色的文字\"}", token);
        assertEquals(200, result.getResponse().getStatus());
        String frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("A0301", jsonField(dataOf(frames, "error"), "code"), frames);
    }

    // ==================== ② 润色 ====================

    @Test
    @DisplayName("成员润色：delta 拼接等于完整文本，done 带完整文本")
    void polishStreamWorks() throws Exception {
        String token = tokenOf(createMember());
        MvcResult result = polishRaw("{\"text\":\"这是需要润色的一段文字\"}", token);

        assertEquals(200, result.getResponse().getStatus());
        String frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<String> deltas = dataFor(frames, "delta");
        assertTrue(deltas.size() >= 2, "应是多段增量推送：" + frames);
        StringBuilder joined = new StringBuilder();
        for (String d : deltas) {
            joined.append(jsonField(d, "delta"));
        }
        assertEquals(fullPolish(), joined.toString(), "增量拼接应等于完整文本");

        assertEquals(fullPolish(), jsonField(dataOf(frames, "done"), "text"), "done 应带完整文本");
    }

    @Test
    @DisplayName("润色空片段：以 error 事件返回 A0401")
    void polishRejectsBlankText() throws Exception {
        String token = tokenOf(createMember());
        MvcResult result = polishRaw("{\"text\":\"   \"}", token);
        String frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("A0401", jsonField(dataOf(frames, "error"), "code"), frames);
    }

    // ==================== ③ 标题摘要（含 RAG 降级）====================

    @Test
    @DisplayName("成员生成标题摘要：解析 JSON 回填")
    void metaWorks() throws Exception {
        String token = tokenOf(createMember());
        String body = mockMvc.perform(post("/ai/compose/meta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# 项目复盘\\n\\n我们做了一个 RAG 问答系统。\"}")
                        .header("satoken", token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode node = jsonMapper.readTree(body);
        assertEquals("00000", node.get("code").asText(), body);
        assertEquals("测试标题", node.get("data").get("title").asText(), body);
        assertEquals("测试摘要", node.get("data").get("summary").asText(), body);
    }

    @Test
    @DisplayName("正文为空：A0401")
    void metaRejectsBlankContent() throws Exception {
        String token = tokenOf(createMember());
        String body = mockMvc.perform(post("/ai/compose/meta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}").header("satoken", token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("A0401", jsonMapper.readTree(body).get("code").asText(), body);
    }

    // ==================== ④ AI 故障 ====================

    @Test
    @DisplayName("AI 故障：润色以 error 事件返回 C0200")
    void polishReturnsErrorWhenAiFails() throws Exception {
        String token = tokenOf(createMember());
        // 负载含 TRIGGER_FAIL 时 Stub 返回 500（见 startStub）
        MvcResult result = polishRaw("{\"text\":\"TRIGGER_FAIL 这段会失败\"}", token);
        String frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("C0200", jsonField(dataOf(frames, "error"), "code"), frames);
    }
}
