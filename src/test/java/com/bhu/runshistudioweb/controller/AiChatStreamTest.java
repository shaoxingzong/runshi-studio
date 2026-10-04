package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.manager.AiQueryCountManager;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SSE 流式提问验收测试
 *
 * author: shaoshing
 *
 * <p><b>本类刻意不加 {@code @Transactional}（与其它测试类不同）</b>：
 * 流式主流程运行在虚拟线程上（{@code streamExecutor}），数据库写入是<b>独立事务、真实提交</b>的，
 * 测试事务回滚对它无效。因此本类在 {@code @AfterEach} 里按前缀手动清理，
 * 所有测试数据都用 {@code T23} 前缀命名（见 {@link #cleanup()}）。
 *
 * <p>AI 流式调用用本地 Stub（JDK {@link HttpServer}）模拟 OpenAI 兼容的 SSE 响应：
 * 固定推送三段增量「流式 / - / 回答」（拼起来 = {@code 流式-回答}），
 * 请求体含 {@code TRIGGER_FAIL} 时返回 500，用于验证失败分支。
 *
 * <p>覆盖七条线：
 * <ol>
 *     <li><b>事件序列</b>：{@code meta → delta* → done}，meta 带 19 位字符串 sessionId；</li>
 *     <li><b>增量拼接</b>：delta 事件内容拼接 == 完整回答（多段推送，不是一次性返回）；</li>
 *     <li><b>落库一致性</b>：流结束后 user + assistant 两条消息入库，done 的 messageId 可在库中查到；</li>
 *     <li><b>续聊</b>：带 sessionId 的流式追加进同一会话；</li>
 *     <li><b>配额</b>：登录用户每次成功流式 +1（SQL 对齐）；</li>
 *     <li><b>失败也是事件</b>：参数错误 / 会话不存在或无权 / AI 失败 → {@code error} 事件，
 *     HTTP 恒 200；AI 失败时保留用户消息、无 assistant、不计数；</li>
 *     <li><b>回归</b>：匿名放行（白名单精确）与既有接口不受影响。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiChatStreamTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 配额计数走 Redis，测试里读「用量」必须取合并值（DB 基准 + Redis 增量） */
    @Resource
    private AiQueryCountManager aiQueryCountManager;

    // ==================== 本地 AI Stub（SSE 版） ====================

    private static HttpServer stub;

    /** Stub 推送的三段增量，拼起来即完整回答 */
    private static final String ANSWER_PART_1 = "流式";
    private static final String ANSWER_PART_2 = "-";
    private static final String ANSWER_PART_3 = "回答";

    private static String fullAnswer() {
        return ANSWER_PART_1 + ANSWER_PART_2 + ANSWER_PART_3;
    }

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // ⚠️ LangChain4j 的请求体是**带空格的美化 JSON**（"stream" : true），
            // 精确匹配 "stream":true 会失败——必须先压掉空白再判断
            String compact = body.replace(" ", "").replace("\r", "").replace("\n", "");
            if (compact.contains("TRIGGER_FAIL")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if (compact.contains("\"stream\":true")) {
                // OpenAI 兼容的 SSE：每个 data 行是一段增量，[DONE] 收尾
                String sse = chunk(ANSWER_PART_1, false) + chunk(ANSWER_PART_2, false)
                        + chunk(ANSWER_PART_3, true) + "data: [DONE]\n\n";
                byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else {
                byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"非流式-回答\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
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
        registry.add("studio.ai.query-limit", () -> 20);
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    /**
     * 按前缀清理本类产生的数据（非事务类必须显式清理；先子后父）
     */
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM studio_ai_message WHERE session_id IN "
                + "(SELECT id FROM studio_ai_session WHERE title LIKE 'T23%' "
                + "OR user_id IN (SELECT id FROM sys_user WHERE user_account LIKE 'T23%'))");
        jdbcTemplate.update("DELETE FROM studio_ai_session WHERE title LIKE 'T23%' "
                + "OR user_id IN (SELECT id FROM sys_user WHERE user_account LIKE 'T23%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE user_account LIKE 'T23%'");
    }

    // ==================== 工具方法 ====================

    /** 发起流式提问并等待流结束，返回原始 SSE 帧文本（轮询到 done/error 帧出现为止） */
    private MvcResult streamRaw(String json, String token) throws Exception {
        var request = post("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("satoken", token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertTrue(result.getRequest().isAsyncStarted(), "SSE 请求应进入异步模式");
        String frames = "";
        for (int i = 0; i < 200; i++) {
            frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (frames.contains("event:done") || frames.contains("event:error")) {
                // 事件帧是分多次写入的：连「event:xxx」字样出现后，data 载荷可能还没写完。
                // 连续两次读取（间隔 60ms）内容一致，才认为这台流已收尾且写完整
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

    private String streamFrames(String json, String token) throws Exception {
        return streamRaw(json, token).getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 取某类事件的全部 data 负载（兼容冒号后空格与 CRLF） */
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

    /** 从 JSON 负载里取字符串字段（负载是本项目 JsonMapper 序列化的紧凑 JSON） */
    private String jsonField(String payload, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(payload);
        assertTrue(m.find(), "负载缺少字段 " + field + "：" + payload);
        return m.group(1);
    }

    /** 最近一次登录的账户（供配额断言与清理使用） */
    private String lastAccount;

    private int queryCountOf(String account) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT ai_query_count FROM sys_user WHERE user_account=?", Integer.class, account);
        // 计数先进 Redis、由定时任务回刷 DB：
        // 只读 DB 会读到回刷前的旧值，必须用「DB 基准 + Redis 增量」的合并值
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE user_account=?", Long.class, account);
        return aiQueryCountManager.merge(userId, count);
    }

    private String loginUserTracked() throws Exception {
        String account = "T23u_" + (System.nanoTime() % 100000);
        lastAccount = account;
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.USER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);

        String body = mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}"))
                .andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 注册并登录一个测试用户（账户记录在 lastAccount，供配额断言与清理） */
    private String login() throws Exception {
        return loginUserTracked();
    }

    // ==================== ① ② ③ 事件序列 / 增量拼接 / 落库 ====================

    @Test
    @DisplayName("匿名流式：meta → delta* → done；增量拼接=完整回答；两条消息入库且 messageId 可查")
    void anonymousStreamSequenceAndPersistence() throws Exception {
        MvcResult result = streamRaw("{\"message\":\"T23-你好\"}", null);
        String frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // HTTP 恒 200（SSE 契约）
        assertEquals(200, result.getResponse().getStatus());

        // 事件顺序：meta → sources → delta → done
        int iMeta = frames.indexOf("event:meta");
        int iSources = frames.indexOf("event:sources");
        int iDelta = frames.indexOf("event:delta");
        int iDone = frames.indexOf("event:done");
        assertTrue(iMeta >= 0 && iSources > iMeta && iDelta > iSources && iDone > iDelta,
                "事件顺序应为 meta → sources → delta → done：" + frames);
        // 本测试环境没有 Embedding 通路（Stub 无 /embeddings）→ 检索降级；
        // sources 恒发、载荷为空数组（前端不必靠「等一会儿没收到」来判断有没有资料）
        assertTrue(dataOf(frames, "sources").contains("\"sources\":[]"),
                "无命中时也应发空数组：" + frames);

        // meta：sessionId 是 19 位字符串
        String meta = dataOf(frames, "meta");
        String sessionId = jsonField(meta, "sessionId");
        assertTrue(sessionId.matches("\\d{19}"), "sessionId 应为 19 位字符串：" + meta);

        // delta：多段推送，拼接 == 完整回答
        List<String> deltas = dataFor(frames, "delta");
        assertTrue(deltas.size() >= 2, "应是多段增量推送：" + frames);
        StringBuilder joined = new StringBuilder();
        for (String d : deltas) {
            joined.append(jsonField(d, "delta"));
        }
        assertEquals(fullAnswer(), joined.toString(), "增量拼接应等于完整回答");

        // done：messageId 指向落库的 assistant 消息
        String messageId = jsonField(dataOf(frames, "done"), "messageId");
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE id=? AND role='assistant' AND content=?",
                Integer.class, Long.parseLong(messageId), fullAnswer()), "done 的 messageId 应能查到 assistant 消息");

        // 落库：user + assistant 两条，user 内容是原提问
        assertEquals(2, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=?",
                Integer.class, Long.parseLong(sessionId)), "应落 2 条消息");
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=? AND role='user' AND content='T23-你好'",
                Integer.class, Long.parseLong(sessionId)), "user 消息内容应与提问一致");
    }

    @Test
    @DisplayName("续聊：带 sessionId 的流式追加进同一会话（4 条消息）")
    void continueSameSessionByStream() throws Exception {
        String first = streamFrames("{\"message\":\"T23-第一问\"}", null);
        String sessionId = jsonField(dataOf(first, "meta"), "sessionId");

        String second = streamFrames("{\"sessionId\":\"" + sessionId + "\",\"message\":\"T23-第二问\"}", null);
        assertEquals(sessionId, jsonField(dataOf(second, "meta"), "sessionId"), "sessionId 应保持不变");

        assertEquals(4, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=?",
                Integer.class, Long.parseLong(sessionId)), "两轮应落 4 条消息");
    }

    // ==================== ⑤ 配额 ====================

    @Test
    @DisplayName("配额：登录用户每次成功流式提问 +1（SQL 对齐）")
    void loggedInUserQuotaIncrements() throws Exception {
        String token = login();
        assertEquals(0, queryCountOf(lastAccount));

        String first = streamFrames("{\"message\":\"T23-登录第一问\"}", token);
        assertTrue(first.contains("event:done"), "登录用户流式应成功：" + first);
        assertEquals(1, queryCountOf(lastAccount), "第一次成功后计数应为 1");

        String sessionId = jsonField(dataOf(first, "meta"), "sessionId");
        streamFrames("{\"sessionId\":\"" + sessionId + "\",\"message\":\"T23-登录第二问\"}", token);
        assertEquals(2, queryCountOf(lastAccount), "第二次成功后计数应为 2");
    }

    // ==================== ⑥ 失败也是事件（HTTP 恒 200） ====================

    @Test
    @DisplayName("参数错误以 error 事件返回：HTTP 200、无 meta、code=40000")
    void paramsErrorBecomesErrorEvent() throws Exception {
        MvcResult result = streamRaw("{\"message\":\"   \"}", null);
        String frames = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(200, result.getResponse().getStatus(), "SSE 失败也应 HTTP 200");
        assertFalse(frames.contains("event:meta"), "参数错误不应发出 meta：" + frames);
        String error = dataOf(frames, "error");
        assertTrue(error.contains("\"code\":40000"), "应为 40000：" + error);
    }

    @Test
    @DisplayName("会话不存在 / 他人会话：error 40400 统一提示（与同步接口同规则）")
    void sessionNotFoundAndOwnership() throws Exception {
        // 不存在
        String missing = streamFrames(
                "{\"sessionId\":\"999999999999999999\",\"message\":\"T23-在吗\"}", null);
        String error = dataOf(missing, "error");
        assertTrue(error.contains("\"code\":40400") && error.contains("会话不存在"), "应 40400：" + error);

        // 登录用户 A 建会话，匿名尝试续聊 → 与不存在同码同提示
        String token = login();
        String own = streamFrames("{\"message\":\"T23-A 的会话\"}", token);
        String sessionId = jsonField(dataOf(own, "meta"), "sessionId");
        String cross = streamFrames(
                "{\"sessionId\":\"" + sessionId + "\",\"message\":\"T23-越权续聊\"}", null);
        String crossError = dataOf(cross, "error");
        assertTrue(crossError.contains("\"code\":40400") && crossError.contains("会话不存在"),
                "越权应 40400 且不暴露存在性：" + crossError);
    }

    @Test
    @DisplayName("AI 失败：error 50001；保留用户消息、无 assistant、不计数")
    void aiFailureKeepsUserMessageAndSkipsCount() throws Exception {
        String token = login();
        String frames = streamFrames("{\"message\":\"T23-TRIGGER_FAIL 故障注入\"}", token);

        String error = dataOf(frames, "error");
        assertTrue(error.contains("\"code\":50001") && error.contains("暂时不可用"), "应 50001：" + error);

        String sessionId = jsonField(dataOf(frames, "meta"), "sessionId");
        long sid = Long.parseLong(sessionId);
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=?", Integer.class, sid),
                "失败时应只保留用户消息");
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=? AND role='user'",
                Integer.class, sid), "留下的应是 user 消息");
        assertEquals(0, queryCountOf(lastAccount), "失败不应增加配额");
    }

    // ==================== ⑦ 回归 ====================

    @Test
    @DisplayName("回归：匿名流式放行；既有公开接口与管理端拦截不受影响")
    void whitelistAndRegression() throws Exception {
        // 匿名流式放行（主用例已证），这里补一条既有公开接口 + 管理端拦截
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE user_account LIKE 'T23%'", Integer.class),
                "清理基准：本类不应有历史残留");

        // 成员接口已按「团队成员不对外展示」整体删除，
        // 「匿名可用」的取样改用仍公开的项目列表（成员接口的 404 断言见 StudioMemberCrudTest）
        String body = mockMvc.perform(get("/project/list")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":0"), "既有公开接口应匿名可用：" + body);

        String admin = mockMvc.perform(get("/member/list/page")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(admin.contains("\"code\":40100"), "管理端接口应仍拦截匿名：" + admin);
    }
}