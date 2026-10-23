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
 * 为什么是普通类 + 构造器注入，而不是 @Component：
 * 测试源码里加 @Component 会被 @SpringBootApplication 的组件扫描
 * 扫到，于是每个测试上下文都会尝试创建它——而它依赖 MockMvc，
 * 后者只在标了 @AutoConfigureMockMvc 的测试里才存在。
 * 结果是所有不带 MockMvc 的测试（如 DDL 校验类）直接启动失败。
 * 因此由测试类自己 new，依赖从它的 @Resource 字段传进来。
 *
 * 为什么抽出来而不是每个测试类各写一份：刷题测试要同时覆盖
 * 「成员」与「非成员」两种身份、反复开关题目状态、读三种 VO 的字段。
 * 这些工具方法如果各写一份，三份之间迟早漂移（比如一处忘了设 member_status），
 * 而漂移的表现是「某个用例莫名 401」。
 *
 * 类名以 Support 结尾，不符合 surefire 的 *Test 命名，不会被当成测试类。
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

    /**
     * @param mockMvc MockMvc（由测试类注入）
     * @param sysUserMapper 账号 Mapper
     * @param studioMemberMapper 成员档案 Mapper
     * @param jdbcTemplate 直查（登录时取账号名）
     * @param jsonMapper 响应解析
     */
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
     * 刻意建两份数据：sys_user 负责登录，
     * studio_member 负责准入判定。只建账号不建档案，会被 Service 判成非成员——
     * 这正是「成员身份认表不认角色」的体现。
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

    // ==================== 请求 ====================

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

    // ==================== 响应解析 ====================

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
