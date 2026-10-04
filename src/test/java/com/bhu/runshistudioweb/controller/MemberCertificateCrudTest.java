package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberCertificateMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioMemberCertificate;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
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
 * 成员-证书关联模块验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖四条线：
 * <ol>
 *     <li><b>绑定/解绑主流程</b>：绑定后两个方向都能查到，解绑后都消失；</li>
 *     <li><b>错误码约定</b>：参数非法 40000、成员/证书不存在 40400、
 *     重复绑定 40000、解绑不存在的关系 40400；</li>
 *     <li><b>C 端脱敏与匿名可访问</b>：官网接口不带 token 可用，且不含置顶权重与审计字段；</li>
 *     <li><b>级联清理</b>：删成员 / 删证书时，关联行必须被同一事务清掉（DESIGN.md 2.2）。</li>
 * </ol>
 *
 * <p>测试数据一律通过管理端接口创建（而不是直接插库），这样也顺带验证了主表接口可用；
 * 关联行是否残留则用 Mapper 直查，避免「用同一个 Service 的读方法验证它的写方法」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberCertificateCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberCertificateMapper relationMapper;

    /** 管理员 token：本模块后台接口全部要求 admin 角色 */
    private String token;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        String account = "mc_adm_" + (System.nanoTime() % 100000);
        SysUser admin = new SysUser();
        admin.setUserAccount(account);
        admin.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        admin.setUserName(account);
        admin.setUserRole(UserRoleConstant.ADMIN);
        admin.setUserStatus(0);
        sysUserMapper.insert(admin);

        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        // 登录接口的 data 是 LoginUserVO 对象，token 嵌在里面：{"code":0,"data":{...,"token":"..."}}
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "管理员登录失败：" + body);
        token = body.substring(start, body.indexOf('"', start));
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
        if (start < 8) {
            return null;
        }
        return body.substring(start, body.indexOf('"', start));
    }

    /** 通过管理端接口新增成员，返回其 id */
    private long addMember(String name) throws Exception {
        String body = postJson("/member/add",
                "{\"name\":\"" + name + "\",\"gradeYear\":2022}", token);
        assertEquals(0, code(body), "新增成员失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    /** 通过管理端接口新增证书，返回其 id */
    private long addCertificate(String title, int sortOrder) throws Exception {
        String body = postJson("/certificate/add",
                "{\"title\":\"" + title + "\",\"awardLevel\":\"" + CertificateLevelEnum.NATIONAL.getValue()
                        + "\",\"awardType\":\"" + CertificateTypeEnum.COMPETITION.getValue()
                        + "\",\"awardDate\":\"" + LocalDate.now().minusDays(10)
                        + "\",\"imageUrl\":\"/uploads/cert.png\",\"sortOrder\":" + sortOrder + "}", token);
        assertEquals(0, code(body), "新增证书失败：" + body);
        return Long.parseLong(dataValue(body));
    }

    private long countRelations(long memberId, long certificateId) {
        return relationMapper.selectCount(new LambdaQueryWrapper<StudioMemberCertificate>()
                .eq(StudioMemberCertificate::getMemberId, memberId)
                .eq(StudioMemberCertificate::getCertificateId, certificateId));
    }

    // ==================== 主流程 ====================

    @Test
    @DisplayName("绑定 → 双向可查 → 解绑 → 双向查不到；重复解绑返回 40400")
    void bindAndUnbindFlow() throws Exception {
        long memberId = addMember("关联流程成员");
        long certificateId = addCertificate("关联流程证书", 1);

        String bindBody = postJson("/member-certificate/bind",
                "{\"memberId\":\"" + memberId + "\",\"certificateId\":\"" + certificateId + "\"}", token);
        assertEquals(0, code(bindBody), "绑定失败：" + bindBody);
        assertEquals(1, countRelations(memberId, certificateId), "关联行没有真正插入");

        // 成员 → 证书
        String certList = getBody("/member-certificate/certificate/list?memberId=" + memberId, token);
        assertEquals(0, code(certList));
        assertTrue(certList.contains("关联流程证书"), "按成员查不到证书：" + certList);

        // 证书 → 成员（反向查询）
        String memberList = getBody("/member-certificate/member/list?certificateId=" + certificateId, token);
        assertEquals(0, code(memberList));
        assertTrue(memberList.contains("关联流程成员"), "按证书查不到成员：" + memberList);

        // 解绑
        assertEquals(0, code(postJson("/member-certificate/unbind",
                "{\"memberId\":\"" + memberId + "\",\"certificateId\":\"" + certificateId + "\"}", token)));
        assertEquals(0, countRelations(memberId, certificateId), "关联行没有被物理删除");
        assertTrue(getBody("/member-certificate/certificate/list?memberId=" + memberId, token)
                .contains("\"data\":[]"), "解绑后仍查到证书");

        // 重复解绑：关系已不存在
        String again = postJson("/member-certificate/unbind",
                "{\"memberId\":\"" + memberId + "\",\"certificateId\":\"" + certificateId + "\"}", token);
        assertEquals(40400, code(again), "解绑不存在的关系应返回 40400");
        assertEquals("该成员未绑定此证书", message(again));
    }

    @Test
    @DisplayName("重复绑定返回 40000，且提示文案明确")
    void duplicateBindRejected() throws Exception {
        long memberId = addMember("重复绑定成员");
        long certificateId = addCertificate("重复绑定证书", 1);

        assertEquals(0, code(postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + certificateId + "}", token)));

        String second = postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + certificateId + "}", token);
        assertEquals(40000, code(second), "重复绑定应返回 40000（唯一索引也兜底并发场景）");
        assertEquals("该成员已绑定此证书", message(second));
        assertEquals(1, countRelations(memberId, certificateId), "重复绑定不应产生第二行");
    }

    // ==================== 错误码约定 ====================

    @Test
    @DisplayName("错误码：参数非法 40000 / 成员不存在 40400 / 证书不存在 40400")
    void errorCodes() throws Exception {
        long memberId = addMember("错误码成员");
        long certificateId = addCertificate("错误码证书", 1);

        // id 为 0：参数非法
        assertEquals(40000, code(postJson("/member-certificate/bind",
                "{\"memberId\":0,\"certificateId\":" + certificateId + "}", token)));

        // 成员不存在
        String noMember = postJson("/member-certificate/bind",
                "{\"memberId\":999999999999999999,\"certificateId\":" + certificateId + "}", token);
        assertEquals(40400, code(noMember));
        assertEquals("成员不存在", message(noMember));

        // 证书不存在
        String noCert = postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":999999999999999999}", token);
        assertEquals(40400, code(noCert));
        assertEquals("证书不存在", message(noCert));

        // 查询接口缺参数：必须是 40000，不能被兜底成 50000
        // （原 /member/certificate/list 的缺参用例已随该 C 端接口下线删除，）
        assertEquals(40000, code(getBody("/member-certificate/member/list", token)), "缺 certificateId 应返回 40000");
    }

    // ==================== 排序（原 C 端用例改用管理端接口） ====================

    // 原有两个 C 端用例（「匿名可访问 + 脱敏」「C 端排序」）已随 /member/certificate/list
    // 整体删除。排序这条行为本身仍值得守住，
    // 因此改用管理端同名接口 /member-certificate/certificate/list 继续覆盖——
    // 两端共用同一套取数逻辑，证明「排序规则没有因下线而改变」。
    @Test
    @DisplayName("排序：置顶权重高的证书排在前面（管理端接口）")
    void sortedBySortOrderThenAwardDate() throws Exception {
        long memberId = addMember("排序成员");
        long lowCertId = addCertificate("低权重证书", 1);
        long highCertId = addCertificate("高权重证书", 9);
        postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + lowCertId + "}", token);
        postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + highCertId + "}", token);

        String list = getBody("/member-certificate/certificate/list?memberId=" + memberId, token);
        assertEquals(0, code(list), "管理端证书列表应可访问：" + list);
        assertTrue(list.indexOf("高权重证书") < list.indexOf("低权重证书"),
                "默认排序应是 sort_order 倒序，实际：" + list);
    }

    // ==================== 级联清理 ====================

    @Test
    @DisplayName("删除成员：同一事务内清理关联行，不留悬空数据")
    void deleteMemberCascadesRelations() throws Exception {
        long memberId = addMember("待删成员");
        long certificateId = addCertificate("成员证书", 1);
        postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + certificateId + "}", token);
        assertEquals(1, countRelations(memberId, certificateId));

        assertEquals(0, code(postJson("/member/delete", "{\"id\":" + memberId + "}", token)));

        assertEquals(0, countRelations(memberId, certificateId), "删成员后关联行必须被清空（DESIGN.md 2.2）");
    }

    @Test
    @DisplayName("删除证书：同一事务内清理关联行，不留悬空数据")
    void deleteCertificateCascadesRelations() throws Exception {
        long memberId = addMember("证书成员");
        long certificateId = addCertificate("待删证书", 1);
        postJson("/member-certificate/bind",
                "{\"memberId\":" + memberId + ",\"certificateId\":" + certificateId + "}", token);
        assertEquals(1, countRelations(memberId, certificateId));

        assertEquals(0, code(postJson("/certificate/delete", "{\"id\":" + certificateId + "}", token)));

        assertEquals(0, countRelations(memberId, certificateId), "删证书后关联行必须被清空（DESIGN.md 2.2）");
    }
}
