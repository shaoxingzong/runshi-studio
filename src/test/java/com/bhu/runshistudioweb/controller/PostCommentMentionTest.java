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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 楼中楼「互相 @」契约测试（走真实接口全流程）
 *
 * <p>覆盖：一级评论 = 楼层、层内扁平回复、第二层之间能互相回复并显式 @ 到对方、
 * 回复只通知被 @ 的人（不吵楼主）、删顶层时把链式回复一并删掉。
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostCommentMentionTest {

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
    private String secondToken;
    private String thirdToken;

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
        long authorId = support.createMember(UserRoleConstant.MEMBER);
        long secondId = support.createUser(UserRoleConstant.USER);
        long thirdId = support.createUser(UserRoleConstant.USER);
        adminToken = support.adminToken();
        authorToken = support.tokenOf(authorId);
        secondToken = support.tokenOf(secondId);
        thirdToken = support.tokenOf(thirdId);
    }

    @Test
    @DisplayName("第二层互相回复：parentId 指向被回复的回复，VO 带 @ 姓名，展示仍扁平挂在同一楼层")
    void secondLevelReplyToEachOther() {
        long postId = createApprovedPost();
        long top = addComment(authorToken, postId, null, "1 楼：顶层评论");
        approve(top);
        long second = addComment(secondToken, postId, top, "层内第一条回复");
        approve(second);
        long third = addComment(thirdToken, postId, second, "层内互相回复（回复第二条，不是顶层）");
        approve(third);

        JsonNode tops = commentList(postId);
        assertEquals(1, tops.size(), "一级评论只有 1 个楼层：" + tops);
        JsonNode floor = tops.get(0);
        assertEquals(1, floor.get("floor").asInt(), "顶层评论是 1 楼");
        JsonNode children = floor.get("children");
        assertEquals(2, children.size(), "层内扁平：两条回复都挂在同一楼层下，不能嵌套成树：" + children);

        JsonNode thirdVo = null;
        for (JsonNode child : children) {
            if (third == Long.parseLong(child.get("id").asText())) {
                thirdVo = child;
            }
        }
        assertNotNull(thirdVo, "第三条必须能在楼层 children 里找到");
        assertEquals(String.valueOf(second), thirdVo.get("parentId").asText(),
                "parentId 必须记录「直接回复谁」：第二条回复，而不是顶层 ID");
        assertEquals(String.valueOf(second), thirdVo.get("replyToId").asText());
        assertNotNull(thirdVo.get("replyToName"), "VO 必须带被回复者展示名，前端才能渲染 @某某");
        assertTrue(!thirdVo.get("replyToName").asText().isBlank());
    }

    @Test
    @DisplayName("层内互相回复只通知被 @ 的人，帖子作者不再被吵醒")
    void replyNotifiesOnlyMentionedUser() {
        long postId = createApprovedPost();
        long top = addComment(authorToken, postId, null, "1 楼：楼主自己发的顶层评论");
        approve(top);
        long second = addComment(secondToken, postId, top, "层内第一条回复");
        approve(second);
        // 这一步回复的是楼主的顶层评论，被 @ 的人正是楼主，收到 COMMENT_REPLIED 是对的。
        int before = notificationCount(authorToken);

        long third = addComment(thirdToken, postId, second, "@第二条回复");
        approve(third);

        // 层内互相回复（third → second）不该再给楼主加任何通知
        assertEquals(before, notificationCount(authorToken),
                "层内互相 @ 不该打扰帖子作者：" + support.getBody("/notification/page", authorToken));
        assertEquals(0, countType(authorToken, "POST_COMMENTED"),
                "回复类通知不该再叠加 POST_COMMENTED");

        // 被 @ 的人（第二条回复作者）收到 COMMENT_REPLIED，文案带 @ 姓名与摘要
        JsonNode item = findType(secondToken, "COMMENT_REPLIED");
        assertNotNull(item, "被 @ 的人应收到回复通知");
        assertTrue(item.get("title").asText().contains("@"), "文案要带 @ 语义：" + item);
        assertTrue(item.get("content").asText().contains("层内互相回复") || item.get("content").asText().contains("@第二"),
                "通知正文应带回复摘要：" + item);
    }

    @Test
    @DisplayName("同一人在同一楼层被多次 @：每次都独立留一条（不被唯一键吞掉）")
    void repeatedMentionNotSwallowed() {
        long postId = createApprovedPost();
        long top = addComment(authorToken, postId, null, "1 楼");
        approve(top);
        long second = addComment(secondToken, postId, top, "我先说一句");
        approve(second);
        long firstMention = addComment(thirdToken, postId, second, "第一次 @");
        approve(firstMention);
        long secondMention = addComment(thirdToken, postId, second, "第二次 @");
        approve(secondMention);

        assertEquals(2, countType(secondToken, "COMMENT_REPLIED"),
                "bizId 用评论 ID 后，两次 @ 应各自留下一条");
    }

    @Test
    @DisplayName("删除顶层评论：链式回复（A→B→C）一并删除，不留孤儿")
    void deleteFloorRemovesChain() {
        long postId = createApprovedPost();
        long top = addComment(authorToken, postId, null, "1 楼");
        approve(top);
        long second = addComment(secondToken, postId, top, "A 回 B");
        approve(second);
        long third = addComment(thirdToken, postId, second, "B 回 C");
        approve(third);

        String body = support.postJson("/admin/post/comment/delete",
                "{\"id\":" + top + "}", adminToken);
        assertEquals("00000", support.code(body), body);
        assertEquals(3, support.data(body).asInt(), "顶层 + 两层链式回复应全部删除：" + body);

        assertEquals(0, commentList(postId).size(), "删完楼层后评论区应为空");
    }

    // 工具

    private long createApprovedPost() {
        String body = support.postJson("/post/add",
                "{\"title\":\"楼层 @ 测试\",\"content\":\"正文\"}", authorToken);
        assertEquals("00000", support.code(body), body);
        long postId = Long.parseLong(support.data(body).asText());
        String audit = support.postJson("/admin/post/audit",
                "{\"ids\":[" + postId + "],\"action\":\"approve\"}", adminToken);
        assertEquals("00000", support.code(audit), audit);
        return postId;
    }

    private long addComment(String token, long postId, Long parentId, String content) {
        String json = "{\"postId\":" + postId + ",\"content\":\"" + content + "\""
                + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
        String body = support.postJson("/post/comment/add", json, token);
        assertEquals("00000", support.code(body), body);
        return Long.parseLong(support.data(body).asText());
    }

    private void approve(long commentId) {
        String body = support.postJson("/admin/post/comment/audit",
                "{\"ids\":[" + commentId + "],\"action\":\"approve\"}", adminToken);
        assertEquals("00000", support.code(body), body);
    }

    private JsonNode commentList(long postId) {
        String body = support.getBody("/post/comment/list?postId=" + postId, null);
        assertEquals("00000", support.code(body), body);
        return support.data(body);
    }

    private JsonNode findType(String token, String type) {
        String page = support.getBody("/notification/page?current=1&pageSize=50", token);
        for (JsonNode record : support.data(page).get("records")) {
            if (type.equals(record.get("type").asText())) {
                return record;
            }
        }
        return null;
    }

    private int countType(String token, String type) {
        String page = support.getBody("/notification/page?current=1&pageSize=50", token);
        int count = 0;
        for (JsonNode record : support.data(page).get("records")) {
            if (type.equals(record.get("type").asText())) {
                count++;
            }
        }
        return count;
    }

    private int notificationCount(String token) {
        String page = support.getBody("/notification/page?current=1&pageSize=50", token);
        return support.data(page).get("records").size();
    }
}
