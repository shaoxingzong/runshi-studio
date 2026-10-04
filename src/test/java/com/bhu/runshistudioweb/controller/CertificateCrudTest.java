package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import com.bhu.runshistudioweb.model.vo.CertificateFrontVO;
import com.bhu.runshistudioweb.model.vo.CertificateVO;
import com.bhu.runshistudioweb.service.CertificateService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证书模块验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖两条线：
 * <ol>
 *     <li><b>越权</b>：匿名访问后台接口 A0201、普通用户 A0301、C 端列表匿名可访问；</li>
 *     <li><b>四个考点</b>：级别与类型正交存储、获奖日期不得晚于今天、图片非空、
 *     默认排序 = 置顶权重倒序 + 获奖日期倒序。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CertificateCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private CertificateService certificateService;

    @Resource
    private SysUserMapper sysUserMapper;

    // ==================== 工具方法 ====================

    private long insertAdmin(String account) {
        SysUser admin = new SysUser();
        admin.setUserAccount(account);
        admin.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        admin.setUserName(account);
        admin.setUserRole(UserRoleConstant.ADMIN);
        admin.setUserStatus(0);
        sysUserMapper.insert(admin);
        return admin.getId();
    }

    private String login(String account) throws Exception {
        String body = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = body.substring(body.indexOf("\"token\":\"") + 9);
        return token.substring(0, token.indexOf('"'));
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

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private String dataValue(String body) {
        int start = body.indexOf("\"data\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private String addBody(String title, String level, String type, String date, String image, Integer sortOrder) {
        return "{\"title\":\"" + title + "\",\"awardLevel\":\"" + level + "\",\"awardType\":\"" + type
                + "\",\"awardDate\":\"" + date + "\",\"imageUrl\":\"" + image + "\""
                + (sortOrder == null ? "" : ",\"sortOrder\":" + sortOrder) + "}";
    }

    // ==================== 鉴权边界 ====================

    @Test
    @DisplayName("C 端列表匿名可访问；后台接口必须登录且是管理员")
    void accessControl() throws Exception {
        // C 端：白名单生效，游客能拿到列表
        assertEquals("00000", code(getBody("/certificate/list", null)), "C 端证书列表应匿名可访问（已加白名单）");

        // 后台：未登录 A0201
        assertEquals("A0201", code(getBody("/certificate/list/page", null)));
        assertEquals("A0201", code(postJson("/certificate/add", "{}", null)));

        // 后台：普通用户 A0301
        String account = "cert_user_" + (System.nanoTime() % 100000);
        SysUser normal = new SysUser();
        normal.setUserAccount(account);
        normal.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        normal.setUserName(account);
        normal.setUserRole(UserRoleConstant.USER);
        normal.setUserStatus(0);
        sysUserMapper.insert(normal);
        String token = login(account);
        assertEquals("A0301", code(getBody("/certificate/list/page", token)), "普通用户不应能访问后台证书列表");
    }

    // ==================== 四个考点 ====================

    @Test
    @DisplayName("考点①：级别与类型是分开的两个字段，非法值一律拒绝")
    void levelAndTypeAreSeparate() throws Exception {
        String admin = "cert_adm_" + (System.nanoTime() % 100000);
        insertAdmin(admin);
        String token = login(admin);
        String pastDate = LocalDate.now().minusDays(30).toString();

        // 正常：级别与类型分别落库
        String body = postJson("/certificate/add", addBody("数学建模国赛", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.COMPETITION.getValue(), pastDate, "/uploads/a.png", null), token);
        assertEquals("00000", code(body), "新增失败：" + body);

        StudioCertificate saved = certificateService.getById(Long.parseLong(dataValue(body)));
        assertEquals(CertificateLevelEnum.NATIONAL.getValue(), saved.getAwardLevel());
        assertEquals(CertificateTypeEnum.COMPETITION.getValue(), saved.getAwardType());

        // 非法级别 / 非法类型：ADR-5 要求拆开存，取值必须在枚举内
        assertEquals("A0401", code(postJson("/certificate/add",
                addBody("级别非法", "superman", CertificateTypeEnum.COMPETITION.getValue(), pastDate,
                        "/uploads/a.png", null), token)));
        assertEquals("A0401", code(postJson("/certificate/add",
                addBody("类型非法", CertificateLevelEnum.NATIONAL.getValue(), "unknown", pastDate,
                        "/uploads/a.png", null), token)));
    }

    @Test
    @DisplayName("考点②：获奖日期不得晚于今天；考点③：图片 URL 不能为空")
    void awardDateAndImageValidation() throws Exception {
        String admin = "cert_val_" + (System.nanoTime() % 100000);
        insertAdmin(admin);
        String token = login(admin);
        String pastDate = LocalDate.now().minusDays(10).toString();

        // 日期晚于今天 → 拒绝
        assertEquals("A0401", code(postJson("/certificate/add", addBody("未来的奖", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.PAPER.getValue(), LocalDate.now().plusDays(1).toString(),
                "/uploads/a.png", null), token)));

        // 图片为空 → 拒绝（DDL 中 image_url 是 NOT NULL）
        assertEquals("A0401", code(postJson("/certificate/add", addBody("没图", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.PAPER.getValue(), pastDate, "", null), token)));

        // 合法组合 → 通过
        assertEquals("00000", code(postJson("/certificate/add", addBody("合法证书", CertificateLevelEnum.PROVINCIAL.getValue(),
                CertificateTypeEnum.PATENT.getValue(), pastDate, "/uploads/b.png", null), token)));
    }

    @Test
    @DisplayName("考点④：默认排序 = 置顶权重倒序 + 获奖日期倒序")
    void defaultSorting() throws Exception {
        String admin = "cert_sort_" + (System.nanoTime() % 100000);
        insertAdmin(admin);
        String token = login(admin);

        // 低权重 + 较新日期
        postJson("/certificate/add", addBody("低权重新日期", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.COMPETITION.getValue(), LocalDate.now().minusDays(5).toString(),
                "/uploads/1.png", 1), token);
        // 高权重 + 较旧日期：应排在最前
        postJson("/certificate/add", addBody("高权重旧日期", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.COMPETITION.getValue(), LocalDate.now().minusDays(100).toString(),
                "/uploads/2.png", 9), token);

        List<CertificateFrontVO> list = certificateService.listFrontCertificates(null);
        List<String> titles = list.stream().map(CertificateFrontVO::getTitle).toList();
        assertTrue(list.size() >= 2, "列表至少应有两条记录，实际：" + list.size());

        // 断言**这两条的相对顺序**，而不是「首条必须是高权重」。
        // 为什么：库里可能已有演示数据（它们的置顶权重可能更高），
        // 那样 list.get(0) 就不是本用例建的那条——那是把环境当成前提，不是排序有 bug。
        int idxHigh = titles.indexOf("高权重旧日期");
        int idxLow = titles.indexOf("低权重新日期");
        assertTrue(idxHigh >= 0 && idxLow >= 0, "本用例建的两条都应出现在列表中，实际：" + titles);
        assertTrue(idxHigh < idxLow,
                "置顶权重高的应排在前面（哪怕获奖日期更早）——这就是「先按 sort_order 倒序」的含义，实际：" + titles);
    }

    @Test
    @DisplayName("C 端 VO 必须脱敏：不含置顶权重与审计字段")
    void frontVoIsSanitized() throws Exception {
        String admin = "cert_vo_" + (System.nanoTime() % 100000);
        insertAdmin(admin);
        String token = login(admin);
        postJson("/certificate/add", addBody("脱敏验证", CertificateLevelEnum.MUNICIPAL.getValue(),
                CertificateTypeEnum.SOFT_COPYRIGHT.getValue(), LocalDate.now().minusDays(3).toString(),
                "/uploads/c.png", 3), token);

        String body = getBody("/certificate/list", null);
        assertEquals("00000", code(body));
        assertFalse(body.contains("sortOrder"), "C 端返回了置顶权重：" + body);
        assertFalse(body.contains("createdAt"), "C 端返回了审计时间：" + body);
        assertFalse(body.contains("deletedAt"), "C 端返回了逻辑删除标记：" + body);
    }

    @Test
    @DisplayName("全流程：新增 → 详情 → 更新 → 分页 → 删除（逻辑删除）")
    void crudFlow() throws Exception {
        String admin = "cert_flow_" + (System.nanoTime() % 100000);
        insertAdmin(admin);
        String token = login(admin);
        String pastDate = LocalDate.now().minusDays(7).toString();

        long id = Long.parseLong(dataValue(postJson("/certificate/add",
                addBody("流程证书", CertificateLevelEnum.NATIONAL.getValue(),
                        CertificateTypeEnum.COMPETITION.getValue(), pastDate, "/uploads/d.png", 2), token)));

        // 详情
        String detail = getBody("/certificate/get?id=" + id, token);
        assertEquals("00000", code(detail));

        // 更新：只改名称，其余不动
        assertEquals("00000", code(postJson("/certificate/update", "{\"id\":" + id + ",\"title\":\"改后名称\"}", token)));
        CertificateVO updated = certificateService.getCertificateById(id);
        assertEquals("改后名称", updated.getTitle());
        assertEquals(CertificateLevelEnum.NATIONAL.getValue(), updated.getAwardLevel(), "未传的字段不应被改动");

        // 分页查询
        String pageBody = getBody("/certificate/list/page?current=1&pageSize=10&title=改后名称", token);
        assertEquals("00000", code(pageBody));
        assertTrue(pageBody.contains("改后名称"), "按名称模糊查询没查到：" + pageBody);

        // 删除 → 逻辑删除后查不到
        assertEquals("00000", code(postJson("/certificate/delete", "{\"id\":" + id + "}", token)));
        assertEquals("A0402", code(getBody("/certificate/get?id=" + id, token)), "删除后应查不到");
    }
}
