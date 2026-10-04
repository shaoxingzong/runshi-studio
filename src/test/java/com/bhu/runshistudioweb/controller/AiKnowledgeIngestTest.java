package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.manager.KnowledgeBaseManager;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioProjectMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RAG 文档入库管道验收测试
 *
 * author: shaoshing
 *
 * <p>AI 调用用本地 Embedding Stub（JDK {@link HttpServer}）代替真实向量服务：
 * <ul>
 *     <li>只认识 {@code /embeddings}，按 OpenAI 兼容格式返回<b>确定性向量</b>
 *     （由文本 hashCode 派生，测试里可以用同一函数重建某个块的向量去向量库检索）；
 *     同时记录调用次数——用来取证「一次入库只发一次批量请求」；</li>
 *     <li>{@code failEmbeddings=true} 时返回 500，用于验证失败路径
 *     （新文档不留残留且可重试；重建失败时旧块与旧向量原样保留）。</li>
 * </ul>
 *
 * <p>覆盖十二条线：
 * <ol>
 *     <li>手工录入全链路：批量向量化 → doc 行 + N 块（embedding_id 齐全、序号连续、长正文正确多块）；</li>
 *     <li>手工录入参数校验（标题/正文/超长边界）→ 40000；</li>
 *     <li>手工录入不幂等：同内容两次 → 两篇文档；</li>
 *     <li>同步项目（首次）：溯源字段完整、正文含「标题/摘要/正文」拼装；</li>
 *     <li>同步幂等：内容未变 → skipped=true、零 Embedding、零写库（raw SQL 取证）；</li>
 *     <li>重建：旧块逻辑删、新块替换、chunk_count 刷新、旧向量清理（向量库检索取证）；</li>
 *     <li>源已删除：40400 + 已有文档标 status=2 + 旧块保留（R2 收口）；</li>
 *     <li>同步参数校验：manual 被拒、非法 sourceType、sourceId 非法 → 40000；</li>
 *     <li>权限：匿名 40100、普通用户 40101、admin 放行（白名单零改动）；</li>
 *     <li>拼装规则：成员中文标签行；证书级别/类型转中文文案（不是库里的英文值）；</li>
 *     <li>向量化失败（首次）：50001 + 无残留行，且失败可重试；</li>
 *     <li>向量化失败（重建）：50001 + 旧块与旧向量原样保留 + status=2，恢复后重建成功。</li>
 * </ol>
 *
 * <p><b>类名以 {@code Ai} 开头是刻意的「排序约束」，改动前先读这段</b>：
 * 本类通过 {@code @DynamicPropertySource} 指向自己的本地 Embedding Stub（端口不同 → 属性集不同），
 * 会新建一个 Spring 测试上下文。而 Sa-Token 用<b>静态</b> {@code SaManager} 持有 DAO，
 * 它总是绑定「最后创建的上下文」；Boot 4.1 的测试框架在类切换时还会 stop 先前上下文
 * （实测日志：{@code DefaultLifecycleProcessor: Stopping beans...}，
 * {@code Bean 'redisConnectionFactory' completed its stop process}）。
 * 因此「新建上下文的测试类」必须全部排在 {@code CertificateCrudTest} 之前——
 * 它是第一个使用默认 MockMvc 上下文的类，该上下文会被其后所有测试类共享，
 * 而静态 DAO 必须始终指向「当前正在使用的上下文」。
 * 本类若排到它之后，后续所有登录类测试都会 50000 失败（本次验收已实测复现）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiKnowledgeIngestTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioProjectMapper studioProjectMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private StudioCertificateMapper studioCertificateMapper;

    @Resource
    private KnowledgeBaseManager knowledgeBaseManager;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    // ==================== 本地 Embedding Stub ====================

    private static HttpServer stub;

    /** /embeddings 被调用次数（批量纪律取证：一次入库应只 +1，而不是每块 +1） */
    private static final AtomicInteger embeddingCalls = new AtomicInteger();

    /** 故障注入开关：true 时 /embeddings 返回 500 */
    private static volatile boolean failEmbeddings = false;

    /** 向量维度：测试自定，与真实模型无关 */
    private static final int DIM = 8;

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
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
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
        registry.add("studio.ai.embedding-model", () -> "test-embedding");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void resetStubState() {
        failEmbeddings = false;
    }

    /**
     * 与 Stub 共用的确定性向量函数：测试里用它重建某个块的向量，
     * 再拿回向量库检索——这是验证「旧向量是否真的被清理/保留」的唯一手段
     */
    private static float[] vec(String text) {
        float[] vector = new float[DIM];
        int hash = text.hashCode();
        for (int i = 0; i < DIM; i++) {
            vector[i] = ((hash >> (i * 4)) & 0x7F) / 128f;
        }
        return vector;
    }

    // ==================== 工具方法 ====================

    private String adminToken() throws Exception {
        String account = "T25adm_" + (System.nanoTime() % 100000);
        SysUser admin = new SysUser();
        admin.setUserAccount(account);
        admin.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        admin.setUserName(account);
        admin.setUserRole(UserRoleConstant.ADMIN);
        admin.setUserStatus(0);
        sysUserMapper.insert(admin);
        return loginAndExtractToken(account);
    }

    private String userToken() throws Exception {
        String account = "T25usr_" + (System.nanoTime() % 100000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(UserRoleConstant.USER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return loginAndExtractToken(account);
    }

    private String loginAndExtractToken(String account) throws Exception {
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    private String postJson(String path, String json, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private int code(String body) {
        int start = body.indexOf("\"code\":") + 7;
        return Integer.parseInt(body.substring(start, body.indexOf(',', start)));
    }

    private JsonNode dataOf(String body) {
        return jsonMapper.readTree(body).get("data");
    }

    /** 转义出合法 JSON 字符串字面量（正文含换行，不能直接拼） */
    private String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private String manualJson(String title, String content) {
        return "{\"title\":\"" + jsonEscape(title) + "\",\"content\":\"" + jsonEscape(content) + "\"}";
    }

    private String syncJson(String sourceType, Object sourceId) {
        return "{\"sourceType\":\"" + sourceType + "\",\"sourceId\":" + sourceId + "}";
    }

    /** 长正文（约 1200 字、12 段）：用于验证真实的「多块切分 + 顺序」 */
    private String longContent(String marker) {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            if (i > 1) {
                content.append("\n\n");
            }
            content.append(marker).append("第").append(i).append("段：")
                    .append("这是用于验证切分边界的段落文本，内容需要足够长才能产生多个块。".repeat(3));
        }
        return content.toString();
    }

    private long createProject(String title, String content) {
        StudioProject project = new StudioProject();
        project.setTitle(title);
        project.setDescription(title + "-摘要");
        project.setContent(content);
        // 无外键约束：这里只需要一个非空队长 ID，与知识库链路无关
        project.setLeaderId(1L);
        project.setStatus(1);
        project.setSortOrder(0);
        studioProjectMapper.insert(project);
        return project.getId();
    }

    private long createMember(String name) {
        StudioMember member = new StudioMember();
        member.setName(name);
        member.setGradeYear(2023);
        member.setMajor("软件工程");
        member.setDirection("Java后端");
        member.setSummary(name + "-简介");
        member.setTeamPosition("member");
        member.setMemberStatus(0);
        member.setSortOrder(0);
        studioMemberMapper.insert(member);
        return member.getId();
    }

    private long createCertificate(String title) {
        StudioCertificate certificate = new StudioCertificate();
        certificate.setTitle(title);
        certificate.setAwardLevel("national");
        certificate.setAwardType("soft_copyright");
        certificate.setAwardDate(LocalDate.of(2024, 5, 1));
        certificate.setImageUrl("http://example.com/t25.png");
        certificate.setSortOrder(0);
        studioCertificateMapper.insert(certificate);
        return certificate.getId();
    }

    /** 从向量库检索（按重建的向量找最近邻），用于验证旧向量的清理/保留 */
    private List<EmbeddingMatch<TextSegment>> searchByText(String text) {
        return knowledgeBaseManager.getEmbeddingStore().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vec(text)))
                        .maxResults(20)
                        .build()).matches();
    }

    private int countDocsByTitle(String title) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_knowledge_doc WHERE title=? AND deleted_at=0", Integer.class, title);
        return count == null ? 0 : count;
    }

    private int countDocsBySource(String sourceType, long sourceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_knowledge_doc WHERE source_type=? AND source_id=? AND deleted_at=0",
                Integer.class, sourceType, sourceId);
        return count == null ? 0 : count;
    }

    private int rawDocStatus(long docId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM studio_knowledge_doc WHERE id=?", Integer.class, docId);
        return status == null ? -1 : status;
    }

    private int rawLiveChunkCount(long docId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_knowledge_chunk WHERE doc_id=? AND deleted_at=0",
                Integer.class, docId);
        return count == null ? 0 : count;
    }

    private int rawAllChunkCount(long docId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_knowledge_chunk WHERE doc_id=?", Integer.class, docId);
        return count == null ? 0 : count;
    }

    private String rawChunkContent(long docId, int chunkIndex) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM studio_knowledge_chunk WHERE doc_id=? AND chunk_index=? AND deleted_at=0",
                String.class, docId, chunkIndex);
    }

    private String rawChunkEmbeddingId(long docId, int chunkIndex) {
        return jdbcTemplate.queryForObject(
                "SELECT embedding_id FROM studio_knowledge_chunk WHERE doc_id=? AND chunk_index=? AND deleted_at=0",
                String.class, docId, chunkIndex);
    }

    // ==================== ① 手工录入全链路 ====================

    @Test
    @DisplayName("手工录入：一次批量向量化 → doc 行 + N 块（序号连续、embedding_id 齐全）+ 长正文多块切分")
    void manualIngestFullPipeline() throws Exception {
        String token = adminToken();
        String title = "T25手工文档_" + System.nanoTime();
        String content = longContent("T25-手工");
        int callsBefore = embeddingCalls.get();

        String body = postJson("/knowledge/doc/manual", manualJson(title, content), token);

        assertEquals(0, code(body), "入库应成功：" + body);
        // docId 必须是「字符串形式的 19 位雪花 ID」（全局 JsonConfig 约定，防止 JS 精度丢失）
        assertTrue(body.matches("(?s).*\"docId\":\"\\d{19}\".*"), "docId 应为字符串：" + body);

        JsonNode data = dataOf(body);
        long docId = Long.parseLong(data.get("docId").asString());
        int chunkCount = data.get("chunkCount").asInt();
        assertEquals(title, data.get("title").asString());
        assertEquals(1, data.get("status").asInt());
        assertFalse(data.get("skipped").asBoolean());
        assertFalse(data.get("rebuilt").asBoolean());
        assertTrue(chunkCount >= 3, "约 1200 字正文应切出多块，实际 " + chunkCount);

        // DB 取证：doc 行字段齐全（manual 来源的 source_id 必须为 NULL）
        Map<String, Object> docRow = jdbcTemplate.queryForMap(
                "SELECT source_type, source_id, content_hash, status, chunk_count "
                        + "FROM studio_knowledge_doc WHERE id=?", docId);
        assertEquals("manual", docRow.get("source_type"));
        assertNull(docRow.get("source_id"), "manual 来源的 source_id 必须为 NULL");
        assertEquals(64, String.valueOf(docRow.get("content_hash")).length(), "SHA-256 应为 64 位 hex");
        assertEquals(1, (int) docRow.get("status"));
        assertEquals(chunkCount, (int) docRow.get("chunk_count"));

        // DB 取证：块行齐全 —— 序号从 0 连续、embedding_id 非空、单块不超长
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT chunk_index, content, embedding_id FROM studio_knowledge_chunk "
                        + "WHERE doc_id=? AND deleted_at=0 ORDER BY chunk_index", docId);
        assertEquals(chunkCount, chunks.size());
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, (int) chunks.get(i).get("chunk_index"), "块序号应从 0 连续");
            assertNotNull(chunks.get(i).get("embedding_id"), "块 " + i + " 的 embedding_id 不能为空");
            String chunkText = (String) chunks.get(i).get("content");
            assertTrue(chunkText.length() <= 500, "单块不得超过 500 字：" + chunkText.length());
            joined.append(chunkText);
        }
        assertTrue(joined.indexOf("第1段") < joined.indexOf("第6段")
                && joined.indexOf("第6段") < joined.indexOf("第12段"), "块拼回应保持原文顺序");

        // 批量纪律：一次入库只发一次 /embeddings（不是每块一次）
        assertEquals(1, embeddingCalls.get() - callsBefore, "一次入库应只有一个 embeddings 请求");
    }

    @Test
    @DisplayName("手工录入参数校验：空白标题 / 空白正文 / 标题超 128 / 正文超 20000 → 40000")
    void manualValidationRejectsBadInput() throws Exception {
        String token = adminToken();

        assertEquals(40000, code(postJson("/knowledge/doc/manual", manualJson("   ", "正文"), token)));
        assertEquals(40000, code(postJson("/knowledge/doc/manual", manualJson("标题", "   "), token)));
        assertEquals(40000, code(postJson("/knowledge/doc/manual", manualJson("t".repeat(129), "正文"), token)));
        assertEquals(40000, code(postJson("/knowledge/doc/manual", manualJson("标题", "文".repeat(20001)), token)));
    }

    @Test
    @DisplayName("手工录入不幂等：相同内容两次 → 两篇文档（语义如此，不是缺陷）")
    void manualIngestIsNotIdempotent() throws Exception {
        String token = adminToken();
        String title = "T25重复_" + System.nanoTime();

        long first = Long.parseLong(dataOf(postJson("/knowledge/doc/manual",
                manualJson(title, "同一份正文"), token)).get("docId").asString());
        long second = Long.parseLong(dataOf(postJson("/knowledge/doc/manual",
                manualJson(title, "同一份正文"), token)).get("docId").asString());

        assertNotEquals(first, second, "两次手工录入应产生两篇文档");
        assertEquals(2, countDocsByTitle(title));
    }

    // ==================== ④⑤⑥ 业务来源同步 ====================

    @Test
    @DisplayName("同步项目（首次）：doc 溯源字段完整；正文含「标题/摘要/正文」三段拼装")
    void syncProjectCreatesDocument() throws Exception {
        String token = adminToken();
        String title = "T25项目_" + System.nanoTime();
        long projectId = createProject(title, "T25项目正文内容");

        String body = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);

        assertEquals(0, code(body), "同步应成功：" + body);
        JsonNode data = dataOf(body);
        long docId = Long.parseLong(data.get("docId").asString());
        assertFalse(data.get("skipped").asBoolean());
        assertFalse(data.get("rebuilt").asBoolean(), "首次同步是新建，不是重建");

        Map<String, Object> docRow = jdbcTemplate.queryForMap(
                "SELECT title, source_type, source_id, status FROM studio_knowledge_doc WHERE id=?", docId);
        assertEquals(title, docRow.get("title"));
        assertEquals("project", docRow.get("source_type"));
        assertEquals(projectId, ((Number) docRow.get("source_id")).longValue());

        String chunk0 = rawChunkContent(docId, 0);
        assertTrue(chunk0.contains("标题：" + title), "正文应含标题行：" + chunk0);
        assertTrue(chunk0.contains("摘要：" + title + "-摘要"), "正文应含摘要行：" + chunk0);
        assertTrue(chunk0.contains("正文：T25项目正文内容"), "正文应含正文行：" + chunk0);
    }

    @Test
    @DisplayName("同步幂等：内容未变 → skipped=true、零 Embedding 调用、零写库（raw SQL 取证）")
    void syncIsIdempotentWhenContentUnchanged() throws Exception {
        String token = adminToken();
        long projectId = createProject("T25幂等项目_" + System.nanoTime(), "T25幂等正文");

        String first = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);
        long docId = Long.parseLong(dataOf(first).get("docId").asString());
        int callsAfterFirst = embeddingCalls.get();
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM studio_knowledge_doc WHERE id=?",
                (rs, rowNum) -> rs.getString(1), docId);
        int chunksBefore = rawLiveChunkCount(docId);

        String second = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);

        assertEquals(0, code(second));
        JsonNode data = dataOf(second);
        assertTrue(data.get("skipped").asBoolean(), "内容未变应跳过：" + second);
        assertFalse(data.get("rebuilt").asBoolean());
        assertEquals(docId, Long.parseLong(data.get("docId").asString()), "仍应指向同一篇文档");

        assertEquals(callsAfterFirst, embeddingCalls.get(), "跳过时不应再调用 Embedding");
        assertEquals(chunksBefore, rawLiveChunkCount(docId), "跳过时不应动块");
        String updatedAtAfter = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM studio_knowledge_doc WHERE id=?",
                (rs, rowNum) -> rs.getString(1), docId);
        assertEquals(updatedAtBefore, updatedAtAfter, "跳过时不应发生任何写库");
    }

    @Test
    @DisplayName("同步重建：内容变更 → 旧块逻辑删、新块替换、chunk_count 刷新、旧向量清理（向量库检索取证）")
    void syncRebuildReplacesChunksAndCleansOldVectors() throws Exception {
        String token = adminToken();
        long projectId = createProject("T25重建项目_" + System.nanoTime(), "T25-重建-旧内容");
        String docIdText = dataOf(postJson("/knowledge/doc/sync", syncJson("project", projectId), token))
                .get("docId").asString();
        long docId = Long.parseLong(docIdText);

        String oldChunk0 = rawChunkContent(docId, 0);
        String oldEmbeddingId = rawChunkEmbeddingId(docId, 0);
        int oldLiveCount = rawLiveChunkCount(docId);
        int callsBeforeRebuild = embeddingCalls.get();

        // 模拟运营修改项目正文（新内容更长 → 块数变化）
        StudioProject update = new StudioProject();
        update.setId(projectId);
        update.setContent(longContent("T25-重建-新"));
        studioProjectMapper.updateById(update);

        String body = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);

        assertEquals(0, code(body), "重建应成功：" + body);
        JsonNode data = dataOf(body);
        assertTrue(data.get("rebuilt").asBoolean(), "内容变更应走重建：" + body);
        assertFalse(data.get("skipped").asBoolean());
        int newChunkCount = data.get("chunkCount").asInt();
        assertTrue(newChunkCount > oldLiveCount, "新内容更长，块数应增加");

        // 旧块：逻辑删（raw SQL 绕过 ORM 的 deleted_at=0 过滤），行仍在
        assertEquals(newChunkCount, rawLiveChunkCount(docId), "未删除块数应等于新块数");
        assertEquals(oldLiveCount + newChunkCount, rawAllChunkCount(docId), "旧块应是逻辑删而不是物理删");
        assertEquals(newChunkCount, jdbcTemplate.queryForObject(
                "SELECT chunk_count FROM studio_knowledge_doc WHERE id=?", Integer.class, docId));

        // 旧向量：已被清理 —— 用旧块正文重建向量去检索，命中集合里不能出现旧 embeddingId
        List<EmbeddingMatch<TextSegment>> matches = searchByText(oldChunk0);
        assertTrue(matches.stream().noneMatch(m -> oldEmbeddingId.equals(m.embeddingId())),
                "旧向量应已清理，实际命中：" + matches.stream().map(EmbeddingMatch::embeddingId).toList());

        // 新块 embedding_id 齐全
        assertNotNull(rawChunkEmbeddingId(docId, 0), "新块必须有 embedding_id");
        assertEquals(1, embeddingCalls.get() - callsBeforeRebuild, "重建仍应只发一次批量请求");
    }

    @Test
    @DisplayName("源已删除：sync 返回 40400，已有文档标记 status=2，旧块保留（R2 收口）")
    void syncSourceMissingMarksDocFailed() throws Exception {
        String token = adminToken();
        long projectId = createProject("T25源删项目_" + System.nanoTime(), "T25-源删-正文");
        long docId = Long.parseLong(dataOf(postJson("/knowledge/doc/sync", syncJson("project", projectId), token))
                .get("docId").asString());
        int liveBefore = rawLiveChunkCount(docId);

        // 项目被逻辑删除（selectById 自动追加 deleted_at=0 → 视为不存在）
        studioProjectMapper.deleteById(projectId);

        String body = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);

        assertEquals(40400, code(body), "源已删除应 40400：" + body);
        assertEquals(2, rawDocStatus(docId), "已有文档应标记 status=2（检索层可据此降级）");
        assertEquals(liveBefore, rawLiveChunkCount(docId), "标记失败不应动旧块");

        // 从未同步过的来源：40400 且不产生任何 doc 行（不写 status=2 新行，避免污染幂等）
        long ghostId = 999999999999999L;
        assertEquals(40400, code(postJson("/knowledge/doc/sync", syncJson("project", ghostId), token)));
        assertEquals(0, countDocsBySource("project", ghostId));
    }

    @Test
    @DisplayName("同步参数校验：manual 被拒、非法 sourceType、sourceId 非法 → 40000")
    void syncValidationRejectsBadRequests() throws Exception {
        String token = adminToken();

        String manual = postJson("/knowledge/doc/sync", syncJson("manual", 1L), token);
        assertEquals(40000, code(manual));
        assertTrue(manual.contains("手工录入"), "应引导到 /knowledge/doc/manual：" + manual);

        assertEquals(40000, code(postJson("/knowledge/doc/sync", syncJson("bogus", 1L), token)));
        // sourceId 为 0 / 缺失 → 40000（@Positive / @NotNull）
        assertEquals(40000, code(postJson("/knowledge/doc/sync", syncJson("project", 0), token)));
        assertEquals(40000, code(postJson("/knowledge/doc/sync", "{\"sourceType\":\"project\"}", token)));
    }

    // ==================== ⑨ 权限 ====================

    @Test
    @DisplayName("权限：匿名 40100、普通用户 40101、admin 放行（白名单零改动）")
    void permissionMatrix() throws Exception {
        String json = manualJson("T25权限", "T25权限正文");

        assertEquals(40100, code(postJson("/knowledge/doc/manual", json, null)), "匿名应 40100");
        assertEquals(40101, code(postJson("/knowledge/doc/manual", json, userToken())), "普通用户应 40101");
        assertEquals(0, code(postJson("/knowledge/doc/manual", json, adminToken())), "admin 应放行");

        // sync 走同一套机制，抽一条匿名验证
        assertEquals(40100, code(postJson("/knowledge/doc/sync", syncJson("project", 1L), null)));
    }

    // ==================== ⑩ 拼装规则 ====================

    @Test
    @DisplayName("拼装规则：成员为中文标签行；证书级别/类型转中文文案（不是库里的英文值）")
    void memberAndCertificateAssembly() throws Exception {
        String token = adminToken();

        long memberId = createMember("T25成员张三");
        long memberDocId = Long.parseLong(dataOf(postJson("/knowledge/doc/sync", syncJson("member", memberId), token))
                .get("docId").asString());
        String memberChunk = rawChunkContent(memberDocId, 0);
        assertTrue(memberChunk.contains("姓名：T25成员张三"), memberChunk);
        assertTrue(memberChunk.contains("届别：2023"), memberChunk);
        assertTrue(memberChunk.contains("专业：软件工程"), memberChunk);
        assertTrue(memberChunk.contains("方向：Java后端"), memberChunk);
        assertTrue(memberChunk.contains("简介：T25成员张三-简介"), memberChunk);

        long certId = createCertificate("T25证书-软著");
        long certDocId = Long.parseLong(dataOf(postJson("/knowledge/doc/sync", syncJson("certificate", certId), token))
                .get("docId").asString());
        String certChunk = rawChunkContent(certDocId, 0);
        assertTrue(certChunk.contains("名称：T25证书-软著"), certChunk);
        // 级别/类型必须转成中文文案（"国家级"/"软件著作权"），而不是 national / soft_copyright
        assertTrue(certChunk.contains("级别：国家级"), certChunk);
        assertTrue(certChunk.contains("类型：软件著作权"), certChunk);
        assertTrue(certChunk.contains("获奖日期：2024-05-01"), certChunk);
    }

    // ==================== ⑪⑫ 向量化失败 ====================

    @Test
    @DisplayName("向量化失败（首次）：50001 + 无残留行（不写新 hash，避免污染幂等），恢复后可重试成功")
    void embeddingFailureOnNewDocIsRetryable() throws Exception {
        String token = adminToken();
        String title = "T25失败重试_" + System.nanoTime();
        String content = "T25-失败重试-正文";
        failEmbeddings = true;

        String failed = postJson("/knowledge/doc/manual", manualJson(title, content), token);

        assertEquals(50001, code(failed), "向量化失败应 50001：" + failed);
        assertTrue(failed.contains("暂时不可用"), failed);
        assertEquals(0, countDocsByTitle(title), "失败不应留下 doc 行（否则新 hash 会让重试被跳过）");

        // 恢复后重试：同一份内容必须能成功（证明失败没有污染幂等判定）
        failEmbeddings = false;
        String retry = postJson("/knowledge/doc/manual", manualJson(title, content), token);
        assertEquals(0, code(retry), "恢复后应可重试成功：" + retry);
        assertEquals(1, countDocsByTitle(title));
    }

    @Test
    @DisplayName("向量化失败（重建）：50001 + 旧块与旧向量原样保留 + status=2，恢复后重建成功")
    void embeddingFailureOnRebuildKeepsOldData() throws Exception {
        String token = adminToken();
        long projectId = createProject("T25重建失败_" + System.nanoTime(), "T25-重建失败-旧正文");
        long docId = Long.parseLong(dataOf(postJson("/knowledge/doc/sync", syncJson("project", projectId), token))
                .get("docId").asString());
        String oldChunk0 = rawChunkContent(docId, 0);
        String oldEmbeddingId = rawChunkEmbeddingId(docId, 0);
        int oldLiveCount = rawLiveChunkCount(docId);

        StudioProject update = new StudioProject();
        update.setId(projectId);
        update.setContent("T25-重建失败-新正文");
        studioProjectMapper.updateById(update);

        failEmbeddings = true;
        String failed = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);

        assertEquals(50001, code(failed), "重建失败应 50001：" + failed);
        assertEquals(2, rawDocStatus(docId), "重建失败应把已有文档标记 status=2");
        assertEquals(oldLiveCount, rawLiveChunkCount(docId), "旧块必须原样保留（可用性优先）");
        List<EmbeddingMatch<TextSegment>> matches = searchByText(oldChunk0);
        assertTrue(matches.stream().anyMatch(m -> oldEmbeddingId.equals(m.embeddingId())),
                "旧向量必须保留，实际命中：" + matches.stream().map(EmbeddingMatch::embeddingId).toList());

        // 恢复后重建成功：状态回到 1、内容更新
        failEmbeddings = false;
        String retry = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);
        assertEquals(0, code(retry), "恢复后应重建成功：" + retry);
        assertTrue(dataOf(retry).get("rebuilt").asBoolean());
        assertEquals(1, rawDocStatus(docId));
        assertTrue(rawChunkContent(docId, 0).contains("T25-重建失败-新正文"));
    }

    // ==================== ⑬ 失败态复位 ====================

    @Test
    @DisplayName("失败态复位：源恢复且内容未变时 sync 不再被幂等跳过，而是重建并回到 status=1")
    void failedDocIsReindexedEvenWhenContentUnchanged() throws Exception {
        String token = adminToken();
        long projectId = createProject("T25-复位项目_" + System.nanoTime(), "T25-复位-正文");
        long docId = Long.parseLong(dataOf(postJson("/knowledge/doc/sync", syncJson("project", projectId), token))
                .get("docId").asString());

        // 源被逻辑删除 → sync 返回 40400 并把已有文档标记为失败（status=2，content_hash 保持旧值）
        studioProjectMapper.deleteById(projectId);
        assertEquals(40400, code(postJson("/knowledge/doc/sync", syncJson("project", projectId), token)));
        assertEquals(2, rawDocStatus(docId), "源删除后文档应标记失败");

        // 源恢复（deleted_at 置回 0）且内容未变：reset 前的实现会因「hash 相同」直接 skipped，
        // 文档永远停在 status=2；修复后必须走一次复位重建
        jdbcTemplate.update("UPDATE studio_project SET deleted_at=0 WHERE id=?", projectId);
        int callsBefore = embeddingCalls.get();

        String body = postJson("/knowledge/doc/sync", syncJson("project", projectId), token);

        assertEquals(0, code(body), "源恢复后应能重建：" + body);
        JsonNode data = dataOf(body);
        assertFalse(data.get("skipped").asBoolean(), "失败态不应被幂等跳过：" + body);
        assertTrue(data.get("rebuilt").asBoolean(), "应走一次复位重建：" + body);
        assertEquals(1, rawDocStatus(docId), "重建后应回到已索引状态");
        assertEquals(callsBefore + 1, embeddingCalls.get(), "复位重建应真实调用一次批量 Embedding");
    }
}