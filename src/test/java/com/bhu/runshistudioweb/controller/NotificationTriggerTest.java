package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站内通知 · 触发点测试（走真实接口全流程）
 *
 * 契约正确不代表「该发的时候会发」——这组用例盯三个接线点：
 * 帖子审核结论（通过 / 驳回）、评论回复通过、成员身份授予。
 * 全部走 HTTP 接口（建帖 → 审核 → 查通知），不直接调 Service：
 * 要验证的正是「接口链路走完，通知有没有产生」。
 *
 * 评论为什么走人工审核而不是等 AI：评论先审后发，测试环境未配 AI
 * （判定失败转人工），异步回调与人工审核的先后顺序两种都安全——
 * 人工先审（状态已离开发审），AI 后到（eq 待审更新 0 行），互不干扰。
 * 这也顺带验证了「通知挂在评论真正可见的那一刻」而非提交那一刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationTriggerTest {

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

    private QuizTestSupport support;

    private String adminToken;
    private String authorToken;
    private String replierToken;

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
        // 发帖要成员档案（准入认 studio_member 表）；回复方只要登录
        long authorId = support.createMember(UserRoleConstant.MEMBER);
        long replierId = support.createUser(UserRoleConstant.USER);
        adminToken = support.adminToken();
        authorToken = support.tokenOf(authorId);
        replierToken = support.tokenOf(replierId);
    }

    // ==================== 工具 ====================

    /** 建一篇待审帖子，返回帖子 ID */
    private long createPendingPost(String token, String title) {
        String body = support.postJson("/post/add",
                "{\"title\":\"" + title + "\",\"content\":\"通知触发点测试正文\"}", token);
        assertEquals("00000", support.code(body), "建帖失败：" + body);
        return Long.parseLong(support.data(body).asText());
    }

    /** 审核帖子（批量接口，单个 id 也走它） */
    private void auditPost(long postId, String action, String reason) {
        String json = reason == null
                ? "{\"ids\":[" + postId + "],\"action\":\"" + action + "\"}"
                : "{\"ids\":[" + postId + "],\"action\":\"" + action + "\",\"rejectReason\":\"" + reason + "\"}";
        String body = support.postJson("/admin/post/audit", json, adminToken);
        assertEquals("00000", support.code(body), "审核失败：" + body);
    }

    /** 在我的通知里找指定类型的那条（找不到返回 null） */
    private JsonNode findType(String token, String type) {
        String page = support.getBody("/notification/page?current=1&pageSize=50", token);
        for (JsonNode record : support.data(page).get("records")) {
            if (type.equals(record.get("type").asText())) {
                return record;
            }
        }
        return null;
    }

    // ==================== 帖子审核 ====================

    @Test
    @DisplayName("帖子审核通过：作者收到 POST_APPROVED")
    void approvedPostNotifiesAuthor() {
        long postId = createPendingPost(authorToken, "通知测试-通过");

        auditPost(postId, "approve", null);

        String body = support.getBody("/notification/page", authorToken);
        JsonNode records = support.data(body).get("records");
        assertEquals(1, records.size(), "通过审核应产生一条通知：" + body);
        JsonNode item = records.get(0);
        assertEquals("POST_APPROVED", item.get("type").asText());
        assertEquals("帖子审核通过", item.get("typeDesc").asText(), "typeDesc 由服务端按枚举填");
        assertTrue(item.get("title").asText().contains("已通过"), item.get("title").asText());
        assertTrue(item.get("content").asText().contains("通知测试-通过"),
                "正文应带帖子标题（快照）：" + item.get("content").asText());
        // 未读数立刻兑现（铃铛的数据来源）
        assertEquals(1, support.data(support.getBody("/notification/unread-count", authorToken)).asLong());
    }

    @Test
    @DisplayName("帖子被驳回：作者收到 POST_REJECTED，正文带理由")
    void rejectedPostNotifiesAuthorWithReason() {
        long postId = createPendingPost(authorToken, "通知测试-驳回");

        auditPost(postId, "reject", "内容与工作室业务无关");

        String body = support.getBody("/notification/page", authorToken);
        JsonNode item = support.data(body).get("records").get(0);
        assertEquals("POST_REJECTED", item.get("type").asText());
        assertTrue(item.get("content").asText().contains("内容与工作室业务无关"),
                "驳回通知必须带理由——那是作者改稿的唯一依据：" + item);
    }

    @Test
    @DisplayName("批量审核：逐个通知，互不撞键")
    void batchAuditNotifiesEachPost() {
        long post1 = createPendingPost(authorToken, "通知测试-批量1");
        long post2 = createPendingPost(authorToken, "通知测试-批量2");

        String body = support.postJson("/admin/post/audit",
                "{\"ids\":[" + post1 + "," + post2 + "],\"action\":\"approve\"}", adminToken);
        assertEquals("00000", support.code(body), body);
        assertEquals(2, support.data(body).asInt(), "两条都应被处理");

        // 同一作者同一类型两条：bizId 不同，唯一键不冲突，两条都在
        String page = support.getBody("/notification/page", authorToken);
        assertEquals(2, support.data(page).get("records").size(),
                "批量审核应逐个通知（省管理员的时间，不该省作者的知情权）：" + page);
    }

    @Test
    @DisplayName("重复审核不重复通知（幂等：只处理待审帖）")
    void reAuditDoesNotDuplicate() {
        long postId = createPendingPost(authorToken, "通知测试-重复审");
        auditPost(postId, "approve", null);

        // 同一批再审一次：接口幂等（0 行），也不该产生第二条通知
        String body = support.postJson("/admin/post/audit",
                "{\"ids\":[" + postId + "],\"action\":\"approve\"}", adminToken);
        assertEquals("00000", support.code(body), body);
        assertEquals(0, support.data(body).asInt(), "已审核过的不该被重复处理");

        String page = support.getBody("/notification/page", authorToken);
        assertEquals(1, support.data(page).get("records").size(), "重复审核不能产生重复通知：" + page);
    }

    // ==================== 评论回复 ====================

    @Test
    @DisplayName("评论回复通过审核：父评论作者收到 COMMENT_REPLIED")
    void approvedReplyNotifiesParentAuthor() {
        long postId = createPendingPost(authorToken, "通知测试-回复");
        auditPost(postId, "approve", null);
        // 作者先发一条顶层评论
        String topBody = support.postJson("/post/comment/add",
                "{\"postId\":" + postId + ",\"content\":\"顶层评论\"}", authorToken);
        assertEquals("00000", support.code(topBody), topBody);
        long topCommentId = Long.parseLong(support.data(topBody).asText());

        // 另一用户回复它（此时评论还是待审——提前通知会让人点开看不到内容）
        String replyBody = support.postJson("/post/comment/add",
                "{\"postId\":" + postId + ",\"parentId\":" + topCommentId + ",\"content\":\"楼中楼回复\"}",
                replierToken);
        assertEquals("00000", support.code(replyBody), replyBody);
        long replyId = Long.parseLong(support.data(replyBody).asText());

        // 审核通过这一刻才通知
        String auditBody = support.postJson("/admin/post/comment/audit",
                "{\"ids\":[" + replyId + "],\"action\":\"approve\"}", adminToken);
        assertEquals("00000", support.code(auditBody), auditBody);

        // 作者此时应有两条：帖子审核通过 + 评论被回复。按类型取回复那条验证内容
        JsonNode replyNotification = findType(authorToken, "COMMENT_REPLIED");
        assertNotNull(replyNotification, "回复通过审核后父评论作者应收到通知");
        assertTrue(replyNotification.get("content").asText().contains("通知测试-回复"),
                "回复通知应带帖子标题做上下文：" + replyNotification);
    }

    @Test
    @DisplayName("回复自己的评论：不给自己发")
    void selfReplyDoesNotNotify() {
        long postId = createPendingPost(authorToken, "通知测试-自回复");
        auditPost(postId, "approve", null);
        String topBody = support.postJson("/post/comment/add",
                "{\"postId\":" + postId + ",\"content\":\"顶层\"}", authorToken);
        long topCommentId = Long.parseLong(support.data(topBody).asText());

        String replyBody = support.postJson("/post/comment/add",
                "{\"postId\":" + postId + ",\"parentId\":" + topCommentId + ",\"content\":\"自回复\"}",
                authorToken);
        long replyId = Long.parseLong(support.data(replyBody).asText());
        support.postJson("/admin/post/comment/audit",
                "{\"ids\":[" + replyId + "],\"action\":\"approve\"}", adminToken);

        // 作者的列表里只应有「帖子审核通过」，不该有「评论被回复」
        JsonNode selfReplyNotification = findType(authorToken, "COMMENT_REPLIED");
        assertEquals(null, selfReplyNotification, "不能给自己发通知");
    }

    // ==================== 成员授予 ====================

    @Test
    @DisplayName("授予成员身份：用户收到 MEMBER_GRANTED；改资料不重复发")
    void memberGrantNotifiesOnce() {
        // setUp 里 replier 是普通 user；把他提为 member
        long replierId = Long.parseLong(
                support.data(support.getBody("/user/current", replierToken)).get("id").asText());

        String updateBody = support.postJson("/user/update",
                "{\"id\":" + replierId + ",\"userRole\":\"" + UserRoleConstant.MEMBER + "\"}", adminToken);
        assertEquals("00000", support.code(updateBody), updateBody);

        String page = support.getBody("/notification/page", replierToken);
        assertEquals(1, support.data(page).get("records").size(), "授予身份应通知一次：" + page);
        assertEquals("MEMBER_GRANTED", support.data(page).get("records").get(0).get("type").asText());

        // 再改一次昵称（角色没变）：不该产生新通知——「变化」才是触发条件
        String renameBody = support.postJson("/user/update",
                "{\"id\":" + replierId + ",\"userName\":\"新昵称\"}", adminToken);
        assertEquals("00000", support.code(renameBody), renameBody);

        String page2 = support.getBody("/notification/page", replierToken);
        assertEquals(1, support.data(page2).get("records").size(),
                "角色没变时改资料不该重复发通知：" + page2);
    }
}
