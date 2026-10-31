package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostLikeMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 管理端「帖子管理」验收测试（列表 / 删除 / 置顶 / 编辑）
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostManageTest {

    /** 测试账号前缀（便于在数据里认出本类产生的用户） */
    private static final String ACCOUNT_PREFIX = "T33pm_";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioPostMapper studioPostMapper;

    @Resource
    private StudioPostCommentMapper studioPostCommentMapper;

    @Resource
    private StudioPostLikeMapper studioPostLikeMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    // ① 鉴权矩阵

    @Test
    @DisplayName("鉴权：五个接口匿名 A0201、普通用户 A0301、admin 放行")
    void permissionMatrix() throws Exception {
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.APPROVED);

        // 匿名：默认要求登录
        assertEquals("A0201", code(getBody("/admin/post/list/page", null)), "列表匿名应 A0201");
        assertEquals("A0201", code(postJson("/admin/post/delete", "{\"id\":" + postId + "}", null)),
                "删除匿名应 A0201");
        assertEquals("A0201", code(postJson("/admin/post/pin", pinJson(postId, 1), null)),
                "置顶匿名应 A0201");
        assertEquals("A0201", code(postJson("/admin/post/update", updateJson(postId, "x", "y"), null)),
                "编辑匿名应 A0201");
        assertEquals("A0201", code(getBody("/admin/post/detail?id=" + postId, null)),
                "详情匿名应 A0201");

        // 普通用户：有登录态但角色不够
        String userToken = tokenOf(createUser(UserRoleConstant.USER));
        assertEquals("A0301", code(getBody("/admin/post/list/page", userToken)), "列表普通用户应 A0301");
        assertEquals("A0301", code(postJson("/admin/post/delete", "{\"id\":" + postId + "}", userToken)),
                "删除普通用户应 A0301");
        assertEquals("A0301", code(postJson("/admin/post/pin", pinJson(postId, 1), userToken)),
                "置顶普通用户应 A0301");
        assertEquals("A0301", code(postJson("/admin/post/update", updateJson(postId, "x", "y"), userToken)),
                "编辑普通用户应 A0301");
        assertEquals("A0301", code(getBody("/admin/post/detail?id=" + postId, userToken)),
                "详情普通用户应 A0301");

        // admin：放行（用「穿过权限层到达业务层」作证据——列表能查、详情能读）
        String admin = adminToken();
        assertEquals("00000", code(getBody("/admin/post/list/page", admin)), "admin 应能查列表");
        assertEquals("00000", code(getBody("/admin/post/detail?id=" + postId, admin)), "admin 应能读详情");
    }

    // ② 全量列表与筛选

    @Test
    @DisplayName("列表：不传 status 三种状态都能查到，且带作者昵称")
    void listAllStatusesWithAuthorName() throws Exception {
        String admin = adminToken();
        long authorId = createUser(UserRoleConstant.USER);
        long pending = createPost(authorId, AuditStatusEnum.PENDING);
        long approved = createPost(authorId, AuditStatusEnum.APPROVED);
        long rejected = createPost(authorId, AuditStatusEnum.REJECTED);

        String body = getBody("/admin/post/list/page?current=1&pageSize=50", admin);
        assertEquals("00000", code(body), "admin 查列表应成功：" + body);
        List<Long> ids = idsOf(body);
        assertTrue(ids.contains(pending), "待审帖应出现在全量列表：" + body);
        assertTrue(ids.contains(approved), "已通过帖应出现在全量列表");
        assertTrue(ids.contains(rejected), "已驳回帖应出现在全量列表");

        // 作者昵称被回填（测试账号的昵称与账号同名，取到任一个即可）
        String authorName = authorNameOf(body, approved);
        assertNotNull(authorName, "作者昵称不应为空：" + body);
        assertFalse(authorName.isBlank(), "作者昵称不应是空白串");
    }

    @Test
    @DisplayName("筛选：status 精确生效；标题关键词模糊生效")
    void filterByStatusAndTitle() throws Exception {
        String admin = adminToken();
        long authorId = createUser(UserRoleConstant.USER);
        // 关键词用纯 ASCII：中文进查询串要先编码，没必要在这种用例里引入编码噪音
        String keyword = "T33title" + (System.nanoTime() % 100000);
        long approved = createPost(authorId, AuditStatusEnum.APPROVED, keyword + "-approved");
        long pending = createPost(authorId, AuditStatusEnum.PENDING, keyword + "-pending");
        createPost(authorId, AuditStatusEnum.APPROVED, "T33-unrelated");

        // status=1：只有已通过
        String approvedOnly = getBody("/admin/post/list/page?status=1&pageSize=50", admin);
        assertTrue(idsOf(approvedOnly).contains(approved), "已通过帖应在筛选结果里");
        assertFalse(idsOf(approvedOnly).contains(pending), "待审帖不该出现在 status=1 的筛选里：" + approvedOnly);

        // 标题模糊：两条同名关键词的都该命中（跨状态）
        String byTitle = getBody("/admin/post/list/page?titleKeyword=" + keyword + "&pageSize=50", admin);
        List<Long> hit = idsOf(byTitle);
        assertTrue(hit.contains(approved) && hit.contains(pending), "标题模糊应跨状态命中：" + byTitle);

        // 状态 + 标题组合：只剩已通过的那条
        String combined = getBody(
                "/admin/post/list/page?status=1&titleKeyword=" + keyword + "&pageSize=50", admin);
        assertTrue(idsOf(combined).contains(approved), "组合筛选应命中已通过的那条");
        assertFalse(idsOf(combined).contains(pending), "组合筛选不该命中待审的那条：" + combined);
    }

    @Test
    @DisplayName("筛选：作者关键词按账号 / 成员姓名两条路径都能命中")
    void filterByAuthorKeyword() throws Exception {
        String admin = adminToken();
        String account = ACCOUNT_PREFIX + "author" + (System.nanoTime() % 100000);
        long authorId = createUser(account, UserRoleConstant.USER);
        long postId = createPost(authorId, AuditStatusEnum.APPROVED, "T33-author-filter");

        // 按账号名搜（测试账号昵称与账号同名）
        String byAccount = getBody("/admin/post/list/page?authorKeyword=" + account + "&pageSize=50", admin);
        assertTrue(idsOf(byAccount).contains(postId), "按账号名应能搜到：" + byAccount);

        // 按成员姓名：门户展示用的是成员姓名，管理员照着屏幕上的名字搜必须能搜到
        String memberName = "T33成员" + (System.nanoTime() % 100000);
        StudioMember member = new StudioMember();
        member.setUserId(authorId);
        member.setName(memberName);
        member.setGradeYear(2022);
        member.setMemberStatus(MemberStatusEnum.IN_TEAM.getValue());
        member.setTeamPosition("member");
        studioMemberMapper.insert(member);

        String byMemberName = getBody("/admin/post/list/page?authorKeyword=" + memberName + "&pageSize=50", admin);
        assertTrue(idsOf(byMemberName).contains(postId), "按成员姓名应能搜到：" + byMemberName);

        // 作者昵称回填：绑了成员档案后用成员姓名（与门户口径一致），而不是账号名
        assertEquals(memberName, authorNameOf(byMemberName, postId),
                "有成员档案时应显示成员姓名（与门户口径一致）");

        // 关键词一个用户都没命中：返回空页而不是全量
        String noHit = getBody("/admin/post/list/page?authorKeyword=no-such-person-xyz&pageSize=50", admin);
        assertEquals(0, idsOf(noHit).size(), "关键词无命中时应返回空页而不是全量：" + noHit);
    }

    @Test
    @DisplayName("分页兜底：页码 / 每页条数非法不报错，按合理值继续查")
    void pageParamsAreCorrected() throws Exception {
        String admin = adminToken();
        createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.APPROVED, "T33-page-fallback");

        for (String query : List.of("current=0", "current=-5", "pageSize=0", "pageSize=999")) {
            String body = getBody("/admin/post/list/page?" + query, admin);
            assertEquals("00000", code(body), "非法分页参数应被兜底纠正而不是报错：" + query + " → " + body);
        }
    }

    @Test
    @DisplayName("参数非法：status 取值超出枚举返回 A0401")
    void invalidStatusIsRejected() throws Exception {
        String body = getBody("/admin/post/list/page?status=9", adminToken());
        assertEquals("A0401", code(body), "非法 status 应 A0401：" + body);
        assertTrue(message(body).contains("待审"), "提示应列出合法取值：" + message(body));
    }

    // ③ 删除

    @Test
    @DisplayName("删除：同事务级联清理帖子、评论与点赞，公开详情随即不可见")
    void deleteCascadesCommentsAndLikes() throws Exception {
        long adminId = createAdmin();
        String admin = tokenOf(adminId);
        long authorId = createUser(UserRoleConstant.USER);
        long postId = createPost(authorId, AuditStatusEnum.APPROVED, "T33-to-delete");

        // 前置：公开详情能读到（已通过）
        assertEquals("00000", code(getBody("/post/detail?id=" + postId, null)), "删除前公开详情应可读");

        // 造两条评论（一条顶层 + 一条回复）与一个赞
        StudioPostComment parent = new StudioPostComment();
        parent.setPostId(postId);
        parent.setAuthorId(authorId);
        parent.setContent("T33-comment-parent");
        parent.setStatus(AuditStatusEnum.APPROVED.getValue());
        parent.setFloor(1);
        studioPostCommentMapper.insert(parent);

        StudioPostComment reply = new StudioPostComment();
        reply.setPostId(postId);
        reply.setAuthorId(authorId);
        reply.setParentId(parent.getId());
        reply.setContent("T33-comment-reply");
        reply.setStatus(AuditStatusEnum.APPROVED.getValue());
        studioPostCommentMapper.insert(reply);

        StudioPostLike like = new StudioPostLike();
        like.setPostId(postId);
        like.setUserId(authorId);
        studioPostLikeMapper.insert(like);
        assertEquals(2, commentRows(postId), "前置条件：应有 2 条评论");
        assertEquals(1, likeRows(postId), "前置条件：应有 1 条点赞");

        String body = postJson("/admin/post/delete", "{\"id\":" + postId + "}", admin);
        assertEquals("00000", code(body), "删除应成功：" + body);
        assertEquals(3, jsonMapper.readTree(body).get("data").asInt(), "应返回内容行数（帖子 1 + 评论 2）");

        // SQL 取证：帖子与评论逻辑删除（deleted_at 非 0），点赞物理删除
        assertEquals(0, countBy("SELECT COUNT(*) FROM studio_post WHERE id = ? AND deleted_at = 0", postId),
                "帖子应被逻辑删除");
        assertEquals(0, countBy("SELECT COUNT(*) FROM studio_post_comment WHERE post_id = ? AND deleted_at = 0", postId),
                "评论应被级联逻辑删除（含回复）");
        assertEquals(0, countBy("SELECT COUNT(*) FROM studio_post_like WHERE post_id = ?", postId),
                "点赞关系应被级联物理删除");
        // 审计痕迹：操作人写进 updated_by（本表没有 deleted_by 列）
        Long updatedBy = jdbcTemplate.queryForObject(
                "SELECT updated_by FROM studio_post WHERE id = ?", Long.class, postId);
        assertEquals(adminId, updatedBy, "删除人应写入 updated_by");

        // 公开详情随即不可见（删完立刻从门户消失，同码同提示不暴露存在性）
        String detail = getBody("/post/detail?id=" + postId, null);
        assertEquals("A0402", code(detail), "删除后公开详情应 A0402：" + detail);

        // 重复删除：与「不存在」同码同提示
        String again = postJson("/admin/post/delete", "{\"id\":" + postId + "}", admin);
        assertEquals("A0402", code(again), "重复删除应 A0402：" + again);
        assertEquals("帖子不存在", message(again));
    }

    @Test
    @DisplayName("删除：待审帖不可删，引导走驳回（与 deleteComment 同规则）")
    void pendingPostCannotBeDeleted() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.PENDING, "T33-pending-no-delete");

        String body = postJson("/admin/post/delete", "{\"id\":" + postId + "}", admin);
        assertEquals("A0401", code(body), "待审帖删除应被拒绝：" + body);
        assertTrue(message(body).contains("驳回"), "提示应引导使用驳回：" + message(body));
        assertEquals(1, countBy("SELECT COUNT(*) FROM studio_post WHERE id = ? AND deleted_at = 0", postId),
                "拒绝时不应产生任何删除");
    }

    @Test
    @DisplayName("删除：已驳回帖可删（清理场景），参数非法 A0401")
    void rejectedPostCanBeDeleted() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.REJECTED, "T33-rejected-delete");

        assertEquals("A0401", code(postJson("/admin/post/delete", "{}", admin)), "缺 id 应 A0401");
        assertEquals("A0401", code(postJson("/admin/post/delete", "{\"id\":-1}", admin)), "负数应 A0401");
        assertEquals("A0402", code(postJson("/admin/post/delete", "{\"id\":999999999999999999}", admin)),
                "不存在应 A0402");

        assertEquals("00000", code(postJson("/admin/post/delete", "{\"id\":" + postId + "}", admin)),
                "已驳回帖应可删除（清理）");
    }

    // ④ 置顶

    @Test
    @DisplayName("置顶：仅已通过可置顶；置顶后公开列表排在最前，取消后让位")
    void pinOnlyApprovedAndSortsFirst() throws Exception {
        String admin = adminToken();
        long authorId = createUser(UserRoleConstant.USER);
        long newer = createPost(authorId, AuditStatusEnum.APPROVED, "T33-pin-newer");
        long older = createPost(authorId, AuditStatusEnum.APPROVED, "T33-pin-older");
        setCreatedAt(newer, LocalDateTime.now());
        setCreatedAt(older, LocalDateTime.now().minusHours(1));

        // 开发库里可能已有置顶帖：先清零（本测试事务内，结束会回滚）
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getPinned, 1)
                .set(StudioPost::getPinned, 0));

        // 未置顶时：新的在前
        assertEquals(String.valueOf(newer), firstIdOf(publicList(admin)), "未置顶时应按时间倒序");

        // 待审帖置顶被拒
        long pending = createPost(authorId, AuditStatusEnum.PENDING, "T33-pin-pending");
        String rejected = postJson("/admin/post/pin", pinJson(pending, 1), admin);
        assertEquals("A0401", code(rejected), "待审帖置顶应被拒绝：" + rejected);
        assertTrue(message(rejected).contains("已通过"), "提示应说明只有已通过可置顶：" + message(rejected));
        assertEquals(0, pinnedOf(pending), "拒绝时不应改写 pinned");

        // 已通过帖置顶成功，并越过时间顺序排到最前
        assertEquals("00000", code(postJson("/admin/post/pin", pinJson(older, 1), admin)), "置顶应成功");
        assertEquals(1, pinnedOf(older), "pinned 应置为 1");
        assertEquals(String.valueOf(older), firstIdOf(publicList(admin)),
                "置顶帖应排在公开列表最前（公开列表本就按 pinned 倒序，此前没有接口能改它）");

        // 取消置顶：让位给更新的那篇
        assertEquals("00000", code(postJson("/admin/post/pin", pinJson(older, 0), admin)), "取消置顶应成功");
        assertEquals(0, pinnedOf(older), "pinned 应复位为 0");
        assertEquals(String.valueOf(newer), firstIdOf(publicList(admin)), "取消置顶后应回到时间倒序");
    }

    @Test
    @DisplayName("置顶：取消不限状态（编辑后回到待审的帖子也要能取消置顶）")
    void unpinWorksForNonApproved() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.APPROVED, "T33-unpin-pending");
        assertEquals("00000", code(postJson("/admin/post/pin", pinJson(postId, 1), admin)));

        StudioPost patch = new StudioPost();
        patch.setId(postId);
        patch.setStatus(AuditStatusEnum.PENDING.getValue());
        studioPostMapper.updateById(patch);

        assertEquals("00000", code(postJson("/admin/post/pin", pinJson(postId, 0), admin)),
                "待审态应允许取消置顶（恢复操作不该被拦住）");
        assertEquals(0, pinnedOf(postId), "pinned 应复位为 0");
    }

    @Test
    @DisplayName("置顶：pinned 取值非法 A0401；id 非法 A0401")
    void pinWithInvalidParams() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.APPROVED, "T33-pin-params");

        assertEquals("A0401", code(postJson("/admin/post/pin", "{\"id\":" + postId + ",\"pinned\":2}", admin)),
                "pinned=2 应 A0401");
        assertEquals("A0401", code(postJson("/admin/post/pin", "{\"id\":" + postId + "}", admin)),
                "缺 pinned 应 A0401");
        assertEquals("A0401", code(postJson("/admin/post/pin", "{\"id\":-1,\"pinned\":1}", admin)),
                "id 为负应 A0401");
        assertEquals(0, pinnedOf(postId), "参数非法不应改写 pinned");
    }

    // ⑤ 编辑

    @Test
    @DisplayName("详情：管理端可读任意状态的正文，且不累加浏览量")
    void adminDetailReturnsContentWithoutCountingView() throws Exception {
        String admin = adminToken();
        long pending = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.PENDING, "T33-detail-pending");
        setContent(pending, "T33-detail-content");

        // 待审帖也能取到正文（公开详情接口只覆盖已通过，且会自增浏览量）
        String body = getBody("/admin/post/detail?id=" + pending, admin);
        assertEquals("00000", code(body), "待审帖详情应可读：" + body);
        assertEquals("T33-detail-content", jsonMapper.readTree(body).get("data").get("content").asText());
        assertEquals(0, viewCountOf(pending), "管理端详情不应累加浏览量");

        assertEquals("A0402", code(getBody("/admin/post/detail?id=999999999999999999", admin)),
                "不存在的帖子应 A0402");
    }

    @Test
    @DisplayName("编辑：已通过的帖子保存后重置为待审，并清空上一轮审核痕迹")
    void editApprovedPostResetsToPending() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.APPROVED, "T33-edit-before");
        setContent(postId, "T33-edit-before-content");

        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getPinned, 1)
                .set(StudioPost::getAuditBy, 123456789L)
                .set(StudioPost::getAuditAt, LocalDateTime.now())
                .set(StudioPost::getRejectReason, "old-reason"));

        String body = postJson("/admin/post/update",
                updateJson(postId, "T33-edit-after", "T33-edit-after-content"), admin);
        assertEquals("00000", code(body), "编辑应成功：" + body);

        StudioPost after = studioPostMapper.selectById(postId);
        assertEquals("T33-edit-after", after.getTitle(), "标题应已更新");
        assertEquals("T33-edit-after-content", after.getContent(), "正文应已更新");
        // 核心规则：内容变了就必须重新过审
        assertEquals(AuditStatusEnum.PENDING.getValue(), after.getStatus(), "已通过的帖子应重置为待审");
        // 修改前那份内容的审核痕迹全部作废
        assertNull(after.getAuditBy(), "audit_by 应清空");
        assertNull(after.getAuditAt(), "audit_at 应清空");
        assertNull(after.getRejectReason(), "reject_reason 应清空");
        assertEquals(0, after.getAiAuditStatus(), "ai_audit_status 应复位为未判（等待重新送审）");
        // pinned 保留：重新通过后置顶效果自动恢复
        assertEquals(1, after.getPinned(), "pinned 不应被编辑清掉");
    }

    @Test
    @DisplayName("编辑：待审帖保存后仍在待审；摘要与封面留空时兜底生成")
    void editPendingPostStaysPendingWithFallbacks() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.PENDING, "T33-edit-pending");
        setContent(postId, "T33-old-content");

        // 摘要 / 封面都不传：应由 Service 用正文兜底
        String body = postJson("/admin/post/update", "{\"id\":" + postId
                + ",\"title\":\"T33-edit-pending\",\"content\":\"T33-new-content\"}", admin);
        assertEquals("00000", code(body), "编辑待审帖应成功：" + body);

        StudioPost after = studioPostMapper.selectById(postId);
        assertEquals(AuditStatusEnum.PENDING.getValue(), after.getStatus(), "待审帖编辑后仍应是待审");
        assertEquals("T33-new-content", after.getContent(), "正文应已更新");
        assertNotNull(after.getSummary(), "摘要为空时应有兜底值");
    }

    @Test
    @DisplayName("编辑：外链图片被拒；标题 / 正文为空被拒；帖子不存在 A0402")
    void editValidatesContent() throws Exception {
        String admin = adminToken();
        long postId = createPost(createUser(UserRoleConstant.USER), AuditStatusEnum.APPROVED, "T33-edit-validate");
        setContent(postId, "T33-original-content");

        String externalImage = "{\"id\":" + postId + ",\"title\":\"t\","
                + "\"content\":\"![x](http://evil.example/x.png)\"}";
        assertEquals("A0401", code(postJson("/admin/post/update", externalImage, admin)),
                "外链图片应被拒（与发帖同一条规则）");

        assertEquals("A0401", code(postJson("/admin/post/update",
                "{\"id\":" + postId + ",\"title\":\"\",\"content\":\"y\"}", admin)), "空标题应 A0401");
        assertEquals("A0401", code(postJson("/admin/post/update",
                "{\"id\":" + postId + ",\"title\":\"t\",\"content\":\"\"}", admin)), "空正文应 A0401");
        assertEquals("A0402", code(postJson("/admin/post/update",
                updateJson(999999999999999999L, "t", "y"), admin)), "不存在应 A0402");

        // 被拒的请求不应改动任何内容
        StudioPost after = studioPostMapper.selectById(postId);
        assertEquals("T33-edit-validate", after.getTitle(), "校验失败不应改动标题");
        assertEquals("T33-original-content", after.getContent(), "校验失败不应改动正文");
    }

    // 工具方法

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

    private static String pinJson(long postId, int pinned) {
        return "{\"id\":" + postId + ",\"pinned\":" + pinned + "}";
    }

    private static String updateJson(long postId, String title, String content) {
        return "{\"id\":" + postId + ",\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
    }

    /** 取列表响应里全部帖子 id */
    private List<Long> idsOf(String listBody) throws Exception {
        JsonNode records = jsonMapper.readTree(listBody).get("data").get("records");
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : records) {
            ids.add(node.get("id").asLong());
        }
        return ids;
    }

    /** 取列表响应里第一条帖子的 id（用于断言置顶排序） */
    private String firstIdOf(String listBody) throws Exception {
        return jsonMapper.readTree(listBody).get("data").get("records").get(0).get("id").asText();
    }

    /** 取列表响应里某篇帖子的作者昵称 */
    private String authorNameOf(String listBody, long postId) throws Exception {
        for (JsonNode node : jsonMapper.readTree(listBody).get("data").get("records")) {
            if (node.get("id").asLong() == postId) {
                JsonNode name = node.get("authorName");
                return name == null || name.isNull() ? null : name.asText();
            }
        }
        return null;
    }

    /** 公开列表（游客视角，最多 50 条） */
    private String publicList(String adminToken) throws Exception {
        return getBody("/post/list/page?current=1&pageSize=50", adminToken);
    }

    // 数据库取证

    private long countBy(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private long commentRows(long postId) {
        return countBy("SELECT COUNT(*) FROM studio_post_comment WHERE post_id = ? AND deleted_at = 0", postId);
    }

    private long likeRows(long postId) {
        return countBy("SELECT COUNT(*) FROM studio_post_like WHERE post_id = ?", postId);
    }

    private int pinnedOf(long postId) {
        Integer pinned = studioPostMapper.selectById(postId).getPinned();
        return pinned == null ? 0 : pinned;
    }

    private int viewCountOf(long postId) {
        Integer views = studioPostMapper.selectById(postId).getViewCount();
        return views == null ? 0 : views;
    }

    // 造数

    /** 建一个指定角色的测试用户，返回用户 id */
    private long createUser(String role) {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        return createUser(account, role);
    }

    private long createUser(String account, String role) {
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

    /** 建一个 admin 并登录，返回 token */
    private String adminToken() throws Exception {
        return tokenOf(createAdmin());
    }

    /** 建一个 admin 用户，返回用户 id（需要断言操作人身份的用例用它） */
    private long createAdmin() {
        return createUser(UserRoleConstant.ADMIN);
    }

    /** 直接落一篇指定状态的帖子 */
    private long createPost(long authorId, AuditStatusEnum status) {
        return createPost(authorId, status, "T33-post-" + (System.nanoTime() % 100000));
    }

    private long createPost(long authorId, AuditStatusEnum status, String title) {
        StudioPost post = new StudioPost();
        post.setTitle(title);
        post.setContent("T33-post-content");
        post.setAuthorId(authorId);
        post.setStatus(status.getValue());
        post.setViewCount(0);
        post.setCommentCount(0);
        post.setLikeCount(0);
        post.setPinned(0);
        studioPostMapper.insert(post);
        return post.getId();
    }

    /** 直接改正文（详情 / 校验用例需要，不走编辑接口以免触发送审链路） */
    private void setContent(long postId, String content) {
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getContent, content));
    }

    /** 直接改创建时间（置顶排序用例需要确定性次序） */
    private void setCreatedAt(long postId, LocalDateTime time) {
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getCreatedAt, time));
    }
}
