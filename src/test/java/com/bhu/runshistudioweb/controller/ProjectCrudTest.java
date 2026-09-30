package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberProjectMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 项目案例模块验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖五条线：
 * <ol>
 *     <li><b>鉴权边界</b>：C 端列表/详情匿名可用；后台接口 40100 / 40101；</li>
 *     <li><b>队长不变量（本任务核心）</b>：新增即同步进关联表、换队长幂等、旧队长保留、
 *     当前队长不可解绑；</li>
 *     <li><b>关联管理</b>：显式绑定冲突报错、解绑物理删除、重复解绑 40400；</li>
 *     <li><b>级联清理</b>：删项目清项目关联；删成员清「成员-项目 + 成员-证书」两张表；</li>
 *     <li><b>tech_stack 的 JSON 边界</b>：List ↔ JSON 往返、超长拦截、脏数据不炸接口。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private StudioMemberProjectMapper memberProjectMapper;

    /** 用来模拟「手工改库改坏 JSON」的脏数据 */
    @Resource
    private JdbcTemplate jdbcTemplate;

    // ==================== 工具方法 ====================

    private String adminToken() throws Exception {
        String account = "p_adm_" + (System.nanoTime() % 100000);
        SysUser admin = new SysUser();
        admin.setUserAccount(account);
        admin.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        admin.setUserName(account);
        admin.setUserRole(UserRoleConstant.ADMIN);
        admin.setUserStatus(0);
        sysUserMapper.insert(admin);

        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "管理员登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
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

    private int code(String body) {
        int start = body.indexOf("\"code\":") + 7;
        return Integer.parseInt(body.substring(start, body.indexOf(',', start)));
    }

    private String dataValue(String body) {
        int start = body.indexOf("\"data\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private long insertMember(String name) {
        StudioMember member = new StudioMember();
        member.setName(name);
        member.setGradeYear(2022);
        member.setTeamPosition("member");
        member.setMemberStatus(0);
        member.setSortOrder(0);
        studioMemberMapper.insert(member);
        return member.getId();
    }

    private long addProject(String title, long leaderId) throws Exception {
        String body = postJson("/project/add",
                "{\"title\":\"" + title + "\",\"description\":\"desc\",\"leaderId\":" + leaderId + "}",
                token());
        assertEquals(0, code(body), "新增项目失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    private long countRelations(long projectId, long memberId) {
        return memberProjectMapper.selectCount(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId)
                .eq(StudioMemberProject::getMemberId, memberId));
    }

    private long countRelationsOfProject(long projectId) {
        return memberProjectMapper.selectCount(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getProjectId, projectId));
    }

    /** 每个用例内部先取一次管理员 token（用字段缓存，避免每个方法都传参） */
    private String token;

    private String token() throws Exception {
        if (token == null) {
            token = adminToken();
        }
        return token;
    }

    // ==================== 鉴权边界 ====================

    @Test
    @DisplayName("鉴权：C 端匿名可访问；后台接口 40100 / 40101")
    void accessControl() throws Exception {
        assertEquals(0, code(getBody("/project/list", null)), "C 端项目列表应匿名可访问（白名单）");
        long leaderId = insertMember("p-anon-leader");
        long projectId = addProject("p-anon-project", leaderId);
        assertEquals(0, code(getBody("/project/detail?id=" + projectId, null)), "C 端详情应匿名可访问");

        assertEquals(40100, code(getBody("/project/list/page", null)), "后台列表匿名应 40100");
        assertEquals(40100, code(postJson("/project/add", "{}", null)));
        assertEquals(40100, code(postJson("/member-project/bind", "{}", null)));

        String account = "p_nor_" + (System.nanoTime() % 100000);
        SysUser normal = new SysUser();
        normal.setUserAccount(account);
        normal.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        normal.setUserName(account);
        normal.setUserRole(UserRoleConstant.USER);
        normal.setUserStatus(0);
        sysUserMapper.insert(normal);
        String normalToken = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        normalToken = normalToken.substring(normalToken.indexOf("\"token\":\"") + 9);
        normalToken = normalToken.substring(0, normalToken.indexOf('"'));
        assertEquals(40101, code(getBody("/project/list/page", normalToken)), "普通用户应 40101");
    }

    // ==================== 队长不变量 ====================

    @Test
    @DisplayName("队长不变量：新增即同步进关联表；重复提交同一队长幂等")
    void leaderSyncIsAutomaticAndIdempotent() throws Exception {
        long leaderId = insertMember("p-leader-1");
        long projectId = addProject("p-sync-project", leaderId);

        // 新增后关联表里必须已有队长（DESIGN 2.3：队长必然出现在参与成员列表）
        assertEquals(1, countRelations(projectId, leaderId), "队长没有被同步进关联表");
        String members = getBody("/member-project/member/list?projectId=" + projectId, token());
        assertEquals(0, code(members));
        assertTrue(members.contains("p-leader-1"), "参与成员列表里应有队长：" + members);

        // 幂等：把 leaderId 重复提交为同一个值，不应 40000、也不应重复插入
        String update = postJson("/project/update",
                "{\"id\":" + projectId + ",\"leaderId\":" + leaderId + "}", token());
        assertEquals(0, code(update), "重复提交同一队长不应报错（ensure 必须幂等）：" + update);
        assertEquals(1, countRelations(projectId, leaderId), "幂等同步后关联行不应重复");
    }

    @Test
    @DisplayName("换队长：新队长被同步进列表；旧队长保留（卸任≠退出）")
    void changeLeaderKeepsOldLeader() throws Exception {
        long oldLeader = insertMember("p-old-leader");
        long newLeader = insertMember("p-new-leader");
        long projectId = addProject("p-change-project", oldLeader);

        String update = postJson("/project/update",
                "{\"id\":" + projectId + ",\"leaderId\":" + newLeader + "}", token());
        assertEquals(0, code(update), "换队长失败：" + update);
        assertEquals(1, countRelations(projectId, newLeader), "新队长没有被同步进关联表");

        String members = getBody("/member-project/member/list?projectId=" + projectId, token());
        assertTrue(members.contains("p-new-leader"), "新队长应在列表里：" + members);
        assertTrue(members.contains("p-old-leader"), "旧队长不应被自动移除（口径：卸任≠退出）：" + members);
    }

    @Test
    @DisplayName("关联管理：重复绑定 40000；解绑物理删除归零；队长不可解绑；重复解绑 40400")
    void bindUnbindRules() throws Exception {
        long leaderId = insertMember("p-bind-leader");
        long memberId = insertMember("p-bind-member");
        long projectId = addProject("p-bind-project", leaderId);

        // 队长不可解绑（守卫）——先于普通成员校验
        String unbindLeader = postJson("/member-project/unbind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + leaderId + "}", token());
        assertEquals(40000, code(unbindLeader), "队长不应能被解绑：" + unbindLeader);
        assertTrue(unbindLeader.contains("请先更换队长"), "提示应给出下一步动作：" + unbindLeader);

        // 显式绑定普通成员
        assertEquals(0, code(postJson("/member-project/bind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + memberId + "}", token())));
        assertEquals(1, countRelations(projectId, memberId));

        // 重复绑定：冲突必须被看见
        String dup = postJson("/member-project/bind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + memberId + "}", token());
        assertEquals(40000, code(dup), "重复绑定应 40000：" + dup);
        assertTrue(dup.contains("已参与"), dup);

        // 解绑：物理删除（行数归零）
        assertEquals(0, code(postJson("/member-project/unbind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + memberId + "}", token())));
        assertEquals(0, countRelations(projectId, memberId), "解绑应是物理删除（行消失）");

        // 重复解绑 → 40400
        String again = postJson("/member-project/unbind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + memberId + "}", token());
        assertEquals(40400, code(again), "重复解绑应 40400：" + again);
    }

    // ==================== 级联清理 ====================

    @Test
    @DisplayName("级联：删项目清空关联；删成员同时清「项目+证书」两张关联表")
    void cascadeDeletes() throws Exception {
        long leaderId = insertMember("p-cas-leader");
        long memberId = insertMember("p-cas-member");
        long projectId = addProject("p-cas-project", leaderId);
        postJson("/member-project/bind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + memberId + "}", token());
        long otherProject = addProject("p-cas-other", memberId);

        assertEquals(2, countRelationsOfProject(projectId), "前置：队长+成员应共 2 行");

        // 删项目：其关联行物理清空
        assertEquals(0, code(postJson("/project/delete", "{\"id\":" + projectId + "}", token())));
        assertEquals(0, countRelationsOfProject(projectId), "删项目后关联行应清空");
        assertEquals(40400, code(getBody("/project/get?id=" + projectId, token())), "删项目后详情应 40400");

        // 删成员：该成员在其它项目的关联行也要清
        assertEquals(1, countRelations(otherProject, memberId), "前置：另一项目应有该成员");
        assertEquals(0, code(postJson("/member/delete", "{\"id\":" + memberId + "}", token())));
        assertEquals(0, countRelations(otherProject, memberId), "删成员后项目关联应被级联清理");
    }

    // ==================== 校验与脱敏 ====================

    @Test
    @DisplayName("状态枚举：非法值 40000；筛选生效；C 端脱敏、详情含正文")
    void statusValidationAndFrontView() throws Exception {
        long leaderId = insertMember("p-view-leader");
        long onlineId = addProject("p-view-online", leaderId);
        String badStatus = postJson("/project/update", "{\"id\":" + onlineId + ",\"status\":9}", token());
        assertEquals(40000, code(badStatus), "非法状态应 40000：" + badStatus);
        assertEquals(40000, code(getBody("/project/list?status=9", null)), "C 端非法状态也应 40000");

        long draftId = addProject("p-view-draft", leaderId);
        postJson("/project/update", "{\"id\":" + draftId + ",\"status\":0,\"content\":\"## 正文内容\"}", token());

        // C 端列表脱敏：无 content / leaderId / sortOrder
        String front = getBody("/project/list?title=p-view-", null);
        assertEquals(0, code(front));
        assertFalse(front.contains("\"content\""), "C 端列表不应返回 content：" + front);
        assertFalse(front.contains("\"leaderId\""), "C 端列表不应返回 leaderId：" + front);
        assertFalse(front.contains("\"sortOrder\""), "C 端列表不应返回 sortOrder：" + front);

        // 详情含正文
        String detail = getBody("/project/detail?id=" + draftId, null);
        assertEquals(0, code(detail));
        assertTrue(detail.contains("\"content\":\"## 正文内容\""), "详情应返回 content：" + detail);
    }

    @Test
    @DisplayName("tech_stack：List ↔ JSON 往返")
    void techStackRoundTrip() throws Exception {
        long leaderId = insertMember("p-tech-leader");
        long projectId = addProject("p-tech-project", leaderId);
        String update = postJson("/project/update",
                "{\"id\":" + projectId + ",\"techStack\":[\"Java\",\"Spring Boot\"]}", token());
        assertEquals(0, code(update), "techStack 更新失败：" + update);

        // 库里存的是 JSON 字符串
        String stored = jdbcTemplate.queryForObject(
                "SELECT tech_stack FROM studio_project WHERE id = ?", String.class, projectId);
        assertEquals("[\"Java\",\"Spring Boot\"]", stored, "库里应是 JSON 快照");

        // 接口读回是 List
        String detail = getBody("/project/detail?id=" + projectId, null);
        assertTrue(detail.contains("\"techStack\":[\"Java\",\"Spring Boot\"]"), "应读回 List：" + detail);
    }

    @Test
    @DisplayName("tech_stack：脏数据（手工改库写坏 JSON）不炸接口，兜底为空数组")
    void techStackDirtyDataDoesNotBreak() throws Exception {
        long leaderId = insertMember("p-dirty-leader");
        long projectId = addProject("p-dirty-project", leaderId);

        // 关键：本用例必须「先改库、后首次读」，不能与往返用例合并——
        // MyBatis 一级缓存作用在同一事务的 SqlSession 上，JdbcTemplate 直改的库对 MyBatis
        // 不可见：先读过的实体再读会拿到缓存旧值，把「脏数据容错」测成假失败。
        // 这是测试基础设施的坑（不是实现的），拆开写才是有效的验证
        jdbcTemplate.update("UPDATE studio_project SET tech_stack = 'not-a-json' WHERE id = ?", projectId);

        String dirty = getBody("/project/detail?id=" + projectId, null);
        assertEquals(0, code(dirty), "脏数据不应让接口 500：" + dirty);
        assertTrue(dirty.contains("\"techStack\":[]"), "解析失败应兜底为空数组：" + dirty);
    }

    @Test
    @DisplayName("tech_stack 序列化超长（>256）：拒绝 40000")
    void techStackTooLongRejected() throws Exception {
        long leaderId = insertMember("p-long-leader");
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                tags.append(',');
            }
            tags.append('"').append("very-long-technology-tag-").append(i).append('"');
        }
        String body = postJson("/project/add",
                "{\"title\":\"p-long-project\",\"description\":\"d\",\"leaderId\":" + leaderId
                        + ",\"techStack\":[" + tags + "]}", token());
        assertEquals(40000, code(body), "超长 techStack 应 40000：" + body);
        assertTrue(body.contains("技术栈"), body);
    }
}