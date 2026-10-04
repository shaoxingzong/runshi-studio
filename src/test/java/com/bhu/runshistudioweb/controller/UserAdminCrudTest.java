package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 管理端用户增删改查验收测试
 *
 * author: shaoshing
 *
 * <p>这组用例守两条线：
 * <ol>
 *     <li><b>越权</b>：普通用户、未登录用户访问管理端接口必须分别拿到 A0301 / A0201
 *     ——这是「权限」功能唯一真正重要的断言；</li>
 *     <li><b>可用性</b>：管理员走完 增 → 查 → 改 → 删 全流程，且每一步的数据落库结果正确
 *     （密码必须是 BCrypt 哈希、昵称要有兜底值、删除必须是逻辑删除）。</li>
 * </ol>
 *
 * <p>测试直接插库造管理员账号（不走注册接口，因为注册只会创建普通用户），
 * 再用真实登录接口换 token，这样权限链路上的每一环都是真的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAdminCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    /** 绕过 ORM 直查数据库，用来验证「逻辑删除」的真实落库值 */
    @Resource
    private JdbcTemplate jdbcTemplate;

    // ==================== 工具方法 ====================

    /** 插库造账号，返回其 id */
    private long insertUser(String account, String role) {
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 走真实登录接口换 token */
    private String login(String account) throws Exception {
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        return data(body, "token");
    }

    private String postJson(String path, String json, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String getBody(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /** 从响应 JSON 中取出 data.code */
    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    /** 从响应 JSON 中取出 data.xxx 的字符串值 */
    private String data(String body, String key) {
        int start = body.indexOf("\"" + key + "\":\"");
        if (start < 0) {
            return null;
        }
        int from = start + key.length() + 4;
        return body.substring(from, body.indexOf('"', from));
    }

    /** 从响应 JSON 中取出 data 本身的字符串值（新增接口返回的是 data:"雪花ID"） */
    private String dataValue(String body) {
        return data(body, "data");
    }

    // ==================== 鉴权边界 ====================

    @Test
    @DisplayName("鉴权：未登录访问管理端接口返回 A0201")
    void anonymousIsRejected() throws Exception {
        assertEquals("A0201", code(getBody("/user/list/page", null)));
        assertEquals("A0201", code(postJson("/user/delete", "{\"id\":1}", null)));
    }

    @Test
    @DisplayName("鉴权：普通用户访问管理端接口返回 A0301（有登录态但角色不够）")
    void normalUserIsForbidden() throws Exception {
        String account = "normal" + (System.nanoTime() % 100000);
        insertUser(account, UserRoleConstant.USER);
        String token = login(account);

        assertEquals("A0301", code(getBody("/user/list/page", token)), "普通用户不应能查看用户列表");
        assertEquals("A0301", code(getBody("/user/get?id=1", token)), "普通用户不应能查看用户详情");
    }

    // ==================== 增删改查全流程 ====================

    @Test
    @DisplayName("全流程：管理员 新增 → 详情 → 更新 → 分页查询 → 删除")
    void adminCrudFlow() throws Exception {
        String adminAccount = "admin" + (System.nanoTime() % 100000);
        long adminId = insertUser(adminAccount, UserRoleConstant.ADMIN);
        String token = login(adminAccount);

        // ---------- 新增：不填密码与昵称，走默认值 ----------
        String newAccount = "crud" + (System.nanoTime() % 100000);
        String addBody = postJson("/user/add",
                "{\"userAccount\":\"" + newAccount + "\",\"userRole\":\"" + UserRoleConstant.ADMIN + "\"}", token);
        assertEquals("00000", code(addBody), "新增失败：" + addBody);
        long newId = Long.parseLong(dataValue(addBody));

        SysUser created = sysUserMapper.selectById(newId);
        assertTrue(created.getUserPassword().startsWith("$2a$"), "密码必须是 BCrypt 哈希，不能存明文");
        assertTrue(PasswordUtils.matches("Runshi@123", created.getUserPassword()), "未填密码时应使用默认初始密码");
        assertEquals(newAccount, created.getUserName(), "昵称不填时应以账号兜底（user_name 是 NOT NULL）");
        assertEquals(UserRoleConstant.ADMIN, created.getUserRole());
        assertEquals(0, created.getUserStatus(), "状态不填时应为 0-正常");

        // ---------- 详情：返回 VO，且绝不能带出密码字段 ----------
        String getBody = getBody("/user/get?id=" + newId, token);
        assertEquals("00000", code(getBody));
        assertEquals(newAccount, data(getBody, "userAccount"));
        assertFalse(getBody.contains("userPassword"), "详情的响应体里出现了密码字段：" + getBody);

        // ---------- 更新：改昵称 + 重置密码（部分更新，未传的字段不动） ----------
        String updateBody = postJson("/user/update",
                "{\"id\":" + newId + ",\"userName\":\"新昵称\",\"userPassword\":\"NewPass@2026\"}", token);
        assertEquals("00000", code(updateBody));
        SysUser updated = sysUserMapper.selectById(newId);
        assertEquals("新昵称", updated.getUserName());
        assertTrue(PasswordUtils.matches("NewPass@2026", updated.getUserPassword()), "密码没有被重置");
        assertEquals(UserRoleConstant.ADMIN, updated.getUserRole(), "未传 userRole 时不应改动角色");
        assertEquals(0, updated.getUserStatus(), "未传 userStatus 时不应改动状态");

        // ---------- 分页查询：条件生效且返回 VO ----------
        String listBody = getBody("/user/list/page?current=1&pageSize=10&userAccount=" + newAccount, token);
        assertEquals("00000", code(listBody));
        assertTrue(listBody.contains("\"total\":"), "分页结果必须带 total：" + listBody);
        assertTrue(listBody.contains(newAccount), "按账号模糊查询没查到刚创建的用户：" + listBody);
        assertFalse(listBody.contains("userPassword"), "列表里出现了密码字段");

        // ---------- 删除：逻辑删除，删完查不到 ----------
        String deleteBody = postJson("/user/delete", "{\"id\":" + newId + "}", token);
        assertEquals("00000", code(deleteBody));
        assertEquals("A0402", code(getBody("/user/get?id=" + newId, token)), "删除后应查不到该用户");

        // 必须是「逻辑删除」而不是物理删除：行还在、deleted_at 是 13 位毫秒时间戳。
        // 这一条决定了数据可追溯，也决定了账号名能被重新注册
        Long deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM sys_user WHERE id = ?", Long.class, newId);
        assertTrue(deletedAt != null && deletedAt != 0 && String.valueOf(deletedAt).length() == 13,
                "删除后 deleted_at 应为 13 位毫秒时间戳，实际：" + deletedAt);

        // 管理员自己没被误删
        assertEquals("00000", code(getBody("/user/get?id=" + adminId, token)));
    }

    // ==================== 防锁死守卫 ====================

    @Test
    @DisplayName("守卫：管理员不能删除自己、不能封禁自己")
    void adminCannotLockHimselfOut() throws Exception {
        String adminAccount = "guard" + (System.nanoTime() % 100000);
        long adminId = insertUser(adminAccount, UserRoleConstant.ADMIN);
        String token = login(adminAccount);

        assertEquals("A0401", code(postJson("/user/delete", "{\"id\":" + adminId + "}", token)),
                "删除自己应被拒绝，否则会立刻失去管理端权限");
        assertEquals("A0401", code(postJson("/user/update",
                "{\"id\":" + adminId + ",\"userStatus\":1}", token)), "封禁自己应被拒绝");
        assertEquals("A0401", code(postJson("/user/update",
                "{\"id\":" + adminId + ",\"userRole\":\"" + UserRoleConstant.USER + "\"}", token)),
                "把自己降级应被拒绝");

        // 守卫只拦「自己」，改自己其它字段仍然允许
        assertEquals("00000", code(postJson("/user/update",
                "{\"id\":" + adminId + ",\"userName\":\"管理员本人\"}", token)));
    }

    @Test
    @DisplayName("参数校验：非法角色、已存在账号、超长昵称都应被拒绝")
    void invalidInputIsRejected() throws Exception {
        String adminAccount = "valid" + (System.nanoTime() % 100000);
        insertUser(adminAccount, UserRoleConstant.ADMIN);
        String token = login(adminAccount);

        // 角色不在枚举内：一旦入库，权限判定会静默失效
        assertEquals("A0401", code(postJson("/user/add",
                "{\"userAccount\":\"role_bad\",\"userRole\":\"superman\"}", token)));

        // 账号已存在
        assertEquals("A0401", code(postJson("/user/add",
                "{\"userAccount\":\"" + adminAccount + "\"}", token)));

        // 昵称超长（DTO 上的 @Size 生效 → A0401 而不是 B0001）
        assertEquals("A0401", code(postJson("/user/add",
                "{\"userAccount\":\"nick_bad\",\"userName\":\"" + "长".repeat(65) + "\"}", token)));
    }
}
