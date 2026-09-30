package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberCertificateMapper;
import com.bhu.runshistudioweb.mapper.StudioMemberProjectMapper;
import com.bhu.runshistudioweb.mapper.StudioProjectMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.entity.StudioMemberProject;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * C 端详情聚合模块验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖五条线：
 * <ol>
 *     <li><b>白名单精确性</b>：{@code /member/detail} 匿名放行；
 *     {@code /member/get}、{@code /member/add} 等管理端接口仍 40100（只差一个后缀，不能被顺带放行）；</li>
 *     <li><b>聚合结构与关联对齐</b>：档案 + 证书 + 项目三段完整，
 *     且只返回与当前成员有关联的数据，不夹带其他成员的证书、未关联的项目；</li>
 *     <li><b>脱敏与空数据</b>：三段均无 userId / leaderId / sortOrder / content / 审计字段；
 *     空段返回空数组而不是 null；</li>
 *     <li><b>项目详情的 members 段（本任务第二个改动点）</b>：队长必含（leader_id 同步不变量）、
 *     排序与成员模块一致、详情正文不受影响；</li>
 *     <li><b>悬空防御</b>：主表已逻辑删除、但关联行残留的证书/项目，不得出现在聚合结果里。</li>
 * </ol>
 *
 * <p>测试数据一律通过管理端接口创建（顺带回归主表接口）；关联行与悬空数据用 Mapper 直查/直改，
 * 避免「用被测接口自己验证自己」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberDetailFrontTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    /** 悬空防御用例：直接对主表做逻辑删除（不经过 Service，因而不会触发级联清理） */
    @Resource
    private StudioCertificateMapper studioCertificateMapper;

    @Resource
    private StudioProjectMapper studioProjectMapper;

    @Resource
    private StudioMemberCertificateMapper memberCertificateMapper;

    @Resource
    private StudioMemberProjectMapper memberProjectMapper;

    /** 每个用例内部先取一次管理员 token（用字段缓存，避免每个方法都传参） */
    private String token;

    private String token() throws Exception {
        if (token == null) {
            String account = "md_adm_" + (System.nanoTime() % 100000);
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
            token = body.substring(start, body.indexOf('"', start));
        }
        return token;
    }

    // ==================== 工具方法 ====================

    private String postJson(String path, String json, String tokenValue) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String getBody(String path, String tokenValue) throws Exception {
        var request = get(path);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private int code(String body) {
        int start = body.indexOf("\"code\":") + 7;
        return Integer.parseInt(body.substring(start, body.indexOf(',', start)));
    }

    private String message(String body) {
        int start = body.indexOf("\"message\":\"") + 11;
        return body.substring(start, body.indexOf('"', start));
    }

    private String dataValue(String body) {
        int start = body.indexOf("\"data\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    /** 统计子串出现次数：用于「恰好 N 条」这类精确计数断言 */
    private int countOf(String body, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 通过管理端接口新增成员（简单版：默认置顶权重 0） */
    private long addMember(String name) throws Exception {
        String body = postJson("/member/add",
                "{\"name\":\"" + name + "\",\"gradeYear\":2022}", token());
        assertEquals(0, code(body), "新增成员失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    /** 通过管理端接口新增成员（指定置顶权重，用于排序用例） */
    private long addMember(String name, int sortOrder) throws Exception {
        String body = postJson("/member/add",
                "{\"name\":\"" + name + "\",\"gradeYear\":2022,\"sortOrder\":" + sortOrder + "}", token());
        assertEquals(0, code(body), "新增成员失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    /** 通过管理端接口新增证书，返回其 id */
    private long addCertificate(String title, int sortOrder, LocalDate awardDate) throws Exception {
        String body = postJson("/certificate/add",
                "{\"title\":\"" + title + "\",\"awardLevel\":\"" + CertificateLevelEnum.NATIONAL.getValue()
                        + "\",\"awardType\":\"" + CertificateTypeEnum.COMPETITION.getValue()
                        + "\",\"awardDate\":\"" + awardDate
                        + "\",\"imageUrl\":\"/uploads/cert.png\",\"sortOrder\":" + sortOrder + "}", token());
        assertEquals(0, code(body), "新增证书失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    /** 通过管理端接口新增项目（队长会被自动同步进关联表），返回其 id */
    private long addProject(String title, long leaderId, int sortOrder) throws Exception {
        String body = postJson("/project/add",
                "{\"title\":\"" + title + "\",\"description\":\"desc\",\"leaderId\":" + leaderId
                        + ",\"sortOrder\":" + sortOrder + "}", token());
        assertEquals(0, code(body), "新增项目失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    private void bindCertificate(long memberId, long certificateId) throws Exception {
        assertEquals(0, code(postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + certificateId + "}", token())));
    }

    private void bindProject(long memberId, long projectId) throws Exception {
        assertEquals(0, code(postJson("/member-project/bind",
                "{\"projectId\":" + projectId + ",\"memberId\":" + memberId + "}", token())));
    }

    private long countCertificateRelations(long memberId, long certificateId) {
        return memberCertificateMapper.selectCount(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId)
                .eq(StudioMemberCertificate::getCertificateId, certificateId));
    }

    private long countProjectRelations(long memberId, long projectId) {
        return memberProjectMapper.selectCount(new LambdaQueryWrapper<StudioMemberProject>()
                .eq(StudioMemberProject::getMemberId, memberId)
                .eq(StudioMemberProject::getProjectId, projectId));
    }

    /** C 端聚合接口禁止出现的内部字段（null 也会被序列化，字段一旦混入必然现形） */
    private static final String[] INTERNAL_FIELDS = {
            "\"userId\"", "\"leaderId\"", "\"sortOrder\"",
            "\"createdAt\"", "\"updatedAt\"", "\"createdBy\"", "\"updatedBy\"", "\"deletedAt\""
    };

    // ==================== 白名单与鉴权边界 ====================

    @Test
    @DisplayName("鉴权：/member/detail 匿名放行；/member/get 等管理端接口仍 40100")
    void accessControlAndWhitelistPrecision() throws Exception {
        long memberId = addMember("白名单成员");

        // 新接口匿名放行
        assertEquals(0, code(getBody("/member/detail?id=" + memberId, null)), "成员详情应匿名可访问（白名单）");

        // 既有公开接口回归：加了新白名单条目不应影响它们
        assertEquals(0, code(getBody("/member/list", null)), "C 端成员列表应匿名可访问");
        assertEquals(0, code(getBody("/project/list", null)), "C 端项目列表应匿名可访问");

        // 白名单精确性：/member/get 与 /member/detail 只差一个后缀，不能被顺带放行
        assertEquals(40100, code(getBody("/member/get?id=" + memberId, null)), "管理端成员详情不能被放行");
        assertEquals(40100, code(postJson("/member/add", "{}", null)), "匿名不能新增成员");
        assertEquals(40100, code(getBody("/member/list/page", null)), "管理端分页列表不能被放行");
    }

    // ==================== 聚合结构与关联对齐 ====================

    @Test
    @DisplayName("聚合结构：档案 + 恰好 2 证书 + 恰好 2 项目，不夹带他人数据")
    void aggregatesSectionsAlignedWithRelations() throws Exception {
        long otherMember = addMember("聚-无关成员");
        long memberId = addMember("聚-主角");

        long certA = addCertificate("聚证书一", 5, LocalDate.now().minusDays(3));
        long certB = addCertificate("聚证书二", 3, LocalDate.now().minusDays(5));
        long certOther = addCertificate("聚-无关证书", 9, LocalDate.now());
        bindCertificate(memberId, certA);
        bindCertificate(memberId, certB);
        bindCertificate(otherMember, certOther);

        // 项目一：主角是队长（新增时自动同步进关联表）；项目二：显式绑定；无关项目不绑定
        addProject("聚项目一", memberId, 5);
        long projectBound = addProject("聚项目二", otherMember, 3);
        bindProject(memberId, projectBound);
        addProject("聚-无关项目", otherMember, 9);

        String body = getBody("/member/detail?id=" + memberId, null);
        assertEquals(0, code(body), "成员详情返回失败：" + body);

        // 三段齐备
        assertTrue(body.contains("\"profile\":{"), "缺少 profile 段：" + body);
        assertTrue(body.contains("\"certificates\":[{" ), "缺少 certificates 内容：" + body);
        assertTrue(body.contains("\"projects\":[{" ), "缺少 projects 内容：" + body);

        // 档案字段抽查
        assertTrue(body.contains("聚-主角") && body.contains("2022"), "profile 字段不完整：" + body);

        // 恰好等于关联数（awardLevel 每张证书一个、status 每个项目一个，且都不会为 null）
        assertEquals(2, countOf(body, "\"awardLevel\""), "证书应恰好返回绑定的 2 张：" + body);
        assertEquals(2, countOf(body, "\"status\":"), "项目应恰好返回关联的 2 个：" + body);

        assertTrue(body.contains("聚证书一") && body.contains("聚证书二"), "绑定的证书没有全部出现");
        assertTrue(body.contains("聚项目一") && body.contains("聚项目二"), "关联的项目没有全部出现");
        assertFalse(body.contains("聚-无关证书"), "夹带了其他成员的证书：" + body);
        assertFalse(body.contains("聚-无关项目"), "夹带了未关联的项目：" + body);
    }

    // ==================== 脱敏 ====================

    @Test
    @DisplayName("脱敏：三段均无内部字段；项目正文只走项目详情，不混进成员聚合")
    void sanitizedAgainstInternalFields() throws Exception {
        long memberId = addMember("脱敏成员");
        long certificateId = addCertificate("脱敏证书", 5, LocalDate.now());
        bindCertificate(memberId, certificateId);

        long projectId = addProject("脱敏项目", memberId, 5);
        String update = postJson("/project/update",
                "{\"id\":" + projectId + ",\"content\":\"SECRET-项目正文\"}", token());
        assertEquals(0, code(update), "写入项目正文失败：" + update);

        String memberBody = getBody("/member/detail?id=" + memberId, null);
        assertEquals(0, code(memberBody));
        for (String forbidden : INTERNAL_FIELDS) {
            assertFalse(memberBody.contains(forbidden), "成员聚合泄露了内部字段 " + forbidden + "：" + memberBody);
        }
        // content 是大字段：项目卡片只需标题/摘要，正文只在项目详情取
        assertFalse(memberBody.contains("\"content\""), "成员聚合不应包含项目正文 JSON 字段：" + memberBody);
        assertFalse(memberBody.contains("SECRET-项目正文"), "成员聚合不应夹带正文内容：" + memberBody);

        // 对照组：项目详情接口仍有正文（脱敏不是靠砍能力实现的）
        String projectBody = getBody("/project/detail?id=" + projectId, null);
        assertEquals(0, code(projectBody));
        assertTrue(projectBody.contains("SECRET-项目正文"), "项目详情应返回正文：" + projectBody);
    }

    // ==================== 错误码约定 ====================

    @Test
    @DisplayName("错误码：id 非法 40000；成员不存在 40400 且提示明确")
    void invalidAndMissingMemberCodes() throws Exception {
        assertEquals(40000, code(getBody("/member/detail?id=0", null)), "id=0 应 40000");
        assertEquals(40000, code(getBody("/member/detail?id=-1", null)), "id 为负应 40000");

        String notFound = getBody("/member/detail?id=999999999999999999", null);
        assertEquals(40400, code(notFound), "不存在的成员应 40400：" + notFound);
        assertEquals("成员不存在", message(notFound));

        // 项目侧同契约回归
        assertEquals(40000, code(getBody("/project/detail?id=0", null)));
    }

    // ==================== 悬空防御 ====================

    @Test
    @DisplayName("悬空防御：主表已删（关联残留）的证书/项目不出现在聚合里")
    void danglingRelationsAreFiltered() throws Exception {
        long memberId = addMember("悬空成员");
        long certificateId = addCertificate("悬空证书", 1, LocalDate.now());
        bindCertificate(memberId, certificateId);

        long projectOwner = addMember("悬空项目的队长");
        long projectId = addProject("悬空项目", projectOwner, 1);
        bindProject(memberId, projectId);

        // 直接对主表做逻辑删除（不经过 Service，因而不会触发级联清理），制造真正的悬空关联
        studioCertificateMapper.deleteById(certificateId);
        studioProjectMapper.deleteById(projectId);

        // 关联行仍在：证明下面的过滤发生在查询侧，而不是级联清理的功劳
        assertEquals(1, countCertificateRelations(memberId, certificateId), "关联行不应被 Mapper 删除");
        assertEquals(1, countProjectRelations(memberId, projectId), "关联行不应被 Mapper 删除");

        String body = getBody("/member/detail?id=" + memberId, null);
        assertEquals(0, code(body));
        assertFalse(body.contains("悬空证书"), "已逻辑删除的证书仍被聚合返回：" + body);
        assertFalse(body.contains("悬空项目"), "已逻辑删除的项目仍被聚合返回：" + body);
        assertEquals(0, countOf(body, "\"awardLevel\""), "证书段应只剩空数组：" + body);
        assertEquals(0, countOf(body, "\"status\":"), "项目段应只剩空数组：" + body);
    }

    // ==================== 项目详情的 members 段 ====================

    @Test
    @DisplayName("项目详情：members 含队长与已绑定成员，匿名可访问且脱敏")
    void projectDetailMembersIncludeLeader() throws Exception {
        long leaderId = addMember("项员-队长");
        long memberId = addMember("项员-成员");
        long projectId = addProject("项详情-聚合", leaderId, 3);
        postJson("/project/update",
                "{\"id\":" + projectId + ",\"content\":\"项详情正文\",\"techStack\":[\"Java\",\"Vue\"]}", token());
        bindProject(memberId, projectId);

        String body = getBody("/project/detail?id=" + projectId, null);
        assertEquals(0, code(body), "项目详情应匿名可访问：" + body);

        assertTrue(body.contains("\"members\":[{"), "缺少 members 段：" + body);
        assertEquals(2, countOf(body, "\"memberStatus\":"), "members 应恰好 2 人：" + body);
        assertTrue(body.contains("项员-队长"), "队长必须出现在参与成员里（leader_id 同步不变量）：" + body);
        assertTrue(body.contains("项员-成员"), "已绑定成员没有出现：" + body);

        // 详情原有字段不受影响
        assertTrue(body.contains("项详情正文"), "内容字段回归失败");
        assertTrue(body.contains("\"techStack\":[\"Java\",\"Vue\"]"), "techStack 回归失败：" + body);

        // members 段同样脱敏（不含 userId / sortOrder / 审计字段）
        for (String forbidden : INTERNAL_FIELDS) {
            assertFalse(body.contains(forbidden), "项目详情的 members 泄露了内部字段 " + forbidden + "：" + body);
        }
    }

    // ==================== 排序 ====================

    @Test
    @DisplayName("排序：成员详情的证书/项目、项目详情的成员，均与各模块既定规则一致")
    void sectionsKeepModuleOrdering() throws Exception {
        long memberId = addMember("排序主角");

        // 证书：sort_order 倒序 → award_date 倒序（两张同权重时按获奖日期分先后）
        bindCertificate(memberId, addCertificate("序证高", 9, LocalDate.now().minusDays(20)));
        bindCertificate(memberId, addCertificate("序证中", 5, LocalDate.now().minusDays(1)));
        bindCertificate(memberId, addCertificate("序证低", 5, LocalDate.now().minusDays(10)));

        // 项目：sort_order 倒序 → created_at 倒序（同为 5 时，后创建的在先）
        addProject("序项高", memberId, 9);
        addProject("序项早", memberId, 5);
        addProject("序项晚", memberId, 5);

        String body = getBody("/member/detail?id=" + memberId, null);
        assertEquals(0, code(body));
        assertTrue(body.indexOf("序证高") < body.indexOf("序证中")
                        && body.indexOf("序证中") < body.indexOf("序证低"),
                "证书排序应为 sort_order 倒序 → award_date 倒序：" + body);
        assertTrue(body.indexOf("序项高") < body.indexOf("序项晚")
                        && body.indexOf("序项晚") < body.indexOf("序项早"),
                "项目排序应为 sort_order 倒序 → created_at 倒序：" + body);

        // 项目详情的成员：sort_order 倒序 → id 倒序（队长也在其中参与排序）
        long memberLow = addMember("序员甲", 1);
        long memberMid = addMember("序员乙", 5);
        long memberHigh = addMember("序员丙", 9);
        long projectId = addProject("排序项目", memberMid, 0);
        bindProject(memberLow, projectId);
        bindProject(memberHigh, projectId);

        String projectBody = getBody("/project/detail?id=" + projectId, null);
        assertEquals(0, code(projectBody));
        assertTrue(projectBody.indexOf("序员丙") < projectBody.indexOf("序员乙")
                        && projectBody.indexOf("序员乙") < projectBody.indexOf("序员甲"),
                "成员排序应为 sort_order 倒序 → id 倒序：" + projectBody);
    }

    // ==================== 空数据边界 ====================

    @Test
    @DisplayName("空数据：无证书无项目的成员，两段返回空数组而不是 null")
    void emptySectionsAreEmptyArrays() throws Exception {
        long memberId = addMember("空段成员");

        String body = getBody("/member/detail?id=" + memberId, null);
        assertEquals(0, code(body));
        assertTrue(body.contains("空段成员"), "profile 不应为空：" + body);
        assertTrue(body.contains("\"certificates\":[]"), "证书段应为空数组：" + body);
        assertTrue(body.contains("\"projects\":[]"), "项目段应为空数组：" + body);
    }
}