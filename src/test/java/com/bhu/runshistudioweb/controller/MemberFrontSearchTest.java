package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.enums.TeamPositionEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C 端成员分页搜索验收测试
 *
 * author: shaoshing
 *
 * <p>覆盖五条线：
 * <ol>
 *     <li><b>真分页</b>：响应是分页对象（records/total/size/current），total 与直查一致——
 *     「searchCount 必须为 true」这条纪律的守门测试；</li>
 *     <li><b>收敛规则</b>：pageSize &gt; 50 收敛、&lt; 1 兜底、current &lt; 1 兜底；</li>
 *     <li><b>多条件筛选</b>：届别 / 方向 / 状态 / 姓名组合命中，且互斥正确；</li>
 *     <li><b>枚举闭集校验</b>：非法职务/状态返回 40000（不是静默空列表）；</li>
 *     <li><b>排序稳定</b>：sort_order 倒序 + id 倒序——同权重时分页翻页不重不漏。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberFrontSearchTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    // ==================== 工具方法 ====================

    private String getBody(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }

    private int code(String body) {
        int start = body.indexOf("\"code\":") + 7;
        return Integer.parseInt(body.substring(start, body.indexOf(',', start)));
    }

    /** 取 "key":"value" 形式的字符串值（分页元数据被全局约定序列化为字符串） */
    private String strData(String body, String key) {
        String marker = "\"" + key + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, "响应里找不到字符串字段 " + key + "：" + body);
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }

    private long insertMember(String name, int gradeYear, String direction, int status, int sortOrder) {
        StudioMember member = new StudioMember();
        member.setName(name);
        member.setGradeYear(gradeYear);
        member.setDirection(direction);
        member.setMemberStatus(status);
        member.setTeamPosition(TeamPositionEnum.MEMBER.getValue());
        member.setSortOrder(sortOrder);
        studioMemberMapper.insert(member);
        return member.getId();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("匿名分页：返回分页对象，total 与直查一致（searchCount 生效）")
    void anonymousPaginationWithTotal() throws Exception {
        insertMember("t18-page-1", 2024, "PagingDir", MemberStatusEnum.IN_TEAM.getValue(), 0);

        String body = getBody("/member/list?current=1&pageSize=10");
        assertEquals(0, code(body), "匿名访问应放行：" + body);
        assertTrue(body.contains("\"records\":"), "响应必须是分页对象（含 records）：" + body);
        // total 与"直查数据库"一致——若误用 searchCount=false，total 会恒为 0，这条必挂
        assertEquals(studioMemberMapper.selectCount(null), Long.valueOf(strData(body, "total")),
                "total 与直查不一致（searchCount 可能被关掉了）：" + body);
        assertEquals("1", strData(body, "current"));
        assertEquals("10", strData(body, "size"));
    }

    @Test
    @DisplayName("分页收敛：pageSize=999→50、pageSize=0→10、current=0→1")
    void pageParamsAreClamped() throws Exception {
        assertEquals("50", strData(getBody("/member/list?pageSize=999"), "size"));
        assertEquals("10", strData(getBody("/member/list?pageSize=0"), "size"));
        assertEquals("1", strData(getBody("/member/list?current=0"), "current"));
    }

    @Test
    @DisplayName("多条件筛选：届别+方向+状态组合命中，互斥正确")
    void filtersAreApplied() throws Exception {
        insertMember("t18-f-hit1", 2031, "FilterDir", MemberStatusEnum.IN_TEAM.getValue(), 1);
        insertMember("t18-f-hit2", 2031, "FilterDir", MemberStatusEnum.IN_TEAM.getValue(), 2);
        insertMember("t18-f-miss", 2031, "FilterDir", MemberStatusEnum.GRADUATED.getValue(), 3);

        // 三条件组合：只有 hit1/hit2 命中（miss 是毕业状态）
        String body = getBody("/member/list?gradeYear=2031&direction=FilterDir&memberStatus=0");
        assertEquals("2", strData(body, "total"), "三条件组合命中数不对：" + body);
        assertTrue(body.contains("t18-f-hit1") && body.contains("t18-f-hit2"));
        assertFalse(body.contains("t18-f-miss"), "毕业成员不该出现在 in_team 筛选里");

        // 姓名模糊：命中但要与前缀隔离
        String byName = getBody("/member/list?name=t18-f-hit");
        assertEquals("2", strData(byName, "total"), "姓名模糊命中数不对：" + byName);

        // 空结果：total=0 且 records 为空数组（不是 null）
        String empty = getBody("/member/list?name=t18-f-nobody");
        assertEquals("0", strData(empty, "total"));
        assertTrue(empty.contains("\"records\":[]"), "空结果应是空数组：" + empty);
    }

    @Test
    @DisplayName("枚举闭集校验：非法职务/状态返回 40000 且提示合法取值")
    void invalidEnumRejected() throws Exception {
        String badPosition = getBody("/member/list?teamPosition=superman");
        assertEquals(40000, code(badPosition), "非法职务应 40000（不是静默空列表）：" + badPosition);
        assertTrue(badPosition.contains("member / leader / tech_lead"), "提示应列出合法取值：" + badPosition);

        String badStatus = getBody("/member/list?memberStatus=9");
        assertEquals(40000, code(badStatus), "非法状态应 40000：" + badStatus);
        assertTrue(badStatus.contains("在读/在队"), "提示应含状态含义：" + badStatus);

        // 合法枚举正常放行
        assertEquals(0, code(getBody("/member/list?teamPosition=leader&memberStatus=0")));
    }

    @Test
    @DisplayName("排序稳定：同权重按 id 倒序，翻页不重不漏")
    void sortStableAcrossPages() throws Exception {
        // 三条同权重成员：id 递增（插入顺序），期望展示顺序为倒序（后建在前）
        insertMember("t18-s-1", 2032, "SortDir", MemberStatusEnum.IN_TEAM.getValue(), 0);
        insertMember("t18-s-2", 2032, "SortDir", MemberStatusEnum.IN_TEAM.getValue(), 0);
        insertMember("t18-s-3", 2032, "SortDir", MemberStatusEnum.IN_TEAM.getValue(), 0);

        String page1 = getBody("/member/list?name=t18-s-&pageSize=2&current=1");
        String page2 = getBody("/member/list?name=t18-s-&pageSize=2&current=2");
        assertEquals("3", strData(page1, "total"));

        int i3 = page1.indexOf("t18-s-3");
        int i2 = page1.indexOf("t18-s-2");
        int i1 = page2.indexOf("t18-s-1");
        assertTrue(i3 >= 0 && i2 > i3, "第 1 页应按 id 倒序（s-3 在 s-2 前）：" + page1);
        assertTrue(i1 >= 0, "第 2 页应含 s-1：" + page2);
        assertFalse(page1.contains("t18-s-1"), "同一行不应同时出现在两页：" + page1);
    }

    @Test
    @DisplayName("C 端 VO 脱敏：不含 userId / 置顶权重 / 审计字段")
    void frontVoIsSanitized() throws Exception {
        insertMember("t18-vo", 2033, "VoDir", MemberStatusEnum.IN_TEAM.getValue(), 9);
        String body = getBody("/member/list?name=t18-vo");
        assertEquals(0, code(body));
        assertFalse(body.contains("\"userId\""), "泄露了 userId：" + body);
        assertFalse(body.contains("\"createdAt\""), "泄露了审计时间：" + body);
        assertFalse(body.contains("\"updatedAt\""), "泄露了审计时间：" + body);
        assertFalse(body.contains("\"deletedAt\""), "泄露了逻辑删除标记：" + body);
        assertTrue(body.contains("t18-vo"));
    }
}