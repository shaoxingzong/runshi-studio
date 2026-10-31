package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioPostLikeMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import jakarta.annotation.Resource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 帖子点赞验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostLikeTest {

    /** 测试账号前缀（便于在数据里认出本类产生的用户） */
    private static final String ACCOUNT_PREFIX = "T32like_";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioPostMapper studioPostMapper;

    @Resource
    private StudioPostLikeMapper studioPostLikeMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    // ① 鉴权

    @Test
    @DisplayName("鉴权：未登录点赞 A0201，且不产生任何数据")
    void anonymousLikeIsRejected() throws Exception {
        long postId = createApprovedPost(createUser(UserRoleConstant.USER));

        String body = like(postId, null);
        assertEquals("A0201", code(body), "未登录点赞应 A0201：" + body);
        assertEquals(0, likeCount(postId), "未登录不应产生计数");
        assertEquals(0, likeRows(postId), "未登录不应产生点赞关系行");
    }

    // ② 点赞成功

    @Test
    @DisplayName("点赞成功：计数 +1、落一条关系行、详情与列表均标记 liked=true")
    void likeSucceedsAndPersists() throws Exception {
        long userId = createUser(UserRoleConstant.USER);
        String token = tokenOf(userId);
        long postId = createApprovedPost(userId);

        String body = like(postId, token);
        assertEquals("00000", code(body), "点赞应成功：" + body);
        assertEquals(1, dataValue(body), "应返回最新点赞数 1");
        assertEquals(1, likeCount(postId), "DB 冗余计数应为 1");
        assertEquals(1, likeRows(postId), "应落 1 条点赞关系行");

        String detail = getBody("/post/detail?id=" + postId, token);
        assertTrue(detail.contains("\"liked\":true"), "详情应标记已赞：" + detail);
    }

    // ③ 幂等（只能赞不能取消）

    @Test
    @DisplayName("幂等：同一用户重复点赞不重复计数，也没有取消入口")
    void duplicateLikeIsIdempotent() throws Exception {
        long userId = createUser(UserRoleConstant.USER);
        String token = tokenOf(userId);
        long postId = createApprovedPost(userId);

        assertEquals(1, dataValue(like(postId, token)), "首次点赞应返回 1");

        String second = like(postId, token);
        assertEquals("00000", code(second), "重复点赞应成功（幂等）而不是报错：" + second);
        assertEquals(1, dataValue(second), "重复点赞不应重复计数");
        assertEquals(1, likeCount(postId), "DB 计数应仍为 1");
        assertEquals(1, likeRows(postId), "关系行应仍为 1 条");
    }

    // ④ 只有已通过的文章可赞

    @Test
    @DisplayName("帖子不存在：A0402，提示与详情接口一致")
    void likeNonExistentPost() throws Exception {
        String token = tokenOf(createUser(UserRoleConstant.USER));

        String body = like(999999999999999999L, token);
        assertEquals("A0402", code(body), "不存在的帖子应 A0402：" + body);
        assertEquals("帖子不存在", message(body), "提示应与详情接口同一口径");
    }

    @Test
    @DisplayName("待审文章不可赞：同样 A0402（不暴露存在性）")
    void likePendingPostIsRejected() throws Exception {
        long userId = createUser(UserRoleConstant.USER);
        String token = tokenOf(userId);
        long postId = createApprovedPost(userId);

        // 改回待审：模拟尚未通过审核的内容
        StudioPost pending = new StudioPost();
        pending.setId(postId);
        pending.setStatus(AuditStatusEnum.PENDING.getValue());
        studioPostMapper.updateById(pending);

        String body = like(postId, token);
        assertEquals("A0402", code(body), "待审文章不应可赞：" + body);
        assertEquals(0, likeCount(postId), "拒绝时不应产生计数");
        assertEquals(0, likeRows(postId), "拒绝时不应产生关系行");
    }

    @Test
    @DisplayName("参数非法：postId 缺失或非正数返回 A0401")
    void likeWithInvalidPostId() throws Exception {
        String token = tokenOf(createUser(UserRoleConstant.USER));
        assertEquals("A0401", code(postJson("/post/like", "{}", token)), "缺 postId 应 A0401");
        assertEquals("A0401", code(postJson("/post/like", "{\"postId\":-1}", token)), "负数应 A0401");
    }

    // ⑤ 多用户

    @Test
    @DisplayName("多用户：各自点赞计数累加，关系行一人一条")
    void multipleUsersEachLike() throws Exception {
        long userA = createUser(UserRoleConstant.USER);
        long userB = createUser(UserRoleConstant.USER);
        long postId = createApprovedPost(userA);

        like(postId, tokenOf(userA));
        like(postId, tokenOf(userB));

        assertEquals(2, likeCount(postId), "两人各赞一次，计数应为 2");
        assertEquals(2, likeRows(postId), "关系行应一人一条");
    }

    // ⑥ liked 的读取填充

    @Test
    @DisplayName("列表 liked：已赞的文章 true，未赞的 false")
    void listMarksLikedPerUser() throws Exception {
        long userId = createUser(UserRoleConstant.USER);
        String token = tokenOf(userId);
        long likedPost = createApprovedPost(userId);
        long untouchedPost = createApprovedPost(userId);

        like(likedPost, token);

        String list = getBody("/post/list/page?current=1&pageSize=50", token);
        assertEquals(Boolean.TRUE, likedOf(list, likedPost), "已赞文章应 liked=true：" + list);
        assertEquals(Boolean.FALSE, likedOf(list, untouchedPost), "未赞文章应 liked=false：" + list);
    }

    @Test
    @DisplayName("游客看详情与列表：liked 恒为 false")
    void guestSeesLikedFalse() throws Exception {
        long userId = createUser(UserRoleConstant.USER);
        long postId = createApprovedPost(userId);

        String detail = getBody("/post/detail?id=" + postId, null);
        assertTrue(detail.contains("\"liked\":false"), "游客看详情 liked 应为 false：" + detail);

        String list = getBody("/post/list/page?current=1&pageSize=50", null);
        assertEquals(Boolean.FALSE, likedOf(list, postId), "游客看列表 liked 应为 false：" + list);
    }

    // 工具方法

    /** 发起点赞，返回响应体 */
    private String like(long postId, String token) throws Exception {
        return postJson("/post/like", "{\"postId\":" + postId + "}", token);
    }

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

    /** 取响应体 data 的数值（点赞接口返回最新点赞数） */
    private long dataValue(String body) throws Exception {
        return jsonMapper.readTree(body).get("data").asLong();
    }

    /** 从列表响应里找某篇帖子的 liked 值；没找到返回 null（让断言失败时信息更明确） */
    private Boolean likedOf(String listBody, long postId) throws Exception {
        JsonNode records = jsonMapper.readTree(listBody).get("data").get("records");
        for (JsonNode node : records) {
            if (node.get("id").asText().equals(String.valueOf(postId))) {
                return node.get("liked").asBoolean();
            }
        }
        return null;
    }

    private long likeCount(long postId) {
        Integer count = studioPostMapper.selectById(postId).getLikeCount();
        return count == null ? 0 : count;
    }

    private long likeRows(long postId) {
        return studioPostLikeMapper.selectCount(new LambdaQueryWrapper<StudioPostLike>()
                .eq(StudioPostLike::getPostId, postId));
    }

    /** 建一个指定角色的测试用户，返回用户 id */
    private long createUser(String role) {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 登录并取 token */
    private String tokenOf(long userId) throws Exception {
        String account = jdbcTemplate.queryForObject(
                "SELECT user_account FROM sys_user WHERE id = ?", String.class, userId);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    /** 直接落一篇「已通过」的文章 */
    private long createApprovedPost(long authorId) {
        StudioPost post = new StudioPost();
        post.setTitle("T32-点赞测试文章");
        post.setContent("点赞测试正文");
        post.setAuthorId(authorId);
        post.setStatus(AuditStatusEnum.APPROVED.getValue());
        post.setViewCount(0);
        post.setCommentCount(0);
        post.setLikeCount(0);
        post.setPinned(0);
        studioPostMapper.insert(post);
        return post.getId();
    }
}
