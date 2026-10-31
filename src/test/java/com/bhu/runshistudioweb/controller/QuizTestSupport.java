package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 刷题测试的共用夹具（建用户 / 建成员档案 / 登录 / 发请求 / 解析响应）
 *
 * @author shaoshing
 */
public class QuizTestSupport {

    /** 测试账号前缀（便于在数据里认出刷题测试产生的用户） */
    public static final String ACCOUNT_PREFIX = "T34quiz_";

    /** 测试用户统一口令 */
    public static final String PASSWORD = "Studio@2026";

    private final MockMvc mockMvc;

    private final SysUserMapper sysUserMapper;

    private final StudioMemberMapper studioMemberMapper;

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper;

    public QuizTestSupport(MockMvc mockMvc, SysUserMapper sysUserMapper,
                           StudioMemberMapper studioMemberMapper,
                           JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.sysUserMapper = sysUserMapper;
        this.studioMemberMapper = studioMemberMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 建一个在队成员（账号 + 成员档案），返回账号 ID
     *
     * @param role 账号角色（user / member / admin），不影响刷题准入
     * @return 账号 ID
     */
    public long createMember(String role) {
        long userId = createUser(role);
        StudioMember member = new StudioMember();
        member.setUserId(userId);
        member.setName(ACCOUNT_PREFIX + userId);
        member.setGradeYear(2023);
        member.setMemberStatus(MemberStatusEnum.IN_TEAM.getValue());
        studioMemberMapper.insert(member);
        return userId;
    }

    /**
     * 只建账号、不建成员档案（用于「非成员」用例）
     *
     * @param role 账号角色
     * @return 账号 ID
     */
    public long createUser(String role) {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(PASSWORD));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 建一个管理员并返回其 token（题库管理的绝大多数用例都需要） */
    public String adminToken() {
        return tokenOf(createMember(UserRoleConstant.ADMIN));
    }

    /**
     * 登录并取 token
     *
     * @param userId 账号 ID
     * @return satoken 的值
     */
    public String tokenOf(long userId) {
        String account = jdbcTemplate.queryForObject(
                "SELECT user_account FROM sys_user WHERE id = ?", String.class, userId);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + PASSWORD + "\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }

    // 请求

    /** 发 POST（JSON 体），返回响应体 */
    public String postJson(String path, String json, String token) {
        try {
            var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
            if (token != null) {
                request.header("satoken", token);
            }
            return mockMvc.perform(request).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("POST " + path + " 失败", e);
        }
    }

    /** 发 GET，返回响应体 */
    public String getBody(String path, String token) {
        try {
            var request = get(path);
            if (token != null) {
                request.header("satoken", token);
            }
            return mockMvc.perform(request).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("GET " + path + " 失败", e);
        }
    }

    // 响应解析

    /** 取业务码 */
    public String code(String body) {
        return tree(body).get("code").asText();
    }

    /** 取提示信息 */
    public String message(String body) {
        return tree(body).get("message").asText();
    }

    /** 取 data 节点（响应结构异常时抛出可读异常） */
    public JsonNode data(String body) {
        JsonNode node = tree(body).get("data");
        assertTrue(node != null, "响应里没有 data 节点：" + body);
        return node;
    }

    /** 解析 JSON（失败时把原文带进异常，便于定位） */
    public JsonNode tree(String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + body, e);
        }
    }
}
