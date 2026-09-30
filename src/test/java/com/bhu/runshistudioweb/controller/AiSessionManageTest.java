package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AI 会话管理验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖十条线（与施工图 AC 一一对应）：
 * <ol>
 *     <li><b>鉴权边界</b>：{@code /ai/session/list} 未登录 40100（不进白名单）；
 *     {@code /ai/session/delete} 匿名放行（白名单精确路径）；既有公开接口回归；</li>
 *     <li><b>列表隔离</b>：只返回本人的会话，看不到别人的；</li>
 *     <li><b>排序与分页</b>：{@code updated_at 倒序 → id 倒序}（同秒活跃的稳定次序），
 *     pageSize 超过 50 收敛；</li>
 *     <li><b>messageCount 批量统计</b>：与 SQL 逐行对齐，空会话补 0；</li>
 *     <li><b>删除=双逻辑删</b>：会话与消息在同一事务内写 {@code deleted_at}，
 *     物理行保留（可追溯），与/19 的物理删关联表形成对照；</li>
 *     <li><b>删除后行为</b>：chat / history / 重复删除 统一 40400「会话不存在」；</li>
 *     <li><b>归属规则</b>：他人会话 40400（同码同提示）；匿名会话凭 ID 可删；</li>
 *     <li><b>参数校验</b>：缺参 / 空白 / 脏值 40000；不存在 40400；</li>
 *     <li><b>脱敏与序列化</b>：无 userId / 审计字段；id 出字符串、messageCount 出数字；</li>
 *     <li><b>全量基线 120 用例保持全绿。</b></li>
 * </ol>
 *
 * <p>会话数据通过提问接口自然创建（顺带验证「聊过就有会话」的集成路径），
 * 个别边界（空会话、批量计数对齐）用 Mapper 直插；断言一律以 SQL 直查为准，
 * 避免「用同一个 Service 的读方法验证它的写方法」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiSessionManageTest {

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

    // ==================== 本地 AI Stub（会话需通过提问接口创建，故沿用 的 Stub 范式） ====================

    private static HttpServer stub;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/chat/completions", exchange -> {
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
        // 本类每个用例最多连续提问 5 次，配额留足余量即可
        registry.add("studio.ai.query-limit", () -> 20);
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

    private int code(String body) {
        int start = body.indexOf("\"code\":") + 7;
        return Integer.parseInt(body.substring(start, body.indexOf(',', start)));
    }

    private String message(String body) {
        int start = body.indexOf("\"message\":\"") + 11;
        return body.substring(start, body.indexOf('"', start));
    }

    private String sessionIdOf(String body) {
        int start = body.indexOf("\"sessionId\":\"") + 13;
        assertTrue(start > 13, "响应缺少 sessionId：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 注册一个普通用户并登录，token 缓存到字段；同时记录其用户 id（供直插空会话用） */
    private Long lastUserId;

    private String loginUser() throws Exception {
        String account = "sm_u_" + (System.nanoTime() % 100000);
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

    /** 提问（sessionId 为 null 表示新建会话），返回响应体 */
    private String chat(String token, String sessionId, String messageText) throws Exception {
        String payload = sessionId == null
                ? "{\"message\":\"" + messageText + "\"}"
                : "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + messageText + "\"}";
        return postJson("/ai/chat", payload, token);
    }

    /** 通过提问创建会话，返回 sessionId */
    private String newSession(String token, String firstMessage) throws Exception {
        String body = chat(token, null, firstMessage);
        assertEquals(0, code(body), "创建会话失败：" + body);
        return sessionIdOf(body);
    }

    private long countMessages(long sessionId) {
        // Mapper 查询自动追加 deleted_at = 0：统计的是「可见消息」
        return aiMessageMapper.selectCount(new LambdaQueryWrapper<StudioAiMessage>()
                .eq(StudioAiMessage::getSessionId, sessionId));
    }

    private int countOf(String body, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ==================== ① 鉴权边界与列表隔离 ====================

    @Test
    @DisplayName("鉴权：列表未登录 40100；只返回本人会话；删除接口匿名可达")
    void authBoundaryAndIsolation() throws Exception {
        // 未登录访问列表：40100（该路径不进白名单）
        assertEquals(40100, code(getBody("/ai/session/list", null)), "会话列表必须登录");

        // 游客访问删除接口：能被拦截器放行（白名单），只是会话不存在 → 40400 而非 40100
        assertEquals(40400, code(postJson("/ai/session/delete",
                "{\"sessionId\":\"999999999999999999\"}", null)), "删除接口应匿名放行");

        // 隔离：A 两个会话，B 一个会话；A 的列表看不到 B 的
        String tokenA = loginUser();
        newSession(tokenA, "T22-a-first");
        newSession(tokenA, "T22-a-second");
        String tokenB = loginUser();
        newSession(tokenB, "T22-b-only");

        String listA = getBody("/ai/session/list?pageSize=50", tokenA);
        assertEquals(0, code(listA));
        assertEquals(2, countOf(listA, "\"title\":"), "A 应恰好看到自己的 2 个会话：" + listA);
        assertTrue(listA.contains("T22-a-first") && listA.contains("T22-a-second"));
        assertFalse(listA.contains("T22-b-only"), "不能看到别人的会话：" + listA);
    }

    // ==================== ③ 排序与分页 ====================

    @Test
    @DisplayName("排序：updated_at 倒序 → id 倒序；分页 pageSize 超限收敛到 50")
    void orderAndPagination() throws Exception {
        String token = loginUser();
        String s1 = newSession(token, "T22-o1");
        String s2 = newSession(token, "T22-o2");
        String s3 = newSession(token, "T22-o3");

        // 直接改库赋不同的 updated_at 保证次序确定（【先改库、后首次读】规避 MyBatis 一级缓存：
        // 本用例只在改库完成后发起唯一一次列表读取——同一 SQL 二次读取会命中缓存拿到旧结果）
        jdbcTemplate.update("UPDATE studio_ai_session SET updated_at='2026-01-01 10:00:30' WHERE id=?",
                Long.parseLong(s1));
        // s2 与 s3 故意同秒：验证次级排序键 id 倒序（s3 后建、id 更大，应排在 s2 前）
        jdbcTemplate.update("UPDATE studio_ai_session SET updated_at='2026-01-01 10:00:10' WHERE id=?",
                Long.parseLong(s2));
        jdbcTemplate.update("UPDATE studio_ai_session SET updated_at='2026-01-01 10:00:10' WHERE id=?",
                Long.parseLong(s3));

        String list = getBody("/ai/session/list?pageSize=50", token);
        // 期望次序：o1（10:00:30）→ o3（10:00:10，id 大）→ o2（10:00:10）
        assertTrue(list.indexOf("T22-o1") < list.indexOf("T22-o3")
                        && list.indexOf("T22-o3") < list.indexOf("T22-o2"),
                "应按 updated_at 倒序、同秒按 id 倒序：" + list);

        // 分页收敛：pageSize=999 → size 收敛为 50；current<1 视为 1（数据未再变化，可安全复用读取）
        // 注意：Page 元数据（size/current/total/pages）是 long，遵循全局 Long→字符串约定，出的是字符串
        String big = getBody("/ai/session/list?pageSize=999", token);
        assertTrue(big.contains("\"size\":\"50\""), "pageSize 应收敛到 50：" + big);
        String badCurrent = getBody("/ai/session/list?current=0&pageSize=50", token);
        assertTrue(badCurrent.contains("\"current\":\"1\""), "current<1 应视为 1：" + badCurrent);
    }

    // ==================== ④ messageCount 批量统计对齐 ====================

    @Test
    @DisplayName("messageCount：与 SQL 逐行对齐，空会话补 0（不是 null / 不是缺行）")
    void messageCountAligned() throws Exception {
        String token = loginUser();
        String s1 = newSession(token, "T22-c1");
        String s2 = newSession(token, "T22-c2");
        chat(token, s1, "T22-c1-follow");   // s1 → 4 条
        // 空会话（0 条消息）：直插
        StudioAiSession empty = new StudioAiSession();
        empty.setUserId(lastUserId);
        empty.setTitle("T22-c-empty");
        aiSessionMapper.insert(empty);

        String list = getBody("/ai/session/list?pageSize=50", token);
        assertEquals(0, code(list));
        assertTrue(Pattern.compile("\"messageCount\":4[,}]").matcher(list).find(),
                "s1 的消息数应为 4：" + list);
        assertTrue(Pattern.compile("\"messageCount\":2[,}]").matcher(list).find(),
                "s2 的消息数应为 2：" + list);
        assertTrue(Pattern.compile("\"messageCount\":0[,}]").matcher(list).find(),
                "空会话应补 0：" + list);
        // SQL 对齐
        assertEquals(4, countMessages(Long.parseLong(s1)));
        assertEquals(2, countMessages(Long.parseLong(s2)));
    }

    // ==================== ⑤⑥ 删除 = 双逻辑删 + 删后行为 ====================

    @Test
    @DisplayName("删除：会话与消息同事务逻辑删（物理行保留）；删后 chat/history/重复删除 40400")
    void deleteCascadesLogicalAndLocksOut() throws Exception {
        String token = loginUser();
        String sid = newSession(token, "T22-d-first");
        chat(token, sid, "T22-d-second");
        long id = Long.parseLong(sid);
        assertEquals(4, countMessages(id), "删除前应有 4 条可见消息");

        // 本人删除
        assertEquals(0, code(postJson("/ai/session/delete", "{\"sessionId\":\"" + sid + "\"}", token)));

        // 双逻辑删：两表物理行都还在，但 deleted_at 全部非 0
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_session WHERE id=? AND deleted_at<>0", Integer.class, id),
                "会话应被逻辑删除");
        assertEquals(4, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=?", Integer.class, id),
                "消息物理行应保留（逻辑删而非物理删）");
        assertEquals(4, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=? AND deleted_at<>0", Integer.class, id),
                "消息应全部被级联逻辑删除");
        assertEquals(0, countMessages(id), "删除后可见消息应为 0");

        // 删后：chat / history 都走「不存在」路径，同码同提示
        String chatAfter = chat(token, sid, "T22-d-after");
        assertEquals(40400, code(chatAfter));
        assertEquals("会话不存在", message(chatAfter));
        assertEquals(40400, code(getBody("/ai/chat/history?sessionId=" + sid, token)));

        // 重复删除 → 40400
        String again = postJson("/ai/session/delete", "{\"sessionId\":\"" + sid + "\"}", token);
        assertEquals(40400, code(again));
        assertEquals("会话不存在", message(again));
    }

    // ==================== ⑦ 归属规则 ====================

    @Test
    @DisplayName("归属：他人会话 40400（同码同提示）；匿名会话凭 ID 可删")
    void ownershipRules() throws Exception {
        String tokenA = loginUser();
        String tokenB = loginUser();
        String aSid = newSession(tokenA, "T22-e-A 的会话");

        // B 删 A 的会话：与「不存在」完全相同的响应
        String cross = postJson("/ai/session/delete", "{\"sessionId\":\"" + aSid + "\"}", tokenB);
        assertEquals(40400, code(cross));
        assertEquals("会话不存在", message(cross));
        // 游客删 A 的会话：同样 40400
        assertEquals(40400, code(postJson("/ai/session/delete",
                "{\"sessionId\":\"" + aSid + "\"}", null)));
        // A 本人可以删（没有被上面的越权尝试影响）
        assertEquals(0, code(postJson("/ai/session/delete", "{\"sessionId\":\"" + aSid + "\"}", tokenA)));

        // 匿名会话：游客创建、任何持 ID 者（这里是匿名请求）可删
        String anonSid = newSession(null, "T22-e-游客会话");
        assertEquals(0, code(postJson("/ai/session/delete",
                "{\"sessionId\":\"" + anonSid + "\"}", null)), "匿名会话凭 ID 可删");
        assertEquals(40400, code(postJson("/ai/session/delete",
                "{\"sessionId\":\"" + anonSid + "\"}", null)), "重复删除 40400");
    }

    // ==================== ⑧ 参数校验 ====================

    @Test
    @DisplayName("参数：缺 sessionId / 空白 / 脏值 40000；不存在 40400")
    void paramValidation() throws Exception {
        assertEquals(40000, code(postJson("/ai/session/delete", "{}", null)), "缺参应 40000");
        assertEquals(40000, code(postJson("/ai/session/delete", "{\"sessionId\":\"   \"}", null)),
                "空白应 40000");
        assertEquals(40000, code(postJson("/ai/session/delete", "{\"sessionId\":\"abc\"}", null)),
                "脏值应 40000 而不是 50000");
        assertEquals(40400, code(postJson("/ai/session/delete",
                "{\"sessionId\":\"999999999999999999\"}", null)), "不存在应 40400");
    }

    // ==================== ⑨ 脱敏与序列化 ====================

    @Test
    @DisplayName("脱敏：无 userId / 审计字段；id 出字符串、messageCount 出数字")
    void sanitizedAndSerialized() throws Exception {
        String token = loginUser();
        newSession(token, "T22-g 脱敏会话");

        String list = getBody("/ai/session/list?pageSize=10", token);
        for (String forbidden : new String[]{"\"userId\"", "\"createdBy\"", "\"updatedBy\"",
                "\"createdAt\"", "\"deletedAt\""}) {
            assertFalse(list.contains(forbidden), "列表泄露了内部字段 " + forbidden + "：" + list);
        }
        // id 是字符串（19 位雪花）；messageCount 是数字；updatedAt 是统一时间格式
        assertTrue(Pattern.compile("\"id\":\"\\d{19}\"").matcher(list).find(), "id 应为字符串：" + list);
        assertTrue(Pattern.compile("\"messageCount\":\\d+[,}]").matcher(list).find(),
                "messageCount 应为数字：" + list);
        assertTrue(Pattern.compile("\"updatedAt\":\"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\"")
                        .matcher(list).find(),
                "updatedAt 应为 yyyy-MM-dd HH:mm:ss：" + list);
    }

    // ==================== 空列表边界 + 回归 ====================

    @Test
    @DisplayName("边界与回归：新用户空列表； 接口与既有白名单不受影响")
    void emptyListAndRegression() throws Exception {
        String token = loginUser();
        String empty = getBody("/ai/session/list", token);
        assertEquals(0, code(empty));
        assertTrue(empty.contains("\"records\":[]"), "新用户应返回空记录：" + empty);
        // Page 元数据是 long → 全局 Long→字符串约定下出字符串（与 records / messageCount 的数字形态不同）
        assertTrue(empty.contains("\"total\":\"0\""), "新用户 total 应为 0：" + empty);

        // 回归：匿名提问 / 历史仍正常；管理端接口仍 40100
        String anonChat = chat(null, null, "T22-h 回归提问");
        assertEquals(0, code(anonChat));
        String anonSid = sessionIdOf(anonChat);
        assertEquals(0, code(getBody("/ai/chat/history?sessionId=" + anonSid, null)));
        assertEquals(40100, code(getBody("/member/list/page", null)));
        assertEquals(0, code(getBody("/member/list", null)));
    }
}