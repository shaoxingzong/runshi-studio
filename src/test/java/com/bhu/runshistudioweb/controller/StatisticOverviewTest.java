package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.mapper.StudioCertificateMapper;
import com.bhu.runshistudioweb.model.entity.StudioCertificate;
import com.bhu.runshistudioweb.model.enums.CertificateLevelEnum;
import com.bhu.runshistudioweb.model.enums.CertificateTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 首页大盘统计验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖四条线：
 * <ol>
 *     <li><b>匿名可访问 + 类型正确</b>：C 端白名单生效，且计数字段是 JSON <b>数字</b>而不是字符串
 *     （VO 用 Integer 避开全局的 Long → 字符串规则，这条断言就是那个决策的守门人）；
 *     同时断言<b>成员计数不得出现</b>——这是「团队成员不对外展示」的守门人；</li>
 *     <li><b>口径正确</b>：统计结果与「直查数据库（MP 自动排除逻辑删除）」一致，
 *     且逻辑删除后计数立刻减少——这是"排除已删除"这条口径的硬证据；</li>
 *     <li><b>分布完整性</b>：枚举里的 key 一个不少（缺位补 0），且各分布求和等于总数；</li>
 *     <li><b>回归</b>：管理端接口仍然要求管理员（白名单只放行了这一个公开统计路径）。</li>
 * </ol>
 *
 * <p>数据直接用 Mapper 插入：本测试考的是"统计"而不是"增删改"（那两块的接口另有测试守着），
 * 用 Mapper 插入才能构造出"任意状态组合"的确定场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatisticOverviewTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private StudioCertificateMapper studioCertificateMapper;

    // ==================== 工具方法 ====================

    /** 匿名请求大盘接口，返回原始响应体 */
    private String fetchOverview() throws Exception {
        return mockMvc.perform(get("/statistic/overview")).andReturn().getResponse().getContentAsString();
    }

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    /**
     * 取出形如 {@code "key":123} 的数值（含嵌套 Map 里的 key）
     * <p>注意：只适用于数字字段；字符串字段的值前面会有引号，本方法会解析失败
     */
    private int numData(String body, String key) {
        String marker = "\"" + key + "\":";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, "响应里找不到字段 " + key + "：" + body);
        int from = start + marker.length();
        int end = from;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        assertTrue(end > from, "字段 " + key + " 的值不是数字（可能是字符串）：" + body);
        return Integer.parseInt(body.substring(from, end));
    }

    private long insertCertificate(String title, String level, String type) {
        StudioCertificate certificate = new StudioCertificate();
        certificate.setTitle(title);
        certificate.setAwardLevel(level);
        certificate.setAwardType(type);
        certificate.setAwardDate(LocalDate.now().minusDays(10));
        certificate.setImageUrl("/uploads/stat.png");
        certificate.setSortOrder(0);
        studioCertificateMapper.insert(certificate);
        return certificate.getId();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("匿名可访问，且计数字段是 JSON 数字（不是字符串）")
    void anonymousAccessWithNumberTypes() throws Exception {
        String body = fetchOverview();
        assertEquals("00000", code(body), "匿名访问 /statistic/overview 应放行（白名单）：" + body);

        // 关键断言：`"certificateTotal":` 后面必须直接是数字，而不是引号——
        // 若 VO 误用 Long，全局 JsonConfig 会把它序列化成字符串，这条会失败
        assertFalse(body.contains("\"certificateTotal\":\""), "计数字段被序列化成了字符串：" + body);
        numData(body, "certificateTotal");

        //「团队成员不对外展示」的守门人：成员计数一旦被人加回这个匿名接口，这里立刻失败
        assertFalse(body.contains("memberTotal"), "匿名大盘接口不得出现成员计数：" + body);
        assertFalse(body.contains("memberInTeam"), "匿名大盘接口不得出现成员计数：" + body);
        assertFalse(body.contains("memberGraduated"), "匿名大盘接口不得出现成员计数：" + body);
    }

    @Test
    @DisplayName("计数与直查一致，且逻辑删除后立即减少（排除已删除口径）")
    void countsMatchDirectQueryAndExcludeDeleted() throws Exception {
        long certId = insertCertificate("stat-c-1", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.COMPETITION.getValue());

        String body = fetchOverview();
        // 期望值直接查库（MP 自动排除 deleted_at != 0，与统计口径同源）
        assertEquals(studioCertificateMapper.selectCount(null).intValue(), numData(body, "certificateTotal"));

        int totalBefore = numData(body, "certificateTotal");
        assertTrue(totalBefore >= 1, "刚插入的证书应被统计：" + body);

        // 逻辑删除一张证书：总数必须减 1
        // （原「逻辑删除成员」的验证已随成员维度移除，这条口径的硬证据改由证书承担）
        studioCertificateMapper.deleteById(certId);

        String after = fetchOverview();
        assertEquals(totalBefore - 1, numData(after, "certificateTotal"), "逻辑删除的证书仍被统计进去了");
    }

    @Test
    @DisplayName("分布 key 一个不少（缺位补 0），且求和等于证书总数")
    void distributionKeysCompleteAndSumMatchesTotal() throws Exception {
        insertCertificate("stat-c-lv1", CertificateLevelEnum.NATIONAL.getValue(),
                CertificateTypeEnum.COMPETITION.getValue());
        insertCertificate("stat-c-lv2", CertificateLevelEnum.MUNICIPAL.getValue(),
                CertificateTypeEnum.PAPER.getValue());

        String body = fetchOverview();

        // 枚举里的每个取值都必须出现（哪怕计数为 0）
        for (CertificateLevelEnum level : CertificateLevelEnum.values()) {
            assertTrue(body.contains("\"" + level.getValue() + "\":"),
                    "分布里缺少级别 key：" + level.getValue() + "，响应：" + body);
        }
        for (CertificateTypeEnum type : CertificateTypeEnum.values()) {
            assertTrue(body.contains("\"" + type.getValue() + "\":"),
                    "分布里缺少类型 key：" + type.getValue() + "，响应：" + body);
        }

        // 分布求和 == 总数（口径自洽：每个未删除的证书恰好落在一个桶里）
        int total = numData(body, "certificateTotal");
        int sumByLevel = 0;
        for (CertificateLevelEnum level : CertificateLevelEnum.values()) {
            sumByLevel += numData(body, level.getValue());
        }
        int sumByType = 0;
        for (CertificateTypeEnum type : CertificateTypeEnum.values()) {
            sumByType += numData(body, type.getValue());
        }
        assertEquals(total, sumByLevel, "级别分布之和与证书总数不一致：" + body);
        assertEquals(total, sumByType, "类型分布之和与证书总数不一致：" + body);

        // 刚插入的市政级证书必须被计入
        assertTrue(numData(body, CertificateLevelEnum.MUNICIPAL.getValue()) >= 1,
                "刚插入的 municipal 证书没有被统计：" + body);
    }

    @Test
    @DisplayName("回归：白名单只放行了公开统计路径，管理端接口仍要求管理员")
    void managementEndpointsStillProtected() throws Exception {
        String anon = mockMvc.perform(get("/member/list/page")).andReturn().getResponse().getContentAsString();
        assertEquals("A0201", code(anon), "管理端接口不该被统计模块的白名单顺带放行");
    }
}