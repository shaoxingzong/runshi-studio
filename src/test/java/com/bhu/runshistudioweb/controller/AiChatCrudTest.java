package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.manager.AiCircuitBreaker;
import com.bhu.runshistudioweb.manager.AiQueryCountManager;
import com.bhu.runshistudioweb.mapper.StudioAiMessageMapper;
import com.bhu.runshistudioweb.mapper.StudioAiSessionMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AI 咨询模块验收测试
 *
 * AI 调用用本地 HTTP Stub（JDK 自带 HttpServer，随机端口）代替真实服务商：
 * - 通过 @DynamicPropertySource 把 studio.ai.* 覆盖为 Stub 地址，
 * AiManager 在 @PostConstruct 构建 RestClient 时就会拿到它——这就是
 * 「base-url 必须来自配置」的验收理由；
 * - Stub 固定返回 OpenAI 兼容响应 {"choices":[{"message":{"content":"stub-回答"}}]}，
 * 并记录最近一次请求体，供「system 角色在首、上下文只取最近 N 条」断言取证；
 * - 请求正文包含 TRIGGER_FAIL 时 Stub 返回 500，用于验证失败分支
 * （C0200 + 保留用户消息 + 配额不加）。
 *
 * 覆盖十条线：
 * - 未登录一律 A0201（游客不可用）+ 精确白名单（既有公开接口回归、管理端接口仍 A0201）；
 * - 首轮自动建会话 + 落 user/assistant 两条消息（SQL 取证）；
 * - 续聊追加同会话、history 时间正序、上限 50；
 * - 普通用户配额逐次原子 +1（SQL 对齐）、超限 A0501；成员不限额；
 * - 参数非法 A0401、会话不存在 A0402；
 * - 会话归属：他人会话 A0402、匿名访问一律 A0201；
 * - AI 失败 → C0200 且无 assistant 消息、配额不加（SQL 取证）；
 * - 返回体 Long 全字符串；
 * - 上下文窗口：Stub 收到的 messages = system + 最近 10 条 + 本次提问；
 * - 全量基线用例保持全绿。
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiChatCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioAiSessionMapper aiSessionMapper;

    @Resource
    private StudioAiMessageMapper aiMessageMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /** 起配额计数走 Redis，测试里读「用量」必须取合并值（DB 基准 + Redis 增量） */
    @Resource
    private AiQueryCountManager aiQueryCountManager;

    /**
     * AI 熔断器
     *
     * 本类有「故意让 AI 失败」的用例，连续失败会打开熔断；而熔断器是进程内单例，
     * 不重置的话同一 JVM 里后跑的 AI 测试会被快速失败成一批 C0200。
     */
    @Resource
    private AiCircuitBreaker circuitBreaker;

    // 每个用例前重置熔断：让本类的失败用例只影响自己，不污染后续测试类
    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker.reset();
    }

    // ==================== 本地 AI Stub ====================

    /** Stub 服务：只认识 /chat/completions，其余路径一律 404 */
    private static HttpServer stub;

    /** 最近一次收到的请求体（供上下文窗口断言） */
    private static volatile String lastRequestBody;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody = body;
            // 提问内容含 TRIGGER_FAIL → 模拟服务商故障（500）
            if (body.contains("TRIGGER_FAIL")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
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
        // 提示词改为「文件位置」：这里指向测试专用文件（src/test/resources/prompts/test-system-prompt.txt）。
        // 断言仍校验模型收到的首条消息是「你是测试助手」——于是这条用例从「配置透传」
        // 升级成「文件 → 模型」全链路验证，且与线上真实话术文本解耦
        registry.add("studio.ai.system-prompt-location", () -> "classpath:prompts/test-system-prompt.txt");
        // 配额上限调小：让「超限 A0501」用例不必真的问 100 次
        registry.add("studio.ai.query-limit", () -> 3);
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    // ==================== 工具方法 ====================

    private String postJson(String path, String json, String tokenValue) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String getBody(String path, String tokenValue) throws Exception {
        var request = get(path);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private String message(String body) {
        int start = body.indexOf("\"message\":\"") + 11;
        return body.substring(start, body.indexOf('"', start));
    }

    /** 从 chat 响应里取 sessionId（JSON 里是字符串） */
    private String sessionIdOf(String body) {
        int start = body.indexOf("\"sessionId\":\"") + 13;
        assertTrue(start > 13, "响应缺少 sessionId：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    private String answerOf(String body) {
        int start = body.indexOf("\"answer\":\"") + 10;
        return body.substring(start, body.indexOf('"', start));
    }

    /** 注册一个普通用户并登录，返回 token */
    private String loginUser() throws Exception {
        String account = "ai_u_" + (System.nanoTime() % 100000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.USER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        lastUserId = user.getId();

        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 最近一次登录的用户 id（用于配额 SQL 断言） */
    private Long lastUserId;

    /**
     * 注册一个成员用户并登录
     *
     * 成员不受提问次数限制，用它可以连续提问而不撞配额上限——
     * 需要「多次提问」的用例（续聊、上下文窗口等）都用它，
     * 顺带也覆盖了「成员不限额」这条规则。
     */
    private String loginMember() throws Exception {
        String account = "ai_m_" + (System.nanoTime() % 100000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.MEMBER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        lastUserId = user.getId();

        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 游客提问（匿名），返回响应体 */
    private String chatAnon(String sessionId, String messageText) throws Exception {
        String payload = sessionId == null
                ? "{\"message\":\"" + messageText + "\"}"
                : "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + messageText + "\"}";
        return postJson("/ai/chat", payload, null);
    }

    /** 登录用户提问，返回响应体 */
    private String chatLogged(String sessionId, String messageText, String token) throws Exception {
        String payload = sessionId == null
                ? "{\"message\":\"" + messageText + "\"}"
                : "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + messageText + "\"}";
        return postJson("/ai/chat", payload, token);
    }

    private long countMessages(long sessionId) {
        return aiMessageMapper.selectCount(new LambdaQueryWrapper<StudioAiMessage>()
                .eq(StudioAiMessage::getSessionId, sessionId));
    }

    private int queryCountOf(long userId) {
        // 走 JdbcTemplate 直查库：不受 MyBatis 一级缓存影响，读到的是真实库值
        Integer count = jdbcTemplate.queryForObject(
                "SELECT ai_query_count FROM sys_user WHERE id=?", Integer.class, userId);
        // 起计数先进 Redis、由定时任务回刷 DB，
        // 因此「用户实际用量」= DB 基准 + Redis 未落库增量，只读 DB 会读到回刷前的旧值
        return aiQueryCountManager.merge(userId, count);
    }

    // ==================== ① 白名单与鉴权 ====================

    @Test
    @DisplayName("鉴权：/ai/* 未登录一律 A0201；登录后提问放行；管理端与既有白名单不受影响")
    void whitelistPrecision() throws Exception {
        // 匿名提问：AI 服务不向游客开放
        assertEquals("A0201", code(chatAnon(null, "你好")), "匿名提问应 A0201");
        // 匿名查历史、删除会话：同样要求登录
        assertEquals("A0201", code(getBody("/ai/chat/history?sessionId=1", null)));
        assertEquals("A0201", code(postJson("/ai/session/delete", "{\"sessionId\":\"1\"}", null)));

        // 登录后可正常提问（stub 返回 200）
        String token = loginMember();
        String chat = chatLogged(null, "你好", token);
        assertEquals("00000", code(chat), "登录提问应放行：" + chat);

        // 登录后缺参：A0401 而不是 B0001（Controller 用 required=false 兜底）
        assertEquals("A0401", code(getBody("/ai/chat/history", token)), "缺 sessionId 应 A0401");

        // 管理端接口回归：AI 相关的白名单调整不得顺带放行
        assertEquals("A0201", code(getBody("/member/list/page", null)));
        assertEquals("A0201", code(postJson("/member/add", "{}", null)));

        // 既有公开接口回归：仍匿名可用
        // （成员接口已按「团队成员不对外展示」整体删除，访问得到 404 且响应体为空，
        //   相关断言集中放在 StudioMemberCrudTest#anonymousCanBrowseFrontListButNotAdmin）
        assertEquals("00000", code(getBody("/project/list", null)));
    }

    // ==================== ② 首轮建会话 + 落两条消息 ====================

    @Test
    @DisplayName("首轮：自动建会话、标题取首问前 30 字、落 user + assistant 两条消息")
    void firstRoundCreatesSessionAndMessages() throws Exception {
        String longQuestion = "你好，请问润石工作室是做什么的？请给我详细介绍下我们的业务范围和技术方向" +
                "，以及我们工作室的成立背景和历史沿革，这些内容可能会超过三十个字的长度";
        String token = loginMember();
        String chat = chatLogged(null, longQuestion, token);
        assertEquals("00000", code(chat));
        String sessionId = sessionIdOf(chat);
        assertEquals("stub-回答", answerOf(chat), "回答应来自 Stub 成功分支");

        long sid = Long.parseLong(sessionId);
        // 会话落库：标题 = 首问前 30 字；会话归属当前登录用户
        StudioAiSession session = aiSessionMapper.selectById(sid);
        assertTrue(session != null, "会话没有落库");
        assertEquals(lastUserId, session.getUserId(), "会话应归属当前登录用户");
        assertEquals(longQuestion.substring(0, 30), session.getTitle(), "标题应取首问前 30 字");

        // 恰好 2 条消息：user + assistant
        assertEquals(2, countMessages(sid), "首轮应恰好落 2 条消息");
        List<StudioAiMessage> msgs = aiMessageMapper.selectList(new LambdaQueryWrapper<StudioAiMessage>()
                .eq(StudioAiMessage::getSessionId, sid).orderByAsc(StudioAiMessage::getId));
        assertEquals("user", msgs.get(0).getRole());
        assertEquals(longQuestion, msgs.get(0).getContent());
        assertEquals("assistant", msgs.get(1).getRole());
        assertEquals("stub-回答", msgs.get(1).getContent());
    }

    // ==================== ③ 续聊 + history 正序 ====================

    @Test
    @DisplayName("续聊：追加进同一会话；history 时间正序；上限 50 条")
    void continueSessionAndHistoryOrder() throws Exception {
        String token = loginMember();
        String sessionId = sessionIdOf(chatLogged(null, "第一问", token));
        chatLogged(sessionId, "第二问", token);
        chatLogged(sessionId, "第三问", token);

        assertEquals(6, countMessages(Long.parseLong(sessionId)), "三轮应落 6 条消息");

        String history = getBody("/ai/chat/history?sessionId=" + sessionId, token);
        assertEquals("00000", code(history));
        // 正序：user/assistant 交替，第一轮 user 在最前
        int firstUser = history.indexOf("第一问");
        int lastAssistant = history.indexOf("stub-回答", history.indexOf("第三问"));
        assertTrue(firstUser > 0 && lastAssistant > 0, "history 内容异常：" + history);
        assertTrue(history.indexOf("第一问") < history.indexOf("第二问")
                && history.indexOf("第二问") < history.indexOf("第三问"), "history 应为时间正序");

        // 上限 50：直接插 55 条消息（绕过接口），history 只返回最近 50 条
        long sid = Long.parseLong(sessionId);
        for (int i = 0; i < 55; i++) {
            StudioAiMessage m = new StudioAiMessage();
            m.setSessionId(sid);
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent("bulk-" + i);
            aiMessageMapper.insert(m);
        }
        String big = getBody("/ai/chat/history?sessionId=" + sessionId, token);
        assertEquals("00000", code(big));
        assertEquals(50, countOf(big, "\"role\":"), "history 应只返回最近 50 条");
        // 只保留最近 50 条 → 最早的 bulk-0 不在
        assertFalse(big.contains("bulk-0"), "最早的批量消息不应出现在最近 50 条里");
    }

    private int countOf(String body, String needle) {
        int count = 0, idx = 0;
        while ((idx = body.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ==================== ④ 配额 ====================

    @Test
    @DisplayName("配额：登录用户每次成功提问原子 +1（SQL 对齐）；达上限 A0501")
    void quotaIncrementAndLimit() throws Exception {
        String token = loginUser();
        long userId = lastUserId;
        assertEquals(0, queryCountOf(userId));

        String r1 = chatLogged(null, "第一次提问", token);
        assertEquals("00000", code(r1));
        assertEquals(1, queryCountOf(userId), "第一次成功后计数应为 1");

        String r2 = chatLogged(sessionIdOf(r1), "第二次提问", token);
        assertEquals("00000", code(r2));
        assertEquals(2, queryCountOf(userId), "第二次成功后计数应为 2（原子 +1，SQL 对齐）");

        // 直接把计数顶到上限（避免真问 3 次），下一次提问必须 A0501
        // 注意：本用例此前没有对这位用户做过 ORM select，这里首次读发生在 UPDATE 之后，
        // 不会撞 MyBatis 一级缓存
        jdbcTemplate.update("UPDATE sys_user SET ai_query_count=3 WHERE id=?", userId);
        String blocked = chatLogged(sessionIdOf(r1), "第三次提问", token);
        assertEquals("A0501", code(blocked), "超限应 A0501：" + blocked);
        assertTrue(message(blocked).contains("用完") || message(blocked).contains("成员"),
                "提示应说明次数用完并引导成员：" + message(blocked));
    }

    // ==================== ⑤ 参数与不存在 ====================

    @Test
    @DisplayName("错误码：message 空/超长 A0401；会话不存在 A0402 统一提示")
    void invalidParamsAndMissingSession() throws Exception {
        String token = loginMember();
        assertEquals("A0401", code(chatLogged(null, "  ", token)), "纯空格提问应 A0401");
        String overlong = "长".repeat(501);
        assertEquals("A0401", code(chatLogged(null, overlong, token)), "超长提问应 A0401");

        String missing = chatLogged("999999999999999999", "在吗", token);
        assertEquals("A0402", code(missing));
        assertEquals("会话不存在", message(missing));

        assertEquals("A0402", code(getBody("/ai/chat/history?sessionId=999999999999999999", token)));
        assertEquals("A0401", code(getBody("/ai/chat/history?sessionId=abc", token)), "脏 sessionId 应 A0401");
    }

    // ==================== ⑥ 会话归属 ====================

    @Test
    @DisplayName("归属：他人会话 A0402（同码同提示）；匿名访问一律 A0201")
    void sessionOwnership() throws Exception {
        String tokenA = loginMember();
        String tokenB = loginMember();
        String sessionA = sessionIdOf(chatLogged(null, "A 的问题", tokenA));

        // B 访问 A 的会话：与「不存在」同码同提示
        String cross = chatLogged(sessionA, "B 想插话", tokenB);
        assertEquals("A0402", code(cross));
        assertEquals("会话不存在", message(cross));
        assertEquals("A0402", code(getBody("/ai/chat/history?sessionId=" + sessionA, tokenB)));

        // 匿名无法访问任何会话（未登录一律 A0201）
        assertEquals("A0201", code(chatAnon(sessionA, "游客追问")));

        // 本人可正常续聊
        assertEquals("00000", code(chatLogged(sessionA, "A 继续", tokenA)));
        assertEquals(4, countMessages(Long.parseLong(sessionA)), "A 两轮 = 4 条消息");
    }

    // ==================== ⑦ AI 失败分支 ====================

    @Test
    @DisplayName("AI 失败：C0200、保留用户消息、无 assistant、配额不加")
    void aiFailureKeepsUserMessage() throws Exception {
        String token = loginUser();
        long userId = lastUserId;
        String sessionId = sessionIdOf(chatLogged(null, "正常提问", token));
        assertEquals(1, queryCountOf(userId));

        // TRIGGER_FAIL → Stub 返回 500 → 必须 C0200（AI 服务属第三方，不是本系统 bug）
        String fail = chatLogged(sessionId, "TRIGGER_FAIL 这次会失败", token);
        assertEquals("C0200", code(fail));
        assertTrue(message(fail).contains("暂时不可用"), "提示应友好：" + message(fail));

        // 失败时：user 消息保留、无 assistant、配额不加
        long sid = Long.parseLong(sessionId);
        List<StudioAiMessage> msgs = aiMessageMapper.selectList(new LambdaQueryWrapper<StudioAiMessage>()
                .eq(StudioAiMessage::getSessionId, sid).orderByAsc(StudioAiMessage::getId));
        assertEquals(3, msgs.size(), "正常一轮 + 失败一轮 = 2 user + 1 assistant");
        assertEquals("TRIGGER_FAIL 这次会失败", msgs.get(2).getContent());
        assertEquals("user", msgs.get(2).getRole());
        assertEquals(1, queryCountOf(userId), "AI 失败不应增加配额");
    }

    // ==================== ⑧ 返回体 Long 字符串 ====================

    @Test
    @DisplayName("序列化：sessionId 与消息 id 均为字符串（防 JS 精度丢失）")
    void longSerializedAsString() throws Exception {
        String token = loginMember();
        String sessionId = sessionIdOf(chatLogged(null, "id 格式", token));
        // sessionId 在 JSON 里带引号（字符串）
        assertTrue(sessionId.matches("\\d{19}"), "sessionId 应为 19 位数字：" + sessionId);

        String history = getBody("/ai/chat/history?sessionId=" + sessionId, token);
        assertTrue(Pattern.compile("\"id\":\"\\d{19}\"").matcher(history).find(),
                "消息 id 应序列化为字符串：" + history.substring(0, Math.min(300, history.length())));
    }

    // ==================== ⑨ 上下文窗口 ====================

    @Test
    @DisplayName("上下文窗口：Stub 收到 system + 最近 10 条 + 本次提问（最早轮次被裁剪）")
    void contextWindowTrimsOldest() throws Exception {
        // 6 轮 = 12 条消息；第 7 次提问时 history 只取最近 10 条。
        // 用成员账号：7 次提问远超普通用户 5 次上限，正好验证「成员不限额」
        String token = loginMember();
        String sessionId = null;
        for (int i = 1; i <= 6; i++) {
            sessionId = sessionIdOf(chatLogged(sessionId, "ctx-q" + i, token));
        }
        // 第 7 次提问，触发 Stub 记录请求体
        chatLogged(sessionId, "ctx-final", token);

        JsonNode payload = jsonMapper.readTree(lastRequestBody);
        JsonNode messages = payload.get("messages");
        assertEquals(12, messages.size(), "应为 system + 10 条历史 + 1 条提问");
        assertEquals("system", messages.get(0).get("role").asString(), "system 角色必须最先");
        assertEquals("你是测试助手", messages.get(0).get("content").asString());

        // 最早一轮（ctx-q1）被窗口裁掉；最后一条是本次提问
        String joined = payload.toString();
        assertFalse(joined.contains("ctx-q1"), "最早一轮不应进入上下文窗口：" + joined);
        assertTrue(joined.contains("ctx-q2") && joined.contains("ctx-final"), "窗口内应有 q2~q6 与本次提问");
        assertEquals("user", messages.get(messages.size() - 1).get("role").asString());
        assertEquals("ctx-final", messages.get(messages.size() - 1).get("content").asString());

        // stream=false 必须显式声明（非流式接口契约）
        assertFalse(payload.get("stream").asBoolean(), "应显式 stream=false");
        assertEquals("test-model", payload.get("model").asString(), "model 应来自配置");
    }
}