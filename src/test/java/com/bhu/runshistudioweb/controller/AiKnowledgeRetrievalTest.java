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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RAG 检索接入问答验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖八条线：
 * <ol>
 *     <li>命中注入 + 溯源：资料拼进本次 user 消息、响应 sources 字段完整（docId 字符串、manual 来源无 sourceId）；</li>
 *     <li>无命中：消息原样、sources 空数组（字段恒存在）；</li>
 *     <li>阈值与顺序：低于 min-score 的命中被过滤、命中按相似度降序；</li>
 *     <li>topK 配置化：条数上限跟随 {@code studio.ai.rag-top-k}；</li>
 *     <li>悬挂容忍：块/文档被逻辑删除的命中静默跳过（R2 收口）；</li>
 *     <li>SSE：事件序列 meta → sources → delta → done，sources 恒发（含无命中空数组）且流式链路同样注入；</li>
 *     <li>检索失败降级：Embedding 故障 → 照常回答、sources 空、配额正常 +1（不新增错误码）；</li>
 *     <li>位置纪律：配额超限请求在检索之前被拦下（不产生多余的 Embedding 调用）。</li>
 * </ol>
 *
 * <p><b>本类刻意不加 {@code @Transactional}</b>（同 AiChatStreamTest 的理由）：
 * 流式接口运行在虚拟线程上、写入是独立事务，测试事务里未提交的数据它看不到
 * （DESIGN 场景 J 已登记该现象）；因此本类数据真实提交、{@code @AfterEach} 按 T26 前缀清理。
 *
 * <p><b>向量隔离技巧</b>：Stub 按文本里的记号 {@code V{n}-{EXACT|NEAR|FAR}} 把向量放进第 n 个
 * 二维平面：EXACT 与问题同向（余弦 1.0）、NEAR 夹角余弦 0.6、FAR 余弦 0.4（低于 0.5 阈值）；
 * 不同平面互相正交。各用例使用不同平面，因此互不干扰、也不依赖执行顺序。
 *
 * <p>类名以 {@code Ai} 开头沿用排序约束（新建上下文的测试类必须排在 CertificateCrudTest 之前），
 * 详见 AiKnowledgeIngestTest 类注释。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiKnowledgeRetrievalTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /** 起配额计数走 Redis，测试里读「用量」必须取合并值（DB 基准 + Redis 增量） */
    @Resource
    private AiQueryCountManager aiQueryCountManager;

    // ==================== 本地 Stub（Embedding + Chat 双端点） ====================

    private static HttpServer stub;

    /** /embeddings 调用次数：用于取「超限请求不触发检索」的证据 */
    private static final AtomicInteger embeddingCalls = new AtomicInteger();

    /** 故障注入：true 时 /embeddings 返回 500（模拟向量服务不可用） */
    private static volatile boolean failEmbeddings = false;

    /** 最近一次 chat/completions 请求体（取「资料是否真的送进模型」的证据） */
    private static volatile String lastRequestBody;

    private static final int DIM = 18;

    private static final Pattern PLANE_MARKER = Pattern.compile("V(\\d)-(EXACT|NEAR|FAR)");

    private static final JsonMapper STUB_JSON = JsonMapper.builder().build();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);

        stub.createContext("/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            embeddingCalls.incrementAndGet();
            if (failEmbeddings) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            JsonNode input = STUB_JSON.readTree(body).get("input");
            List<String> texts = new ArrayList<>();
            if (input.isArray()) {
                input.forEach(node -> texts.add(node.asString()));
            } else {
                texts.add(input.asString());
            }
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) {
                    data.append(',');
                }
                float[] vector = vec(texts.get(i));
                data.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
                for (int j = 0; j < vector.length; j++) {
                    if (j > 0) {
                        data.append(',');
                    }
                    data.append(vector[j]);
                }
                data.append("]}");
            }
            String resp = "{\"object\":\"list\",\"data\":[" + data
                    + "],\"model\":\"test-embedding\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
            writeJson(exchange, resp);
        });

        stub.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody = body;
            String compact = body.replace(" ", "").replace("\r", "").replace("\n", "");
            if (compact.contains("\"stream\":true")) {
                String sse = chunk("T26-流式", false) + chunk("回答", true) + "data: [DONE]\n\n";
                byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else {
                writeJson(exchange, "{\"choices\":[{\"message\":{\"content\":\"T26-stub-回答\"}}]}");
            }
        });

        stub.start();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String resp) throws IOException {
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String chunk(String content, boolean last) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + content + "\"},\"finish_reason\":" + (last ? "\"stop\"" : "null") + "}]}\n\n";
    }

    /**
     * 平面向量：V{n}-EXACT 与问题同向（relevance 1.0）；NEAR 余弦 0.6（relevance 0.8）；
     * FAR 余弦 0.4（relevance 0.7，低于 0.75 阈值被过滤）；
     * 无记号则落在保留维上（与所有平面正交，relevance 0.5，同样被过滤）
     */
    private static float[] vec(String text) {
        float[] vector = new float[DIM];
        Matcher matcher = PLANE_MARKER.matcher(text);
        if (matcher.find()) {
            int plane = Integer.parseInt(matcher.group(1));
            switch (matcher.group(2)) {
                case "EXACT" -> vector[plane * 2] = 1f;
                case "NEAR" -> {
                    vector[plane * 2] = 0.6f;
                    vector[plane * 2 + 1] = 0.8f;
                }
                default -> {
                    vector[plane * 2] = 0.4f;
                    vector[plane * 2 + 1] = 0.9165152f;
                }
            }
            return vector;
        }
        vector[DIM - 1] = 1f;
        return vector;
    }

    @DynamicPropertySource
    static void stubProps(DynamicPropertyRegistry registry) {
        int port = stub.getAddress().getPort();
        registry.add("studio.ai.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("studio.ai.api-key", () -> "test-key");
        registry.add("studio.ai.model", () -> "test-model");
        registry.add("studio.ai.embedding-model", () -> "test-embedding");
        // 配额调小：让「超限请求在检索之前被拦下」的用例不必真的问 100 次
        registry.add("studio.ai.query-limit", () -> 3);
        // 刻意用非默认值（默认 5）：同时证明 topK 真的来自配置
        registry.add("studio.ai.rag-top-k", () -> 2);
        // ⚠️ LangChain4j 的 minScore 语义是「相关度 relevance」而非裸余弦：relevance = (1 + cos) / 2
        // （正交向量 = 0.5、EXACT(cos 1.0) = 1.0、NEAR(cos 0.6) = 0.8、FAR(cos 0.4) = 0.7）。
        // 因此这里配 0.75：恰好卡在「过滤正交与弱相关、保留接近及以上」的位置
        // （线上默认 0.5 等于不过滤——正交命中也会以 0.5 通过，已在验收报告中登记）
        registry.add("studio.ai.rag-min-score", () -> 0.75);
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void resetStubState() {
        failEmbeddings = false;
        lastRequestBody = null;
    }

    /** 按前缀清理（非事务类必须显式清理；先子后父） */
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM studio_ai_message WHERE session_id IN "
                + "(SELECT id FROM studio_ai_session WHERE title LIKE 'T26%' "
                + "OR user_id IN (SELECT id FROM sys_user WHERE user_account LIKE 'T26%'))");
        jdbcTemplate.update("DELETE FROM studio_ai_session WHERE title LIKE 'T26%' "
                + "OR user_id IN (SELECT id FROM sys_user WHERE user_account LIKE 'T26%')");
        jdbcTemplate.update("DELETE FROM studio_knowledge_chunk WHERE doc_id IN "
                + "(SELECT id FROM studio_knowledge_doc WHERE title LIKE 'T26%')");
        jdbcTemplate.update("DELETE FROM studio_knowledge_doc WHERE title LIKE 'T26%'");
        jdbcTemplate.update("DELETE FROM sys_user WHERE user_account LIKE 'T26%'");
    }

    // ==================== 工具方法 ====================

    private String adminToken() throws Exception {
        String account = "T26adm_" + (System.nanoTime() % 100000);
        SysUser admin = new SysUser();
        admin.setUserAccount(account);
        admin.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        admin.setUserName(account);
        admin.setUserRole(UserRoleConstant.ADMIN);
        admin.setUserStatus(0);
        sysUserMapper.insert(admin);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "管理员登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 最近一次登录的账户（供配额断言） */
    private String lastAccount;

    private String loginUser() throws Exception {
        String account = "T26usr_" + (System.nanoTime() % 100000);
        lastAccount = account;
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.USER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    private int queryCountOf(String account) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT ai_query_count FROM sys_user WHERE user_account=?", Integer.class, account);
        // 起计数先进 Redis、由定时任务回刷 DB：
        // 「用户实际用量」= DB 基准 + Redis 未落库增量，只读 DB 会读到回刷前的旧值
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE user_account=?", Long.class, account);
        return aiQueryCountManager.merge(userId, count);
    }

    private String postJson(String path, String json, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private JsonNode dataOf(String body) {
        return jsonMapper.readTree(body).get("data");
    }

    /** 手工录入一篇文档（管理端接口，与 同一入口） */
    private long ingest(String title, String content) throws Exception {
        String body = postJson("/knowledge/doc/manual",
                "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}", adminToken());
        assertEquals("00000", code(body), "入库应成功：" + body);
        return Long.parseLong(dataOf(body).get("docId").asString());
    }

    /** 取最近一次 chat 请求里「最后一条消息」的内容（注入是否发生的唯一取证点） */
    private String lastUserMessageOfChatRequest() {
        JsonNode messages = jsonMapper.readTree(lastRequestBody).get("messages");
        return messages.get(messages.size() - 1).get("content").asString();
    }

    private String chatAnon(String message) throws Exception {
        return postJson("/ai/chat", "{\"message\":\"" + message + "\"}", null);
    }

    private String chatLogged(String message, String token) throws Exception {
        return postJson("/ai/chat", "{\"message\":\"" + message + "\"}", token);
    }

    /** 发起流式提问并等到流结束（轮询到 done/error 帧且连续两次读取一致） */
    private String streamFrames(String json, String token) throws Exception {
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
        return frames;
    }

    /** 取某类事件的首个 data 负载 */
    private String dataOf(String frames, String eventName) {
        Matcher m = Pattern.compile("(?m)^event:\\s*" + eventName + "\\s*\\r?\\ndata:\\s*(.*?)\\s*\\r?$")
                .matcher(frames);
        assertTrue(m.find(), "缺少 " + eventName + " 事件：" + frames);
        return m.group(1);
    }

    // ==================== ① 命中注入 + 溯源 ====================

    @Test
    @DisplayName("命中注入与溯源：资料拼进本次 user 消息；响应 sources 字段完整；历史里仍是用户原话")
    void hitInjectsReferencesAndReturnsSources() throws Exception {
        ingest("T26-V1项目文档", "V1-EXACT 润石工作室的明星项目是「星轨计划」，队长是小石。");
        String question = "T26 V1-EXACT 问题：工作室的明星项目叫什么？";

        String body = chatAnon(question);

        assertEquals("00000", code(body), "提问应成功：" + body);
        // 注入取证：资料确实被拼进了送进模型的 user 消息
        String userContent = lastUserMessageOfChatRequest();
        assertTrue(userContent.startsWith("参考资料："), "应带参考资料前缀：" + userContent);
        assertTrue(userContent.contains("T26-V1项目文档"), "应含文档标题：" + userContent);
        assertTrue(userContent.contains("星轨计划"), "应含块正文：" + userContent);
        assertTrue(userContent.endsWith("用户问题：" + question), "应以用户问题收尾：" + userContent);

        // 溯源取证：docId 是字符串（雪花 ID 精度约定）、manual 来源无 sourceId、score 趋近 1
        JsonNode sources = dataOf(body).get("sources");
        assertNotNull(sources, "sources 字段必须存在");
        assertEquals(1, sources.size());
        assertTrue(sources.get(0).get("docId").asString().matches("\\d{19}"), "docId 应为字符串：" + body);
        assertEquals("T26-V1项目文档", sources.get(0).get("title").asString());
        assertEquals("manual", sources.get(0).get("sourceType").asString());
        assertTrue(sources.get(0).get("sourceId").isNull(), "manual 来源的 sourceId 应为 null");
        assertTrue(sources.get(0).get("score").asDouble() > 0.99, "EXACT 向量相似度应趋近 1：" + body);

        // 历史落库：user 消息是原话，注入版不写库
        long sessionId = Long.parseLong(dataOf(body).get("sessionId").asString());
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_ai_message WHERE session_id=? AND role='user' AND content=?",
                Integer.class, sessionId, question), "历史里应保留用户原话（不含参考资料）");
    }

    // ==================== ② 无命中 ====================

    @Test
    @DisplayName("无相关命中：消息原样、sources 为空数组（字段恒存在，不是 null）")
    void noRelevantHitKeepsOriginalMessage() throws Exception {
        // V6 平面没有任何已入库向量 → 正交（relevance 0.5 < 0.75 阈值），等价于「知识库里没有相关资料」
        String question = "T26 V6-EXACT 问题：知识库里没有这个主题";

        String body = chatAnon(question);

        assertEquals("00000", code(body), "提问应成功：" + body);
        assertEquals(question, lastUserMessageOfChatRequest(), "无命中时消息必须原样（不注入）");

        JsonNode sources = dataOf(body).get("sources");
        assertNotNull(sources, "sources 字段必须存在");
        assertEquals(0, sources.size(), "无命中时应为空数组：" + body);
    }

    // ==================== ③ 阈值与顺序 ====================

    @Test
    @DisplayName("阈值与顺序：低于 min-score 的命中被过滤；结果按相似度降序（EXACT → NEAR）")
    void minScoreFiltersAndOrdersByScore() throws Exception {
        ingest("T26-V2精确文档", "V2-EXACT 精确匹配的正文");
        ingest("T26-V2接近文档", "V2-NEAR 语义接近的正文");
        ingest("T26-V2远离文档", "V2-FAR 弱相关的正文");

        String body = chatAnon("T26 V2-EXACT 问题：找精确资料");

        assertEquals("00000", code(body), "提问应成功：" + body);
        JsonNode sources = dataOf(body).get("sources");
        assertEquals(2, sources.size(), "relevance 0.7（cos 0.4）的弱相关命中应被 0.75 阈值过滤：" + body);
        assertEquals("T26-V2精确文档", sources.get(0).get("title").asString(), "相似度降序：精确在前");
        assertEquals("T26-V2接近文档", sources.get(1).get("title").asString());
        // score 是 relevance = (1 + cos) / 2：NEAR 的 cos 为 0.6 → relevance 0.8
        assertTrue(Math.abs(sources.get(1).get("score").asDouble() - 0.8) < 0.02,
                "NEAR 的相关度应约 0.8：" + body);
        assertFalse(body.contains("T26-V2远离文档"), "被过滤的命中不应出现在响应里：" + body);
    }

    // ==================== ④ topK 配置化 ====================

    @Test
    @DisplayName("topK 配置化：条数上限跟随 studio.ai.rag-top-k（本类配为 2）")
    void topKFromConfigLimitsSources() throws Exception {
        ingest("T26-V3文档甲", "V3-EXACT 甲");
        ingest("T26-V3文档乙", "V3-EXACT 乙");
        ingest("T26-V3文档丙", "V3-EXACT 丙");

        String body = chatAnon("T26 V3-EXACT 问题：找齐三篇");

        assertEquals("00000", code(body), "提问应成功：" + body);
        JsonNode sources = dataOf(body).get("sources");
        assertEquals(2, sources.size(), "应被 rag-top-k=2 截断（默认值是 5）：" + body);
    }

    // ==================== ⑤ 悬挂容忍 ====================

    @Test
    @DisplayName("悬挂容忍：块/文档被逻辑删除的命中静默跳过，提问照常成功（R2 收口）")
    void danglingHitsAreSkippedSilently() throws Exception {
        long docA = ingest("T26-V4块被删", "V4-EXACT 甲文档正文");
        long docB = ingest("T26-V4文档被删", "V4-EXACT 乙文档正文");
        // 直改库模拟悬挂：A 的块被删、B 的文档被删（向量仍在库里残留）
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE studio_knowledge_chunk SET deleted_at=? WHERE doc_id=?", now, docA);
        jdbcTemplate.update("UPDATE studio_knowledge_doc SET deleted_at=? WHERE id=?", now, docB);

        String question = "T26 V4-EXACT 问题：查已删除的资料";
        String body = chatAnon(question);

        assertEquals("00000", code(body), "悬挂数据不应让提问失败：" + body);
        assertEquals(0, dataOf(body).get("sources").size(), "两条命中都查不到实体，应全部跳过：" + body);
        assertEquals(question, lastUserMessageOfChatRequest(), "全部跳过等价于无命中，不应注入");
    }

    // ==================== ⑥ SSE 事件序列 ====================

    @Test
    @DisplayName("SSE：meta → sources → delta → done；命中注入且载荷带标题；无命中恒发空数组")
    void sseEventOrderAndSourcesPayload() throws Exception {
        ingest("T26-V5流式文档", "V5-EXACT 流式回答要依据的资料正文");

        String hit = streamFrames("{\"message\":\"T26 V5-EXACT 流式问题\"}", null);
        int iMeta = hit.indexOf("event:meta");
        int iSources = hit.indexOf("event:sources");
        int iDelta = hit.indexOf("event:delta");
        int iDone = hit.indexOf("event:done");
        assertTrue(iMeta >= 0 && iSources > iMeta && iDelta > iSources && iDone > iDelta,
                "事件顺序应为 meta → sources → delta → done：" + hit);

        JsonNode hitPayload = jsonMapper.readTree(dataOf(hit, "sources"));
        assertEquals(1, hitPayload.get("sources").size(), "命中时应带溯源：" + hit);
        assertEquals("T26-V5流式文档", hitPayload.get("sources").get(0).get("title").asString());
        assertTrue(lastUserMessageOfChatRequest().contains("参考资料"), "流式链路同样应注入资料");
        assertTrue(lastUserMessageOfChatRequest().contains("T26-V5流式文档"));

        // 无命中：sources 恒发、载荷为空数组（前端无需靠「等一会儿」判断有没有资料）
        String miss = streamFrames("{\"message\":\"T26 V7-EXACT 流式无命中\"}", null);
        assertTrue(miss.contains("event:sources"), "无命中也必须发 sources 事件：" + miss);
        JsonNode missPayload = jsonMapper.readTree(dataOf(miss, "sources"));
        assertEquals(0, missPayload.get("sources").size(), "无命中时应为空数组：" + miss);
        assertEquals("T26 V7-EXACT 流式无命中", lastUserMessageOfChatRequest(), "无命中时消息原样");
    }

    // ==================== ⑦ 检索失败降级 ====================

    @Test
    @DisplayName("检索失败降级：Embedding 故障 → 照常回答、sources 空、配额正常 +1（不新增错误码）")
    void retrievalFailureDegradesWithoutBlocking() throws Exception {
        String token = loginUser();
        failEmbeddings = true;

        String body = chatLogged("T26-降级提问：向量服务不可用", token);

        assertEquals("00000", code(body), "检索失败不应阻断回答：" + body);
        assertTrue(body.contains("T26-stub-回答"), "应返回模型的回答：" + body);
        assertEquals(0, dataOf(body).get("sources").size(), "降级时 sources 为空数组：" + body);
        assertEquals(1, queryCountOf(lastAccount), "回答成功应正常计数（检索失败≠回答失败）");
    }

    // ==================== ⑧ 位置纪律 ====================

    @Test
    @DisplayName("位置纪律：配额超限请求在检索之前被拦下（不产生多余的 Embedding 调用）")
    void overLimitRejectsBeforeRetrieval() throws Exception {
        String token = loginUser();
        // 配额上限 3：用掉 3 次（每次都真实经过检索）
        for (int i = 1; i <= 3; i++) {
            assertEquals("00000", code(chatLogged("T26-配额-" + i, token)), "第 " + i + " 次应成功");
        }
        int callsAfterThree = embeddingCalls.get();

        String rejected = chatLogged("T26-配额-4", token);

        assertEquals("A0501", code(rejected), "第 4 次应超限：" + rejected);
        assertEquals(callsAfterThree, embeddingCalls.get(),
                "超限请求不应触发检索——检索必须排在配额预检之后");
    }
}