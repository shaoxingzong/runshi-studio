package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作室内部事项看板契约测试（走真实接口全流程）
 *
 * <p>覆盖：仅成员可见、三列结构、负责人权限边界、乐观锁、完成时间、逾期判定、下线。
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardItemControllerTest {

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
    private String memberAToken;
    private String memberBToken;
    private long memberArchiveA;
    private long memberArchiveB;

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
        long userA = support.createMember(UserRoleConstant.MEMBER);
        long userB = support.createMember(UserRoleConstant.MEMBER);
        memberArchiveA = memberArchiveIdOf(userA);
        memberArchiveB = memberArchiveIdOf(userB);
        adminToken = support.adminToken();
        memberAToken = support.tokenOf(userA);
        memberBToken = support.tokenOf(userB);
    }

    @Test
    @DisplayName("游客与普通注册用户看不到内部事项看板")
    void guestAndNonMemberAreRejected() {
        String guest = support.getBody("/board/item/board", null);
        assertEquals("A0201", support.code(guest), "未登录应被拦在 @SaCheckLogin：" + guest);

        long outsider = support.createUser(UserRoleConstant.USER);
        String body = support.getBody("/board/item/board", support.tokenOf(outsider));
        assertEquals("A0301", support.code(body), "普通用户不是在队成员：" + body);
    }

    @Test
    @DisplayName("成员看到三列结构与统计字段")
    void memberSeesThreeColumns() {
        JsonNode board = board(memberAToken);
        assertTrue(board.has("todo") && board.has("doing") && board.has("done"),
                "一次请求要返回三列：" + board);
        assertTrue(board.has("pendingCount") && board.has("overdueCount")
                && board.has("myPendingCount") && board.has("doneCount"),
                "顶部统计字段齐全：" + board);
    }

    @Test
    @DisplayName("管理员发布并指派：负责人可推进，其他成员只读")
    void assigneeCanMoveOwnItemButOthersCannot() {
        String id = addItem(memberArchiveA, null);

        JsonNode boardA = board(memberAToken);
        assertEquals(1, boardA.get("todo").size(), "新事项出现在待办列：" + boardA);
        JsonNode itemA = boardA.get("todo").get(0);
        assertEquals(id, itemA.get("id").asText());
        assertNotNull(itemA.get("assigneeName"), "负责人姓名应由服务端批量反查填充");
        assertTrue(itemA.get("canChangeStatus").asBoolean(), "被指派的人可以推进自己那条");

        JsonNode boardB = board(memberBToken);
        assertFalse(boardB.get("todo").get(0).get("canChangeStatus").asBoolean(),
                "别人那条只能看，不能改：" + boardB);

        String denied = changeStatus(memberBToken, id, 1, 0);
        assertEquals("A0301", support.code(denied), "非负责人改状态必须被拒：" + denied);

        String ok = changeStatus(memberAToken, id, 1, 0);
        assertEquals("00000", support.code(ok), "负责人可以推进：" + ok);
        assertEquals(1, board(memberAToken).get("doing").size(), "推进后进入进行中列");
    }

    @Test
    @DisplayName("管理员可流转任意事项，且重复目标状态是幂等的")
    void adminCanMoveAnyItem() {
        String id = addItem(memberArchiveB, null);
        String ok = changeStatus(adminToken, id, 2, 0);
        assertEquals("00000", support.code(ok), "管理员不受负责人限制：" + ok);

        String same = changeStatus(adminToken, id, 2, 2);
        assertEquals("00000", support.code(same), "目标与当前相同应直接返回，不报错：" + same);
    }

    @Test
    @DisplayName("乐观锁：带着过期状态提交会被拒绝，避免两人互相覆盖")
    void optimisticLockRejectsStaleStatus() {
        String id = addItem(memberArchiveA, null);
        assertEquals("00000", support.code(changeStatus(adminToken, id, 2, 0)));

        String conflict = changeStatus(adminToken, id, 1, 0);
        assertNotEquals("00000", support.code(conflict),
                "expectedStatus=0 与库中 2 不符，必须拒绝而不是静默覆盖：" + conflict);
    }

    @Test
    @DisplayName("完成时写 completed_at，回退时清空")
    void completeAndReopenMaintainCompletedAt() {
        String id = addItem(memberArchiveA, null);
        assertEquals("00000", support.code(changeStatus(adminToken, id, 2, 0)));
        assertNotNull(completedAtOf(id), "进入已完成要有完成时间");

        assertEquals("00000", support.code(changeStatus(adminToken, id, 1, 2)));
        assertNull(completedAtOf(id), "回退到进行中后不应保留完成时间");
    }

    @Test
    @DisplayName("过期事项标逾期，管理端可按「只看逾期」筛出未交的人")
    void overdueFlagAndFilter() {
        String id = addItem(memberArchiveA, "2020-01-01 10:00:00");
        JsonNode board = board(memberAToken);
        assertTrue(board.get("todo").get(0).get("overdue").asBoolean(), "过期未完成应标逾期：" + board);
        assertEquals(1, board.get("overdueCount").asInt(), "逾期统计要跟着涨");

        String page = support.getBody("/board/item/page?onlyOverdue=true&pageSize=10", adminToken);
        assertEquals("00000", support.code(page), page);
        JsonNode records = support.data(page).get("records");
        assertTrue(records.size() >= 1, "只看逾期应能筛到这条：" + page);
        boolean found = false;
        for (JsonNode record : records) {
            if (id.equals(record.get("id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "筛出的记录要包含刚建的事项");
    }

    @Test
    @DisplayName("管理员下线事项后成员看板不再展示（逻辑删除）")
    void deleteRemovesFromBoard() {
        String id = addItem(memberArchiveA, null);
        String deleted = support.postJson("/board/item/delete",
                "{\"id\":\"" + id + "\"}", adminToken);
        assertEquals("00000", support.code(deleted), deleted);

        JsonNode board = board(memberAToken);
        assertEquals(0, board.get("todo").size(), "下线后不该再出现在看板：" + board);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_board_item WHERE id = ? AND deleted_at = 0",
                Integer.class, Long.valueOf(id)).intValue(), "逻辑删除，物理行仍在");
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private long memberArchiveIdOf(long userId) {
        StudioMember member = studioMemberMapper.selectOne(new LambdaQueryWrapper<StudioMember>()
                .eq(StudioMember::getUserId, userId));
        assertNotNull(member, "夹具应建好成员档案");
        return member.getId();
    }

    /** 管理员发布一条事项；不传 requestId，走幂等管理器的「兼容旧客户端」放行分支 */
    private String addItem(long assigneeMemberId, String deadline) {
        String json = "{\"title\":\"事项-" + System.nanoTime() + "\","
                + "\"description\":\"**说明**\","
                + "\"assigneeMemberId\":\"" + assigneeMemberId + "\","
                + (deadline == null ? "" : "\"deadline\":\"" + deadline + "\",")
                + "\"status\":0}";
        String body = support.postJson("/board/item/add", json, adminToken);
        assertEquals("00000", support.code(body), body);
        return support.data(body).asText();
    }

    private JsonNode board(String token) {
        String body = support.getBody("/board/item/board", token);
        assertEquals("00000", support.code(body), body);
        return support.data(body);
    }

    private String changeStatus(String token, String id, int targetStatus, int expectedStatus) {
        String json = "{\"id\":\"" + id + "\",\"targetStatus\":" + targetStatus
                + ",\"expectedStatus\":" + expectedStatus + "}";
        return support.postJson("/board/item/status", json, token);
    }

    private Object completedAtOf(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT completed_at FROM studio_board_item WHERE id = ?",
                Object.class, Long.valueOf(id));
    }
}
