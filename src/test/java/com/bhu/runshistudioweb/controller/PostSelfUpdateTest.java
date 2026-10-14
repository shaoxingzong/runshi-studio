package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bhu.runshistudioweb.manager.AiCircuitBreaker;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 作者自助改稿验收测试（改自己被驳回的帖子）
 *
 * author: shaoshing
 *
 * <p><b>覆盖的规则</b>（与产品口径一一对应）：
 * <ul>
 *     <li><b>准入</b>：未登录 A0201；非本人的帖子按「不存在」A0402（同码同提示，
 *     不暴露存在性）；待审 / 已通过的帖子同样 A0402（作者只能改已驳回的）；</li>
 *     <li><b>改稿效果</b>：标题 / 正文更新、状态重置为待审、上一轮审核痕迹清空
 *     （audit_by / audit_at / reject_reason / ai_audit_status）；</li>
 *     <li><b>正文可读</b>：{@code /post/my/detail} 让作者读到驳回稿的正文，
 *     且<b>不累加浏览量</b>；非本人、已通过的帖子读不到；</li>
 *     <li><b>校验同源</b>：外链图片被拒、空标题 / 空正文被拒，且被拒时不改动任何内容。</li>
 * </ul>
 *
 * <p><b>为什么不担心编辑触发的异步 AI 初判</b>：测试环境没有配置 AI，
 * {@code ContentAuditManager#judge} 立刻失败并转人工，而该结论不改 status；
 * 且它跑在虚拟线程上，会先被本测试事务持有的行锁挡住。因此断言只盯确定性字段。
 *
 * <p><b>类名不能以 {@code Admin} 开头</b>：会排在 {@code Ai*} 测试之前导致
 * Spring 上下文被 stop、后续登录类批量失败（见 {@code PostManageTest} 注释）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostSelfUpdateTest {

    private static final String ACCOUNT_PREFIX = "T35self_";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioPostMapper studioPostMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    /**
     * AI 熔断器：每个用例前重置。
     *
     * <p>改稿会触发「重新送 AI 初判」，而测试环境没有配置 AI，每次都是失败——
     * 连续几个用例就会把熔断器打开，传染给后跑的测试类。
     */
    @Resource
    private AiCircuitBreaker circuitBreaker;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker.reset();
    }

    // ==================== ① 准入 ====================

    @Test
    @DisplayName("准入：未登录 A0201；非本人 / 待审 / 已通过一律按「不存在」A0402")
    void accessControl() throws Exception {
        long authorId = createUser();
        String author = tokenOf(authorId);
        long rejected = createPost(authorId, AuditStatusEnum.REJECTED);
        long pending = createPost(authorId, AuditStatusEnum.PENDING);
        long approved = createPost(authorId, AuditStatusEnum.APPROVED);

        // 未登录
        assertEquals("A0201", code(postJson("/post/update-mine",
                updateJson(rejected, "t", "c"), null)), "未登录应 A0201");

        // 别人的 token 改本文：同码同提示，不暴露存在性
        String other = tokenOf(createUser());
        String cross = postJson("/post/update-mine", updateJson(rejected, "t", "c"), other);
        assertEquals("A0402", code(cross), "非本人应 A0402：" + cross);
        assertEquals("帖子不存在", message(cross), "非本人应与不存在同提示");

        // 待审 / 已通过的帖子也不能改（作者只能改已驳回）
        assertEquals("A0402", code(postJson("/post/update-mine",
                updateJson(pending, "t", "c"), author)), "待审帖不应允许作者改");
        assertEquals("A0402", code(postJson("/post/update-mine",
                updateJson(approved, "t", "c"), author)), "已通过帖不应允许作者改");
    }

    // ==================== ② 改稿效果 ====================

    @Test
    @DisplayName("改稿：内容更新、状态重置为待审、上一轮审核痕迹全部清空")
    void reeditResetsToPending() throws Exception {
        long authorId = createUser();
        String author = tokenOf(authorId);
        long postId = createPost(authorId, AuditStatusEnum.REJECTED, "T35-驳回稿");

        // 打上「已审过」的痕迹（驳回理由 + 审核人 + 审核时间 + AI 结论）
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getRejectReason, "内容需要补充技术细节")
                .set(StudioPost::getAuditBy, 123456789L)
                .set(StudioPost::getAuditAt, LocalDateTime.now())
                .set(StudioPost::getAiAuditStatus, 3));

        String body = postJson("/post/update-mine",
                updateJson(postId, "T35-改后标题", "T35-改后正文"), author);
        assertEquals("00000", code(body), "改稿应成功：" + body);

        StudioPost after = studioPostMapper.selectById(postId);
        assertEquals("T35-改后标题", after.getTitle(), "标题应已更新");
        assertEquals("T35-改后正文", after.getContent(), "正文应已更新");
        assertEquals(AuditStatusEnum.PENDING.getValue(), after.getStatus(), "应重置为待审");
        // 上一轮内容的审核痕迹全部作废
        assertNull(after.getRejectReason(), "驳回理由应清空");
        assertNull(after.getAuditBy(), "审核人应清空");
        assertNull(after.getAuditAt(), "审核时间应清空");
        assertEquals(0, after.getAiAuditStatus(), "AI 结论应复位为未判");
    }

    @Test
    @DisplayName("改稿：摘要与封面留空时兜底生成（与发帖同规则）")
    void reeditGeneratesFallbacks() throws Exception {
        long authorId = createUser();
        String author = tokenOf(authorId);
        long postId = createPost(authorId, AuditStatusEnum.REJECTED);

        String body = postJson("/post/update-mine", "{\"id\":" + postId
                + ",\"title\":\"T35-兜底\",\"content\":\"![图](/uploads/2026/10/x.png) 正文内容\"}", author);
        assertEquals("00000", code(body), "改稿应成功：" + body);

        StudioPost after = studioPostMapper.selectById(postId);
        assertNotNull(after.getSummary(), "摘要为空时应有兜底值");
        assertEquals("/uploads/2026/10/x.png", after.getCoverImage(), "封面应取正文首图");
    }

    @Test
    @DisplayName("改稿：外链图片被拒、空标题 / 空正文被拒，且被拒时不改动内容")
    void reeditValidatesContent() throws Exception {
        long authorId = createUser();
        String author = tokenOf(authorId);
        long postId = createPost(authorId, AuditStatusEnum.REJECTED, "T35-原文");
        setContent(postId, "T35-原正文");

        String externalImage = "{\"id\":" + postId + ",\"title\":\"t\","
                + "\"content\":\"![x](http://evil.example/x.png)\"}";
        assertEquals("A0401", code(postJson("/post/update-mine", externalImage, author)),
                "外链图片应被拒（与发帖同规则）");
        assertEquals("A0401", code(postJson("/post/update-mine",
                "{\"id\":" + postId + ",\"title\":\"\",\"content\":\"c\"}", author)), "空标题应 A0401");
        assertEquals("A0401", code(postJson("/post/update-mine",
                "{\"id\":" + postId + ",\"title\":\"t\",\"content\":\"\"}", author)), "空正文应 A0401");

        StudioPost after = studioPostMapper.selectById(postId);
        assertEquals("T35-原文", after.getTitle(), "校验失败不应改动标题");
        assertEquals("T35-原正文", after.getContent(), "校验失败不应改动正文");
        assertEquals(AuditStatusEnum.REJECTED.getValue(), after.getStatus(), "校验失败不应改状态");
    }

    // ==================== ③ 作者读自己的未公开稿 ====================

    @Test
    @DisplayName("详情：作者可读自己驳回稿的正文，且不累加浏览量")
    void myDetailReturnsContentWithoutCountingView() throws Exception {
        long authorId = createUser();
        String author = tokenOf(authorId);
        long postId = createPost(authorId, AuditStatusEnum.REJECTED);
        setContent(postId, "T35-被驳回的正文");

        String body = getBody("/post/my/detail?id=" + postId, author);
        assertEquals("00000", code(body), "作者应能读自己的驳回稿：" + body);
        assertEquals("T35-被驳回的正文",
                jsonMapper.readTree(body).get("data").get("content").asText());
        assertEquals(0, viewCountOf(postId), "读自己的稿子不应累加浏览量");

        // 待审稿也能预览（确认自己发出去的样子）
        long pending = createPost(authorId, AuditStatusEnum.PENDING);
        setContent(pending, "T35-待审的正文");
        String pendingBody = getBody("/post/my/detail?id=" + pending, author);
        assertEquals("00000", code(pendingBody), "作者应能预览自己的待审稿");
    }

    @Test
    @DisplayName("详情：非本人 / 已通过的帖子读不到；未登录 A0201")
    void myDetailAccessControl() throws Exception {
        long authorId = createUser();
        long postId = createPost(authorId, AuditStatusEnum.REJECTED);
        setContent(postId, "T35-正文");

        // 已通过的走公开详情，本接口不覆盖
        long approved = createPost(authorId, AuditStatusEnum.APPROVED);
        String approvedBody = getBody("/post/my/detail?id=" + approved, tokenOf(authorId));
        assertEquals("A0402", code(approvedBody), "已通过的帖子不应走本接口");

        // 非本人
        String other = tokenOf(createUser());
        assertEquals("A0402", code(getBody("/post/my/detail?id=" + postId, other)),
                "非本人应 A0402（不暴露存在性）");

        // 未登录
        assertEquals("A0201", code(getBody("/post/my/detail?id=" + postId, null)), "未登录应 A0201");
    }

    // ==================== 工具方法 ====================

    private String postJson(String path, String json, String tokenValue) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String getBody(String path, String tokenValue) throws Exception {
        var request = get(path);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String code(String body) throws Exception {
        return jsonMapper.readTree(body).get("code").asText();
    }

    private String message(String body) throws Exception {
        return jsonMapper.readTree(body).get("message").asText();
    }

    private static String updateJson(long postId, String title, String content) {
        return "{\"id\":" + postId + ",\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
    }

    private int viewCountOf(long postId) {
        Integer views = studioPostMapper.selectById(postId).getViewCount();
        return views == null ? 0 : views;
    }

    private void setContent(long postId, String content) {
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getContent, content));
    }

    private long createUser() {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(com.bhu.runshistudioweb.constant.UserRoleConstant.USER);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    private long createPost(long authorId, AuditStatusEnum status) {
        return createPost(authorId, status, "T35-post-" + (System.nanoTime() % 100000));
    }

    private long createPost(long authorId, AuditStatusEnum status, String title) {
        StudioPost post = new StudioPost();
        post.setTitle(title);
        post.setContent("T35-post-content");
        post.setAuthorId(authorId);
        post.setStatus(status.getValue());
        post.setViewCount(0);
        post.setCommentCount(0);
        post.setLikeCount(0);
        post.setPinned(0);
        studioPostMapper.insert(post);
        return post.getId();
    }

    private String tokenOf(long userId) throws Exception {
        String account = jdbcTemplate.queryForObject(
                "SELECT user_account FROM sys_user WHERE id = ?", String.class, userId);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }
}
