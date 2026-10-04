package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.manager.KnowledgeBaseManager;
import com.bhu.runshistudioweb.mapper.StudioProjectMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioProject;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 向量强制重建（观察项 e /）验收测试
 *
 * author: shaoshing
 *
 * <p><b>验证的核心场景</b>：向量库是进程内内存，应用重启后 chunk 行还在但向量全没了
 * （场景 I 已登记）。此时 {@code sync-all} 会因为内容哈希未变而全部 skipped，
 * 检索一直 0 命中——只有 {@code reindex-all} 能恢复，而且它对手工录入（manual）
 * 的文档同样有效（那些文档没有业务源可读，sync 救不了它们）。
 *
 * <p>这里用「清空向量库」来等价模拟重启：重启的本质就是向量消失、chunk 行保留。
 *
 * <p>类名以 {@code Ai} 开头：新建 Spring 测试上下文，排序约束见 AiKnowledgeManageTest 类注释。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiReindexTest {

    private static final String PROJECT_CONTENT = "星盘计划使用 Spring Boot 与 Redis 构建推荐系统。";
    private static final String MANUAL_CONTENT = "这是一篇手工录入的团队规范文档，没有业务来源。";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioProjectMapper studioProjectMapper;

    @Resource
    private KnowledgeBaseManager knowledgeBaseManager;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /** 本类产生的向量 ID，用于收尾清理（向量库是内存单例，不随事务回滚） */
    private final List<String> embeddingIds = new CopyOnWriteArrayList<>();

    private static HttpServer stub;
    private static final int DIM = 8;
    private static final JsonMapper STUB_JSON = JsonMapper.builder().build();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
            byte[] resp = ("{\"object\":\"list\",\"data\":[" + data + "],\"model\":\"test-embedding\"}")
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
        registry.add("studio.ai.embedding-model", () -> "test-embedding");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @AfterEach
    void cleanVectors() {
        if (!embeddingIds.isEmpty()) {
            knowledgeBaseManager.getEmbeddingStore().removeAll(embeddingIds);
            embeddingIds.clear();
        }
    }

    @Test
    @DisplayName("AC4：模拟重启后 sync-all 救不回来，reindex-all 恢复检索（含 manual 文档）")
    void reindexRestoresSearchAfterSimulatedRestart() throws Exception {
        String token = adminToken();
        long projectId = createProject();

        // ① 入库：一个 source 类（项目）+ 一个 manual 类（手工录入）
        assertEquals("00000", code(postJson("/knowledge/doc/sync",
                "{\"sourceType\":\"project\",\"sourceId\":" + projectId + "}", token)), "同步项目应成功");
        assertEquals("00000", code(postJson("/knowledge/doc/manual",
                "{\"title\":\"T30-团队规范\",\"content\":\"" + MANUAL_CONTENT + "\"}", token)),
                "手工录入应成功");

        List<String> ids = allEmbeddingIds();
        embeddingIds.addAll(ids);
        assertFalse(ids.isEmpty(), "入库后应有向量 ID");
        assertTrue(hitCount(PROJECT_CONTENT) >= 1, "入库后项目块应能检索到");
        assertTrue(hitCount(MANUAL_CONTENT) >= 1, "入库后手工文档块应能检索到");

        // ② 模拟重启：清空向量库（chunk 行仍在）
        knowledgeBaseManager.getEmbeddingStore().removeAll(ids);
        assertEquals(0, hitCount(PROJECT_CONTENT), "向量被清空后应 0 命中");

        // ③ sync-all 会因为「内容哈希未变」全部跳过，检索仍然救不回来
        String syncAll = postJson("/knowledge/doc/sync-all", "{}", token);
        assertEquals("00000", code(syncAll), "sync-all 应成功：" + syncAll);
        assertTrue(data(syncAll).get("skipped").asInt() >= 1, "内容未变应被跳过：" + syncAll);
        assertEquals(0, hitCount(PROJECT_CONTENT), "sync-all 跳过后检索仍未恢复");

        // ④ reindex-all：直接用 chunk 文本重新嵌入，恢复检索
        String reindex = postJson("/knowledge/doc/reindex-all", "{}", token);
        assertEquals("00000", code(reindex), "reindex-all 应成功：" + reindex);
        JsonNode result = data(reindex);
        assertTrue(result.get("rebuilt").asInt() >= 2, "两篇文档都应被重建：" + reindex);
        assertEquals(0, result.get("failed").asInt(), "不应有失败：" + reindex);

        embeddingIds.addAll(allEmbeddingIds());
        assertTrue(hitCount(PROJECT_CONTENT) >= 1, "reindex 后项目块应恢复检索");
        assertTrue(hitCount(MANUAL_CONTENT) >= 1, "reindex 后手工文档块也应恢复（sync 救不了它）");
    }

    @Test
    @DisplayName("AC4：权限矩阵——admin 放行 / 普通用户 A0301 / 匿名 A0201")
    void permissionMatrix() throws Exception {
        assertEquals("00000", code(postJson("/knowledge/doc/reindex-all", "{}", adminToken())),
                "管理员应放行");
        assertEquals("A0301", code(postJson("/knowledge/doc/reindex-all", "{}", userToken())),
                "普通用户应 A0301");
        assertEquals("A0201", code(postJson("/knowledge/doc/reindex-all", "{}", null)),
                "匿名应 A0201");
    }

    // ==================== 工具 ====================

    private long createProject() {
        StudioProject project = new StudioProject();
        project.setTitle("T30-星盘计划");
        project.setDescription("演示项目");
        project.setContent(PROJECT_CONTENT);
        project.setLeaderId(1L);
        project.setStatus(1);
        project.setSortOrder(0);
        studioProjectMapper.insert(project);
        return project.getId();
    }

    private int hitCount(String text) {
        return knowledgeBaseManager.getEmbeddingStore().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vec(text)))
                        .maxResults(20)
                        .build()).matches().size();
    }

    private List<String> allEmbeddingIds() {
        return jdbcTemplate.queryForList(
                "SELECT embedding_id FROM studio_knowledge_chunk WHERE deleted_at=0 AND embedding_id IS NOT NULL",
                String.class);
    }

    private static float[] vec(String text) {
        float[] vector = new float[DIM];
        int hash = text.hashCode();
        for (int i = 0; i < DIM; i++) {
            vector[i] = ((hash >> (i * 4)) & 0x7F) / 128f;
        }
        return vector;
    }

    private String adminToken() throws Exception {
        return tokenOf(UserRoleConstant.ADMIN, "T30radm_");
    }

    private String userToken() throws Exception {
        return tokenOf(UserRoleConstant.USER, "T30rusr_");
    }

    private String tokenOf(String role, String prefix) throws Exception {
        String account = prefix + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
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

    private String code(String body) throws Exception {
        return jsonMapper.readTree(body).get("code").asText();
    }

    private JsonNode data(String body) throws Exception {
        return jsonMapper.readTree(body).get("data");
    }
}
