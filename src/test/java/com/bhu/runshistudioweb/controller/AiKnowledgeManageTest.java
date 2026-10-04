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
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 知识库管理端操作验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖九条线：
 * <ol>
 *     <li>全量同步（首次）：三类业务数据全部 created、统计等式成立（created+rebuilt+skipped+failed==total）、
 *     每源恰好一次批量 Embedding 请求；</li>
 *     <li>全量同步（复跑）：全部 skipped、Embedding 调用增量 0、updated_at 不变（零写库取证）；</li>
 *     <li>单条失败不中断：成员正文带失败记号 → 该条 failed 且不留 doc 行，项目/证书照常入库；
 *     修复后重跑补齐、其余跳过；</li>
 *     <li>分页契约：过滤（title 模糊 / sourceType 精确 / status 精确）、非法 sourceType → A0401、
 *     docId/sourceId 为字符串、时间为约定格式、<b>响应不含正文</b>、pageSize 上限收敛；</li>
 *     <li>级联删除：doc + chunk 同事务逻辑删（行仍在、deleted_at != 0）、提交后清向量
 *     （向量库直查不再命中）、删除后检索不再命中、重复删除 A0402；</li>
 *     <li>重建分支：manual 拒绝（A0401 且不改动原文档）、文档不存在 A0402、id 非法 A0401；</li>
 *     <li>重建源类：内容变 → rebuilt（块替换、旧块逻辑删）；未变 → skipped；</li>
 *     <li>重建源已删除：A0402 + 已有文档标记 status=2；</li>
 *     <li>权限矩阵：4 个接口匿名 A0201、普通用户 A0301、admin 放行。</li>
 * </ol>
 *
 * <p><b>为什么本类不加 {@code @Transactional}</b>：全量同步与删除内部用
 * {@code TransactionTemplate}（REQUIRED 传播）。若测试也开事务，内层会「加入」测试事务，
 * 一旦某条 sync 失败被 {@code syncBatch} 捕获，内层的 rollback 会把整个测试事务标记为
 * rollback-only，测试结束提交时抛 {@code UnexpectedRollbackException}——而真实运行时
 * 每条 sync 是独立事务、失败只回滚自己。因此本类数据<b>真实提交</b>、{@code @AfterEach}
 * 按 T27 前缀清理（先向量、再子表后父表）。
 *
 * <p><b>Embedding Stub（JDK {@link HttpServer}）</b>：
 * <ul>
 *     <li>平面向量技巧与 {@code AiKnowledgeRetrievalTest} 相同（{@code V{n}-EXACT/NEAR/FAR}），
 *     本类只用 V8 平面隔离「删除后检索」用例；</li>
 *     <li><b>失败记号</b>：请求体包含 {@code FAIL-EMBEDDING} 时返回 500——
 *     用于「单条失败不中断」用例（只有带记号的源会失败，其它源照常成功）。</li>
 * </ul>
 *
 * <p><b>类名以 {@code Ai} 开头是刻意的「排序约束」，改动前先读这段</b>：
 * 本类通过 {@code @DynamicPropertySource} 指向自己的 Stub（端口不同 → 属性集不同），
 * 会新建一个 Spring 测试上下文。Sa-Token 用<b>静态</b> {@code SaManager} 持有 DAO，
 * 它总是绑定「最后创建的上下文」；Boot 4.1 的测试框架在类切换时还会 stop 先前上下文。
 * 因此「新建上下文的测试类」必须全部排在 {@code CertificateCrudTest} 之前——
 * 它是第一个使用默认 MockMvc 上下文的类，该上下文会被其后所有测试类共享，
 * 而静态 DAO 必须始终指向「当前正在使用的上下文」。
 * 本类若排到它之后，后续所有登录类测试都会 B0001 失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiKnowledgeManageTest {

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
    private KnowledgeDocService knowledgeDocService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    // ==================== 本地 Embedding Stub ====================

    private static HttpServer stub;

    /** /embeddings 调用次数：用于取证「复跑零调用」「每源一次批量请求」 */
    private static final AtomicInteger embeddingCalls = new AtomicInteger();

    /** 失败记号：请求体含它 → 该次向量化返回 500（用于「单条失败不中断」） */
    private static final String FAIL_MARKER = "FAIL-EMBEDDING";

    private static final int DIM = 18;

    private static final Pattern PLANE_MARKER = Pattern.compile("V(\\d)-(EXACT|NEAR|FAR)");

    private static final JsonMapper STUB_JSON = JsonMapper.builder().build();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            embeddingCalls.incrementAndGet();
            if (body.contains(FAIL_MARKER)) {
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
        // 与 AiKnowledgeRetrievalTest 同一理由：LangChain4j 的 minScore 是 relevance=(1+cos)/2，
        // 配 0.75 才能把「正交（0.5）与弱相关（0.7）」过滤掉；线上默认 0.5 属于不过滤
        // （已在验收报告登记为观察项，本类沿用 0.75）
        registry.add("studio.ai.rag-min-score", () -> 0.75);
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    /** 按前缀清理（先向量后库表；非事务类必须显式清理） */
    @AfterEach
    void cleanup() {
        purgeT27Vectors();
        jdbcTemplate.update("DELETE FROM studio_knowledge_chunk WHERE doc_id IN "
                + "(SELECT id FROM studio_knowledge_doc WHERE title LIKE 'T27%')");
        jdbcTemplate.update("DELETE FROM studio_knowledge_doc WHERE title LIKE 'T27%'");
        jdbcTemplate.update("DELETE FROM studio_project WHERE title LIKE 'T27%'");
        jdbcTemplate.update("DELETE FROM studio_member WHERE name LIKE 'T27%'");
        jdbcTemplate.update("DELETE FROM studio_certificate WHERE title LIKE 'T27%'");
        jdbcTemplate.update("DELETE FROM sys_user WHERE user_account LIKE 'T27%'");
    }

    // ==================== 工具方法 ====================

    private String adminToken() throws Exception {
        String account = "T27adm_" + (System.nanoTime() % 100000);
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
        String account = "T27usr_" + (System.nanoTime() % 100000);
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

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private JsonNode dataOf(String body) {
        return jsonMapper.readTree(body).get("data");
    }

    /** MP 的 Page 里 total/size/current 是 long，被全局 JsonConfig 序列化为字符串；两种都兼容读取 */
    private long pageNumber(JsonNode page, String field) {
        JsonNode value = page.get(field);
        assertNotNull(value, "分页字段缺失：" + field + "，响应：" + page);
        return value.isString() ? Long.parseLong(value.asString()) : value.asLong();
    }

    /** 手工录入（管理端接口） */
    private long ingestManual(String token, String title, String content) throws Exception {
        String body = postJson("/knowledge/doc/manual",
                "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}", token);
        assertEquals("00000", code(body), "手工录入应成功：" + body);
        return Long.parseLong(dataOf(body).get("docId").asString());
    }

    /** 同步一条业务数据，返回 docId */
    private long syncSource(String token, String sourceType, long sourceId) throws Exception {
        String body = postJson("/knowledge/doc/sync",
                "{\"sourceType\":\"" + sourceType + "\",\"sourceId\":" + sourceId + "}", token);
        assertEquals("00000", code(body), "同步应成功：" + body);
        return Long.parseLong(dataOf(body).get("docId").asString());
    }

    private long createProject(String title, String content) {
        StudioProject project = new StudioProject();
        project.setTitle(title);
        project.setDescription(title + "-摘要");
        project.setContent(content);
        // 无外键约束：只需要一个非空队长 ID，与知识库链路无关
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
        certificate.setImageUrl("http://example.com/t27.png");
        certificate.setSortOrder(0);
        studioCertificateMapper.insert(certificate);
        return certificate.getId();
    }

    /** 把清理用的向量先摘出来（必须在物理删 chunk 行之前执行，否则子查询查不到） */
    private void purgeT27Vectors() {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT embedding_id FROM studio_knowledge_chunk WHERE embedding_id IS NOT NULL AND doc_id IN "
                        + "(SELECT id FROM studio_knowledge_doc WHERE title LIKE 'T27%')", String.class);
        knowledgeBaseManager.removeVectors(ids);
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

    private long docDeletedAt(long docId) {
        Long deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM studio_knowledge_doc WHERE id=?", Long.class, docId);
        return deletedAt == null ? -1L : deletedAt;
    }

    private int liveChunkCount(long docId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_knowledge_chunk WHERE doc_id=? AND deleted_at=0", Integer.class, docId);
        return count == null ? 0 : count;
    }

    private int allChunkCount(long docId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_knowledge_chunk WHERE doc_id=?", Integer.class, docId);
        return count == null ? 0 : count;
    }

    private String chunkContent(long docId, int chunkIndex) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM studio_knowledge_chunk WHERE doc_id=? AND chunk_index=? AND deleted_at=0",
                String.class, docId, chunkIndex);
    }

    private List<String> embeddingIdsOf(long docId) {
        return jdbcTemplate.queryForList(
                "SELECT embedding_id FROM studio_knowledge_chunk WHERE doc_id=? AND deleted_at=0 AND embedding_id IS NOT NULL",
                String.class, docId);
    }

    /** 三篇 doc 的 updated_at 快照（复跑幂等用例的零写库取证） */
    private List<String> docUpdatedAtList() {
        return jdbcTemplate.query(
                "SELECT updated_at FROM studio_knowledge_doc WHERE deleted_at=0 ORDER BY source_type, id",
                (rs, rowNum) -> rs.getString(1));
    }

    /**
     * 平面向量（与 Stub 共用）：V{n}-EXACT 与问题同向（relevance 1.0）；
     * NEAR 余弦 0.6；FAR 余弦 0.4；无记号则落在保留维（与所有平面正交）
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

    /** 直接查询向量库（绕过数据库过滤）：验证「向量是否真的被清理」的唯一手段 */
    private List<EmbeddingMatch<TextSegment>> searchByText(String text) {
        return knowledgeBaseManager.getEmbeddingStore().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vec(text)))
                        .maxResults(20)
                        .build()).matches();
    }

    /**
     * 当前未删除的**源数据**总条数（project + member + certificate），即 sync-all 的 total 基准
     *
     * <p>为什么需要它：早期这些用例直接断言 {@code total == 3}，隐含假设「库是空的」。
     * 一旦库里有演示数据（db/demo-data.sql 的 15 条），total 就变成 18 而断言失败——
     * 那是**测试把环境当成了前提**，不是产品缺陷。断言「基线条数 + 本次新增」才能让
     * 验收测试与业务数据共存。
     */
    private int sourceCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM studio_project WHERE deleted_at = 0", Integer.class)
                + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM studio_member WHERE deleted_at = 0", Integer.class)
                + jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM studio_certificate WHERE deleted_at = 0", Integer.class);
    }

    /**
     * 统计等式：{@code created + rebuilt + skipped + failed == total}
     *
     * <p>它比任何单个计数都重要——调用方靠这个等式判断「有没有漏」。
     * 有演示数据时各分项的具体数值会变，但等式必须永远成立。
     */
    private void assertSummaryConsistent(JsonNode data, String body) {
        int total = data.get("total").asInt();
        int sum = data.get("created").asInt() + data.get("rebuilt").asInt()
                + data.get("skipped").asInt() + data.get("failed").asInt();
        assertEquals(total, sum, "统计等式必须成立（created+rebuilt+skipped+failed == total）：" + body);
    }

    // ==================== ① 全量同步：首次 ====================

    @Test
    @DisplayName("全量同步首次：三类数据全部 created、统计等式成立、每源恰好一次批量 Embedding")
    void syncAllFirstRunCreatesAllThreeTypes() throws Exception {
        String token = adminToken();
        // 基线：本用例建数据**之前**库里已有的源数据条数（开发库可能已导入演示数据）
        int baseline = sourceCount();
        long projectId = createProject("T27-全量项目_" + System.nanoTime(), "T27-全量-项目正文");
        long memberId = createMember("T27-全量成员_" + System.nanoTime());
        long certificateId = createCertificate("T27-全量证书_" + System.nanoTime());
        int callsBefore = embeddingCalls.get();

        String body = postJson("/knowledge/doc/sync-all", "", token);

        assertEquals("00000", code(body), "全量同步应成功：" + body);
        JsonNode data = dataOf(body);
        assertEquals(baseline + 3, data.get("total").asInt(), "总条数 = 原有条数 + 本次新增 3 条：" + body);
        assertEquals(3, data.get("created").asInt(), "本次新增的 3 条应全部 created（原有的走 skipped）：" + body);
        assertEquals(0, data.get("rebuilt").asInt());
        assertEquals(0, data.get("failed").asInt());
        assertSummaryConsistent(data, body);

        // DB 取证：三类各一篇 doc、status=1、块行齐全且 embedding_id 非空
        assertEquals(1, countDocsBySource("project", projectId), "项目应入库一篇");
        assertEquals(1, countDocsBySource("member", memberId), "成员应入库一篇");
        assertEquals(1, countDocsBySource("certificate", certificateId), "证书应入库一篇");
        for (String[] pair : List.of(new String[]{"project", String.valueOf(projectId)},
                new String[]{"member", String.valueOf(memberId)},
                new String[]{"certificate", String.valueOf(certificateId)})) {
            Long docId = jdbcTemplate.queryForObject(
                    "SELECT id FROM studio_knowledge_doc WHERE source_type=? AND source_id=? AND deleted_at=0",
                    Long.class, pair[0], Long.parseLong(pair[1]));
            assertNotNull(docId, pair[0] + " 应有 doc 行");
            assertEquals(1, rawDocStatus(docId), "入库成功应为 status=1");
            assertTrue(liveChunkCount(docId) >= 1, "应有切分块行");
            assertFalse(embeddingIdsOf(docId).isEmpty(), "每个块必须有 embedding_id");
        }

        // 每源一次批量请求：3 条源恰好 3 次 /embeddings（不是每块一次）
        assertEquals(3, embeddingCalls.get() - callsBefore, "三类源应恰好 3 次批量 Embedding 请求");
    }

    // ==================== ② 全量同步：复跑幂等 ====================

    @Test
    @DisplayName("全量同步复跑：全部 skipped、Embedding 零增量、updated_at 不变（零写库取证）")
    void syncAllSecondRunSkipsAllWithZeroEmbedding() throws Exception {
        String token = adminToken();
        int baseline = sourceCount();
        createProject("T27-幂等项目_" + System.nanoTime(), "T27-幂等-项目正文");
        createMember("T27-幂等成员_" + System.nanoTime());
        createCertificate("T27-幂等证书_" + System.nanoTime());

        assertEquals("00000", code(postJson("/knowledge/doc/sync-all", "", token)), "首跑应成功");
        int callsAfterFirst = embeddingCalls.get();
        List<String> updatedAtBefore = docUpdatedAtList();
        assertEquals(baseline + 3, updatedAtBefore.size(), "首跑后文档数 = 原有 + 本次 3 篇");

        String second = postJson("/knowledge/doc/sync-all", "", token);

        assertEquals("00000", code(second), "复跑应成功：" + second);
        JsonNode data = dataOf(second);
        assertEquals(baseline + 3, data.get("total").asInt());
        assertEquals(0, data.get("created").asInt(), "复跑不应再新建：" + second);
        assertEquals(0, data.get("rebuilt").asInt(), "内容未变不应重建：" + second);
        assertEquals(0, data.get("failed").asInt());
        assertEquals(data.get("total").asInt(), data.get("skipped").asInt(),
                "所有文档内容都未变时应全部 skipped：" + second);
        assertSummaryConsistent(data, second);

        assertEquals(callsAfterFirst, embeddingCalls.get(), "跳过路径不应再调用 Embedding");
        assertEquals(updatedAtBefore, docUpdatedAtList(), "跳过路径不应发生任何写库");
    }

    // ==================== ③ 单条失败不中断 ====================

    @Test
    @DisplayName("单条失败不中断：带失败记号的成员 failed 且不留 doc 行，项目/证书照常入库；修复后重跑补齐")
    void syncAllSingleFailureDoesNotInterruptOthers() throws Exception {
        String token = adminToken();
        int baseline = sourceCount();
        long projectId = createProject("T27-失败项目_" + System.nanoTime(), "T27-失败-项目正常正文");
        // 成员正文（姓名行）带失败记号 → 只有这一条的向量化请求会 500
        long memberId = createMember("T27-" + FAIL_MARKER + "成员_" + System.nanoTime());
        long certificateId = createCertificate("T27-失败证书_" + System.nanoTime());

        String body = postJson("/knowledge/doc/sync-all", "", token);

        assertEquals("00000", code(body), "批量任务应返回统计而不是整体异常：" + body);
        JsonNode data = dataOf(body);
        assertEquals(baseline + 3, data.get("total").asInt());
        assertEquals(2, data.get("created").asInt(), "本次两条应成功：" + body);
        assertEquals(1, data.get("failed").asInt(), "本次一条应失败：" + body);
        assertEquals(0, data.get("rebuilt").asInt());
        assertSummaryConsistent(data, body);

        assertEquals(0, countDocsBySource("member", memberId), "失败条不应留下 doc 行（避免脏 hash 污染幂等）");
        assertEquals(1, countDocsBySource("project", projectId), "失败不应中断它前面的同步");
        assertEquals(1, countDocsBySource("certificate", certificateId), "失败不应中断它后面的同步");

        // 修复来源（姓名与简介都要去掉记号——正文拼装会把简介也带上）后重跑：成员补齐 created，其余 skipped
        StudioMember fix = new StudioMember();
        fix.setId(memberId);
        fix.setName("T27-失败成员-已修复_" + System.nanoTime());
        fix.setSummary("修复后的简介");
        studioMemberMapper.updateById(fix);

        String retry = postJson("/knowledge/doc/sync-all", "", token);

        assertEquals("00000", code(retry), "修复后重跑应成功：" + retry);
        JsonNode retryData = dataOf(retry);
        assertEquals(1, retryData.get("created").asInt(), "只有修复的那条应新建：" + retry);
        // 跳过的 = 库里原有的（baseline）+ 本用例首跑成功的 2 条。
        // 不能断言 2：库里若已有演示数据，它们内容未变也会一并 skipped。
        assertEquals(baseline + 2, retryData.get("skipped").asInt(),
                "其余（原有 + 首跑成功的两条）都应跳过：" + retry);
        assertEquals(0, retryData.get("failed").asInt());
        assertSummaryConsistent(retryData, retry);
        assertEquals(1, countDocsBySource("member", memberId), "补齐后成员应有 doc 行");
    }

    // ==================== ④ 分页契约 ====================

    @Test
    @DisplayName("分页契约：过滤生效、非法来源类型 A0401、字段类型约定、响应不含正文、pageSize 收敛")
    void listPageFiltersAndContract() throws Exception {
        String token = adminToken();
        long projectId = createProject("T27-分页项目_" + System.nanoTime(), "T27-分页-项目正文");
        long memberId = createMember("T27-分页成员_" + System.nanoTime());
        long manualDocId = ingestManual(token, "T27-分页手册_" + System.nanoTime(),
                "T27-分页-手册独特正文，不应出现在列表响应里");
        long projectDocId = syncSource(token, "project", projectId);
        long memberDocId = syncSource(token, "member", memberId);
        assertNotEquals(projectDocId, manualDocId);

        // 默认分页：本用例的 3 篇
        // ⚠️ 必须带 title 过滤：不带过滤时 total 是**全局**文档数，
        // 库里若有演示数据会把这个断言顶掉（那是测试把环境当成了前提，不是产品缺陷）。
        String body = postJson("/knowledge/doc/list/page",
                "{\"current\":1,\"pageSize\":10,\"title\":\"T27-分页\"}", token);
        assertEquals("00000", code(body), "分页应成功：" + body);
        JsonNode data = dataOf(body);
        assertEquals(3, pageNumber(data, "total"), "标题过滤下应有本用例的 3 篇：" + body);
        assertEquals(1, pageNumber(data, "current"));
        assertEquals(10, pageNumber(data, "size"));
        assertEquals(3, data.get("records").size());

        // 契约：docId/SourceId 为 19 位字符串（全局 Long→String）、时间为约定格式、状态为数字
        assertTrue(body.matches("(?s).*\"docId\":\"\\d{19}\".*"), "docId 应为字符串：" + body);
        assertTrue(body.matches("(?s).*\"createdAt\":\"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\".*"),
                "createdAt 应为 yyyy-MM-dd HH:mm:ss：" + body);
        assertTrue(body.contains("\"status\":1"), "status 应是数字（计数/状态不是 ID）：" + body);
        // <b>不含正文</b>：列表 VO 里没有正文，任何 chunk 文本都不该出现
        assertFalse(body.contains("手册独特正文"), "列表响应不应含正文：" + body);

        // title 模糊筛选 → 只回手册一篇（manual 来源、sourceId 为 null）
        JsonNode byTitle = dataOf(postJson("/knowledge/doc/list/page",
                "{\"title\":\"T27-分页手册\"}", token));
        assertEquals(1, pageNumber(byTitle, "total"), "标题模糊筛选应命中 1 篇：" + byTitle);
        assertEquals("manual", byTitle.get("records").get(0).get("sourceType").asString());
        assertTrue(byTitle.get("records").get(0).get("sourceId").isNull(), "manual 来源 sourceId 应为 null");

        // sourceType 精确筛选 → 只回项目一篇，sourceId 为字符串
        // 来源筛选也要带 title：演示数据里有 5 个项目文档，不带过滤会命中 6 篇
        JsonNode bySource = dataOf(postJson("/knowledge/doc/list/page",
                "{\"sourceType\":\"project\",\"title\":\"T27-分页项目\"}", token));
        assertEquals(1, pageNumber(bySource, "total"), "来源筛选应命中 1 篇：" + bySource);
        assertEquals(String.valueOf(projectId), bySource.get("records").get(0).get("sourceId").asString(),
                "sourceId 应为字符串形式的业务 ID");
        assertTrue(bySource.get("records").get(0).get("sourceId").isString(), "sourceId 应为字符串类型");

        // status 筛选同样要带 title 隔离（演示数据也都是 status=1，不带过滤会命中 18 篇）
        assertEquals(3, pageNumber(dataOf(postJson("/knowledge/doc/list/page",
                "{\"status\":1,\"title\":\"T27-分页\"}", token)), "total"),
                "status=1 应命中本用例的全部 3 篇");
        assertEquals(0, pageNumber(dataOf(postJson("/knowledge/doc/list/page",
                "{\"status\":2,\"title\":\"T27-分页\"}", token)), "total"),
                "无失败文档，status=2 应为空");

        // 非法来源类型是闭集校验错误：A0401 而不是静默空列表
        String illegal = postJson("/knowledge/doc/list/page", "{\"sourceType\":\"superman\"}", token);
        assertEquals("A0401", code(illegal), "非法 sourceType 应 A0401：" + illegal);
        assertTrue(illegal.contains("来源类型不合法"), "应给出可读原因：" + illegal);

        // pageSize 上限收敛（999 → 50）
        assertEquals(50, pageNumber(dataOf(postJson("/knowledge/doc/list/page",
                "{\"pageSize\":999}", token)), "size"), "超过上限应被收敛到 50");
    }

    // ==================== ⑤ 级联删除 + 检索不再命中 ====================

    @Test
    @DisplayName("级联删除：doc+chunk 同事务逻辑删、向量清理、删除后检索不再命中、重复删除 A0402")
    void deleteCascadesAndStopsRetrieval() throws Exception {
        String token = adminToken();
        String title = "T27-删除文档_" + System.nanoTime();
        long docId = ingestManual(token, title, "V8-EXACT 待删除的正文内容，只用于删除链路验收");
        String question = "T27 V8-EXACT 问题：删除之后还应命中吗";

        // 前置：能检索到
        KnowledgeDocService.RetrievalResult before = knowledgeDocService.retrieve(question);
        assertTrue(before.hit(), "删除前应能命中该文档");
        assertEquals(docId, before.sources().get(0).getDocId().longValue());
        List<String> embeddingIds = embeddingIdsOf(docId);
        assertFalse(embeddingIds.isEmpty(), "删除前应有向量 ID");
        String embeddingId = embeddingIds.get(0);
        int allBefore = allChunkCount(docId);

        String body = postJson("/knowledge/doc/delete", "{\"id\":" + docId + "}", token);

        assertEquals("00000", code(body), "删除应成功：" + body);
        assertTrue(dataOf(body).asBoolean(), "应返回 true：" + body);

        // 逻辑删除取证：doc 与 chunk 的行都还在（不是物理删），deleted_at 已写毫秒时间戳
        assertNotEquals(0L, docDeletedAt(docId), "doc 应被逻辑删除（deleted_at != 0）");
        assertEquals(allBefore, allChunkCount(docId), "块行应仍在（逻辑删而非物理删）");
        assertEquals(0, liveChunkCount(docId), "块应全部逻辑删除");

        // 检索不再命中（两层证据：DB 过滤 + 向量已清理）
        KnowledgeDocService.RetrievalResult after = knowledgeDocService.retrieve(question);
        assertFalse(after.hit(), "删除后不应再命中");
        assertTrue(searchByText(question).stream().noneMatch(m -> embeddingId.equals(m.embeddingId())),
                "旧向量应已清理（向量库直查不应再命中）");

        // 重复删除 → A0402
        assertEquals("A0402", code(postJson("/knowledge/doc/delete", "{\"id\":" + docId + "}", token)),
                "已删除的文档再删应 A0402");
    }

    // ==================== ⑥ 重建分支：manual / 不存在 / id 非法 ====================

    @Test
    @DisplayName("重建分支：manual 拒绝（A0401 且不改动）、文档不存在 A0402、id 非法 A0401")
    void rebuildRejectsManualAndInvalidIds() throws Exception {
        String token = adminToken();
        long manualDocId = ingestManual(token, "T27-重建手册_" + System.nanoTime(), "T27-重建-手工正文");

        String rejected = postJson("/knowledge/doc/rebuild", "{\"id\":" + manualDocId + "}", token);
        assertEquals("A0401", code(rejected), "manual 文档不应允许重建：" + rejected);
        assertTrue(rejected.contains("手工录入"), "应给出手工录入的替代路径：" + rejected);
        assertEquals(1, rawDocStatus(manualDocId), "被拒绝的 manual 文档不应被改动");

        assertEquals("A0402", code(postJson("/knowledge/doc/rebuild",
                "{\"id\":999999999999999999}", token)), "不存在的文档应 A0402");
        assertEquals("A0401", code(postJson("/knowledge/doc/rebuild", "{\"id\":0}", token)),
                "id=0 应被 @Positive 拦下 → A0401");

        // 删除接口同一套边界
        assertEquals("A0402", code(postJson("/knowledge/doc/delete",
                "{\"id\":999999999999999999}", token)), "删除不存在的文档应 A0402");
        assertEquals("A0401", code(postJson("/knowledge/doc/delete", "{\"id\":0}", token)),
                "删除 id=0 应 A0401");
    }

    // ==================== ⑦ 重建源类：内容变 rebuilt / 未变 skipped ====================

    @Test
    @DisplayName("重建源类：内容变更 → rebuilt（块替换、旧块逻辑删）；未变 → skipped")
    void rebuildSourceDocRecomputesAndSkips() throws Exception {
        String token = adminToken();
        long projectId = createProject("T27-重建项目_" + System.nanoTime(), "T27-重建-旧正文");
        long docId = syncSource(token, "project", projectId);
        int liveBefore = liveChunkCount(docId);

        // 运营修改项目正文 → 重建
        StudioProject update = new StudioProject();
        update.setId(projectId);
        update.setContent("T27-重建-新正文");
        studioProjectMapper.updateById(update);

        String body = postJson("/knowledge/doc/rebuild", "{\"id\":" + docId + "}", token);

        assertEquals("00000", code(body), "重建应成功：" + body);
        JsonNode data = dataOf(body);
        assertTrue(data.get("rebuilt").asBoolean(), "内容变更应走重建：" + body);
        assertFalse(data.get("skipped").asBoolean());
        assertEquals(docId, Long.parseLong(data.get("docId").asString()), "重建应指向同一篇文档");
        assertTrue(chunkContent(docId, 0).contains("T27-重建-新正文"), "新块应包含新正文");
        assertTrue(allChunkCount(docId) > liveBefore, "旧块应被逻辑删（行数变多、存活数不变）");
        assertEquals(liveBefore, liveChunkCount(docId), "存活块数应不变" );

        // 内容未变再重建 → skipped（零成本）
        String again = postJson("/knowledge/doc/rebuild", "{\"id\":" + docId + "}", token);
        assertEquals("00000", code(again), "二次重建应成功：" + again);
        JsonNode againData = dataOf(again);
        assertTrue(againData.get("skipped").asBoolean(), "内容未变应跳过：" + again);
        assertFalse(againData.get("rebuilt").asBoolean());
    }

    // ==================== ⑧ 重建：源已删除 ====================

    @Test
    @DisplayName("重建源已删除：A0402 + 已有文档标记 status=2（R2 收口）")
    void rebuildSourceMissingMarksFailed() throws Exception {
        String token = adminToken();
        long projectId = createProject("T27-源删项目_" + System.nanoTime(), "T27-源删-正文");
        long docId = syncSource(token, "project", projectId);

        // 项目被逻辑删除 → 重建时读源视为不存在
        studioProjectMapper.deleteById(projectId);

        String body = postJson("/knowledge/doc/rebuild", "{\"id\":" + docId + "}", token);

        assertEquals("A0402", code(body), "源已删除应 A0402：" + body);
        assertEquals(2, rawDocStatus(docId), "已有文档应标记 status=2");
    }

    // ==================== ⑨ 权限矩阵 ====================

    @Test
    @DisplayName("权限矩阵：4 个接口匿名 A0201、普通用户 A0301、admin 放行（白名单零改动）")
    void permissionMatrixOfManageApis() throws Exception {
        // 匿名：默认拒绝
        assertEquals("A0201", code(postJson("/knowledge/doc/sync-all", "", null)), "sync-all 匿名应 A0201");
        assertEquals("A0201", code(postJson("/knowledge/doc/list/page", "{}", null)), "list/page 匿名应 A0201");
        assertEquals("A0201", code(postJson("/knowledge/doc/delete", "{\"id\":1}", null)), "delete 匿名应 A0201");
        assertEquals("A0201", code(postJson("/knowledge/doc/rebuild", "{\"id\":1}", null)), "rebuild 匿名应 A0201");

        // 普通用户：有登录态但角色不够
        String userToken = userToken();
        assertEquals("A0301", code(postJson("/knowledge/doc/sync-all", "", userToken)), "sync-all 普通用户应 A0301");
        assertEquals("A0301", code(postJson("/knowledge/doc/list/page", "{}", userToken)), "list/page 普通用户应 A0301");
        assertEquals("A0301", code(postJson("/knowledge/doc/delete", "{\"id\":1}", userToken)), "delete 普通用户应 A0301");
        assertEquals("A0301", code(postJson("/knowledge/doc/rebuild", "{\"id\":1}", userToken)), "rebuild 普通用户应 A0301");

        // admin：放行（用「通过权限层后到达业务层」的证据断言：
        // list/page 空库可查；sync-all 空库统计为 0；delete/rebuild 走业务层得到 A0402 而不是 A0301）
        String admin = adminToken();
        String listBody = postJson("/knowledge/doc/list/page", "{}", admin);
        assertEquals("00000", code(listBody), "admin 应能查列表（不假设库为空）：" + listBody);

        String syncAll = postJson("/knowledge/doc/sync-all", "", admin);
        assertEquals("00000", code(syncAll), "admin 应能全量同步：" + syncAll);
        // 不断言 total == 0：库里可能已有演示数据。
        // 这里真正要证明的是「请求穿过了权限层、进入业务层并正常返回统计」，
        // 所以断言统计等式成立 + 无失败项即可。
        assertSummaryConsistent(dataOf(syncAll), syncAll);
        assertEquals(0, dataOf(syncAll).get("failed").asInt(), "全量同步不应有失败项：" + syncAll);

        assertEquals("A0402", code(postJson("/knowledge/doc/delete", "{\"id\":999999999999999999}", admin)),
                "admin 删除不存在的文档 → A0402（说明已进入业务层）");
        assertEquals("A0402", code(postJson("/knowledge/doc/rebuild", "{\"id\":999999999999999999}", admin)),
                "admin 重建不存在的文档 → A0402（说明已进入业务层）");
    }
}