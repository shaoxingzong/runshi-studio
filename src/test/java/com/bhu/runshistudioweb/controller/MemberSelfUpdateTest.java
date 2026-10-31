package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 成员自助编辑本人档案：权限边界与字段白名单验收
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberSelfUpdateTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    /** 插库造账号，返回其 id */
    private long insertUser(String account) {
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole("user");
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 给账号绑定一份在队档案，返回成员 id */
    private long bindMember(long userId, String name) {
        StudioMember member = new StudioMember();
        member.setUserId(userId);
        member.setName(name);
        member.setGradeYear(2022);
        member.setTeamPosition("member");
        member.setMemberStatus(0);
        member.setSortOrder(0);
        studioMemberMapper.insert(member);
        return member.getId();
    }

    /** 走真实登录接口换 token */
    private String login(String account) throws Exception {
        return data(postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null), "token");
    }

    private String postJson(String path, String json, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
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

    /** 从响应 JSON 中取出 "key":"value" 形式的字符串值 */
    private String data(String body, String key) {
        int start = body.indexOf("\"" + key + "\":\"");
        if (start < 0) {
            return null;
        }
        int from = start + key.length() + 4;
        return body.substring(from, body.indexOf('"', from));
    }

    @Test
    @DisplayName("成员可更新自己的展示字段，且登录账号不受影响")
    void memberCanUpdateOwnProfile() throws Exception {
        String account = "selfUpd" + (System.nanoTime() % 100000);
        long userId = insertUser(account);
        long memberId = bindMember(userId, "原名");
        String token = login(account);

        String body = postJson("/member/mine/update", """
                {"name":"新姓名","major":"软件工程","direction":"AI应用",
                 "githubUrl":"https://github.com/new","summary":"新的个人简介"}""", token);
        assertEquals("00000", code(body), "成员更新本人档案失败：" + body);

        StudioMember updated = studioMemberMapper.selectById(memberId);
        assertNotNull(updated, "更新后查不到成员档案");
        assertEquals("新姓名", updated.getName());
        assertEquals("软件工程", updated.getMajor());
        assertEquals("AI应用", updated.getDirection());
        assertEquals("https://github.com/new", updated.getGithubUrl());
        assertEquals("新的个人简介", updated.getSummary());

        // 账号只读：整个链路没有任何修改 user_account 的入口
        assertEquals(account, sysUserMapper.selectById(userId).getUserAccount(), "登录账号不应被改动");
    }

    @Test
    @DisplayName("已登录但不是成员：拒绝编辑（A0301），且不会创建档案")
    void nonMemberIsRejected() throws Exception {
        String account = "selfNo" + (System.nanoTime() % 100000);
        long userId = insertUser(account);
        String token = login(account);

        String body = postJson("/member/mine/update", "{\"name\":\"新姓名\"}", token);
        assertEquals("A0301", code(body), "非成员不应能编辑成员档案：" + body);
        assertEquals(0L, studioMemberMapper.selectCount(
                new LambdaQueryWrapper<StudioMember>().eq(StudioMember::getUserId, userId)),
                "拒绝后不应创建成员档案");
    }

    @Test
    @DisplayName("白名单外字段（账号绑定/届别/职务/状态/排序）即使提交也不生效")
    void adminOnlyFieldsIgnored() throws Exception {
        String account = "selfPriv" + (System.nanoTime() % 100000);
        long userId = insertUser(account);
        long memberId = bindMember(userId, "原名");

        // 准备另一个账号，作为「试图改绑」的目标
        long otherUserId = insertUser("selfOther" + (System.nanoTime() % 100000));
        String token = login(account);

        String body = postJson("/member/mine/update", """
                {"name":"改名","userId":"%d","gradeYear":2030,"teamPosition":"leader",
                 "memberStatus":1,"sortOrder":99}""".formatted(otherUserId), token);
        assertEquals("00000", code(body), "请求应被接受但只应用白名单字段：" + body);

        StudioMember after = studioMemberMapper.selectById(memberId);
        assertEquals("改名", after.getName(), "白名单内的姓名字段应生效");
        assertEquals(userId, after.getUserId(), "成员不能把档案改绑到其他账号");
        assertEquals(2022, after.getGradeYear(), "届别仅管理员可改");
        assertEquals("member", after.getTeamPosition(), "职务仅管理员可改");
        assertEquals(0, after.getMemberStatus(), "成员状态仅管理员可改");
        assertEquals(0, after.getSortOrder(), "置顶排序仅管理员可改");
    }
}
