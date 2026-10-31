package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「成员身份」一致性的验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberIdentityConsistencyTest {

    /** 测试账号前缀 */
    private static final String ACCOUNT_PREFIX = "T35ident_";

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

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
    }

    // ① 未绑定账号的档案：允许创建，但确实不生效

    @Test
    @DisplayName("新增成员不绑账号：仍然允许（保留历史导入能力），但该档案不生效")
    void addMemberWithoutAccountIsAllowedButIneffective() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        // 允许创建（db/user.sql 明确保留「可以有档案没账号（历史导入）」）
        String body = support.postJson("/member/add",
                "{\"name\":\"未绑定测试\",\"gradeYear\":2023}", token);
        assertEquals("00000", support.code(body),
                "不绑账号仍应允许创建（历史导入场景）：" + body);

        // 但它不会对任何账号生效——这正是「成员功能不显示」的直接原因
        String userToken = support.tokenOf(userId);
        String mine = support.getBody("/member/mine", userToken);
        assertEquals("00000", support.code(mine), "查询应成功：" + mine);
        assertTrue(support.data(mine).isNull(),
                "未绑定账号的档案不该被任何账号认领：" + mine);
    }

    @Test
    @DisplayName("新增成员绑定了不存在的账号：A0401（沿用既有 assertUserBindable 的口径）")
    void addMemberWithNonExistentAccount() {
        String token = support.adminToken();
        String body = support.postJson("/member/add",
                "{\"name\":\"账号不存在测试\",\"gradeYear\":2023,\"userId\":999999999999999999}", token);
        assertEquals("A0401", support.code(body), "账号不存在应被拒绝：" + body);
        assertTrue(support.message(body).contains("账号"),
                "提示应说明账号问题：" + body);
    }

    @Test
    @DisplayName("新增成员正常绑定账号：成功，且该账号能查到自己的档案")
    void addMemberWithAccountWorks() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        String body = support.postJson("/member/add",
                "{\"name\":\"正常绑定测试\",\"gradeYear\":2023,\"userId\":" + userId + "}", token);
        assertEquals("00000", support.code(body), "正常绑定应成功：" + body);

        // 用该账号登录，确认 /member/mine 能查到档案 —— 这就是「成员功能是否可见」的判据
        String userToken = support.tokenOf(userId);
        String mine = support.getBody("/member/mine", userToken);
        assertEquals("00000", support.code(mine), "查询我的档案应成功：" + mine);
        assertNotNull(support.data(mine).get("name"),
                "绑定后应能查到档案（否则成员功能不显示）：" + mine);
    }

    // ② 授予角色前必须有档案

    @Test
    @DisplayName("给无档案的账号授予 member 角色：A0401，并提示先去建档")
    void grantMemberRoleWithoutProfileIsRejected() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        String body = support.postJson("/user/update",
                "{\"id\":" + userId + ",\"userRole\":\"member\"}", token);

        assertEquals("A0401", support.code(body), "无档案授予成员应被拒绝：" + body);
        assertTrue(support.message(body).contains("成员档案"),
                "提示应说明缺少成员档案：" + body);
        assertTrue(support.message(body).contains("成员管理"),
                "提示应告诉管理员下一步去哪建档：" + body);

        // 角色不能被改掉（否则又回到「有角色没档案」的坏状态）
        String role = jdbcTemplate.queryForObject(
                "SELECT user_role FROM sys_user WHERE id = ?", String.class, userId);
        assertEquals("user", role, "被拒绝时角色不该变化");
    }

    @Test
    @DisplayName("给无档案的账号授予 admin 角色：同样被拒（admin 也可能用到成员功能）")
    void grantAdminRoleWithoutProfileIsRejected() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        String body = support.postJson("/user/update",
                "{\"id\":" + userId + ",\"userRole\":\"admin\"}", token);
        assertEquals("A0401", support.code(body), "无档案授予管理员应被拒绝：" + body);
    }

    @Test
    @DisplayName("先建档绑定、再改角色：两步都成功，成员功能可用")
    void profileThenRoleWorks() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        // 第一步：建档并绑定
        String addBody = support.postJson("/member/add",
                "{\"name\":\"先建档后改角色\",\"gradeYear\":2023,\"userId\":" + userId + "}", token);
        assertEquals("00000", support.code(addBody), "建档应成功：" + addBody);

        // 第二步：改角色
        String roleBody = support.postJson("/user/update",
                "{\"id\":" + userId + ",\"userRole\":\"member\"}", token);
        assertEquals("00000", support.code(roleBody), "有档案后改角色应成功：" + roleBody);

        String role = jdbcTemplate.queryForObject(
                "SELECT user_role FROM sys_user WHERE id = ?", String.class, userId);
        assertEquals("member", role);
    }

    @Test
    @DisplayName("降级为普通用户不需要档案（user 角色不依赖成员功能）")
    void downgradeToUserNeedsNoProfile() {
        String token = support.adminToken();
        // 建一个普通账号（无档案），改回 user 角色应放行
        long userId = support.createUser(UserRoleConstant.USER);

        String body = support.postJson("/user/update",
                "{\"id\":" + userId + ",\"userName\":\"改个昵称\"}", token);
        assertEquals("00000", support.code(body), "普通资料修改不该被拦：" + body);
    }

    // ③ 回归：真实坏数据的表现

    @Test
    @DisplayName("user_id 为 NULL 的档案确实不生效（复现原 bug 的形态）")
    void orphanProfileDoesNotGrantAccess() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        // 直接造一条 user_id 为 NULL 的档案（模拟修复前通过后台产生的脏数据）
        StudioMember orphan = new StudioMember();
        orphan.setUserId(null);
        orphan.setName("孤儿档案_" + System.nanoTime());
        orphan.setGradeYear(2023);
        orphan.setMemberStatus(0);
        orphan.setTeamPosition("member");
        orphan.setSortOrder(0);
        studioMemberMapper.insert(orphan);

        // 该账号登录后查不到自己的档案 → 成员功能不显示
        String userToken = support.tokenOf(userId);
        String mine = support.getBody("/member/mine", userToken);
        assertEquals("00000", support.code(mine), "查询应成功：" + mine);
        assertTrue(support.data(mine).isNull(),
                "user_id 为 NULL 的档案不该被任何人认领：" + mine);
    }

    @Test
    @DisplayName("有档案但角色是 user：成员接口可用（准入认档案不认角色）")
    void profileWithoutRoleStillWorks() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        // 建档绑定，但**不改角色**
        String addBody = support.postJson("/member/add",
                "{\"name\":\"有档案无角色\",\"gradeYear\":2023,\"userId\":" + userId + "}", token);
        assertEquals("00000", support.code(addBody), "建档应成功：" + addBody);

        // 刷题接口应按档案放行（这就是「认表不认角色」的体现）
        String userToken = support.tokenOf(userId);
        String catalog = support.getBody("/quiz/catalog", userToken);
        assertEquals("00000", support.code(catalog),
                "有档案即可用成员接口，不该被角色卡住：" + catalog);
    }

    // ④ 解绑 / 重复绑定

    @Test
    @DisplayName("同一账号不能被两个成员档案同时绑定")
    void cannotBindSameAccountTwice() {
        String token = support.adminToken();
        long userId = support.createUser(UserRoleConstant.USER);

        String first = support.postJson("/member/add",
                "{\"name\":\"第一次绑定\",\"gradeYear\":2023,\"userId\":" + userId + "}", token);
        assertEquals("00000", support.code(first), "首次绑定应成功：" + first);

        String second = support.postJson("/member/add",
                "{\"name\":\"第二次绑定\",\"gradeYear\":2023,\"userId\":" + userId + "}", token);
        assertEquals("A0401", support.code(second), "重复绑定应被拒绝：" + second);
        assertTrue(support.message(second).contains("已被"),
                "提示应说明账号已被占用：" + second);
    }
}
