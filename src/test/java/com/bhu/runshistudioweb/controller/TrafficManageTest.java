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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 流量埋点与统计验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TrafficManageTest {

    private static final String ACCOUNT_PREFIX = "T34traf_";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    // ① 鉴权

    @Test
    @DisplayName("鉴权：埋点匿名可访问；统计接口匿名 A0201、普通用户 A0301、admin 放行")
    void permissionMatrix() throws Exception {
        // 埋点：匿名可访问（游客的访问也要统计）
        String report = postJson("/traffic/report",
                "{\"pagePath\":\"/posts\",\"visitorId\":\"v-anon\"}", null);
        assertEquals("00000", code(report), "埋点应匿名可访问：" + report);

        // 统计：匿名拒绝
        assertEquals("A0201", code(getBody("/admin/traffic/overview", null)), "统计匿名应 A0201");

        // 统计：普通用户拒绝
        String userToken = tokenOf(createUser(UserRoleConstant.USER));
        assertEquals("A0301", code(getBody("/admin/traffic/overview", userToken)), "统计普通用户应 A0301");

        // 统计：admin 放行（用「拿到完整结构」作证据）
        String admin = tokenOf(createUser(UserRoleConstant.ADMIN));
        String overview = getBody("/admin/traffic/overview?days=7", admin);
        assertEquals("00000", code(overview), "admin 应能查统计：" + overview);
        JsonNode data = jsonMapper.readTree(overview).get("data");
        assertNotNull(data.get("totalPv"), "响应应含 totalPv");
        assertNotNull(data.get("daily"), "响应应含每日趋势");
        assertNotNull(data.get("topPages"), "响应应含热门页面");
    }

    // ② 埋点规则

    @Test
    @DisplayName("埋点：参数缺失 A0401；query / hash 被剥掉（防 token 入库）")
    void reportValidatesAndStripsQuery() throws Exception {
        assertEquals("A0401", code(postJson("/traffic/report", "{\"visitorId\":\"v\"}", null)),
                "缺 pagePath 应 A0401");
        assertEquals("A0401", code(postJson("/traffic/report", "{\"pagePath\":\"/posts\"}", null)),
                "缺 visitorId 应 A0401");
        // 匿名可写接口收紧输入：路径必须以 / 开头，否则任意字符串都能污染热门页面聚合
        assertEquals("A0401", code(postJson("/traffic/report",
                "{\"pagePath\":\"posts\",\"visitorId\":\"v\"}", null)), "路径不以 / 开头应 A0401");

        // 带 query 与 hash 的路径：落库时应只剩路径部分
        String body = postJson("/traffic/report",
                "{\"pagePath\":\"/posts?token=abc123#frag\",\"visitorId\":\"v-strip\"}", null);
        assertEquals("00000", code(body), "埋点应成功：" + body);
        assertEquals(1, countBy("SELECT COUNT(*) FROM studio_page_visit WHERE visitor_id = 'v-strip' "
                + "AND page_path = '/posts'"), "query 与 hash 应被剥掉，只留路径");
    }

    @Test
    @DisplayName("埋点：登录用户的 user_id 取自登录态，而不是请求体")
    void reportTakesUserIdFromSession() throws Exception {
        long userId = createUser(UserRoleConstant.USER);
        String token = tokenOf(userId);

        // 请求体里塞一个别人的 id：应被忽略（后端只认登录态）
        String body = postJson("/traffic/report",
                "{\"pagePath\":\"/projects\",\"visitorId\":\"v-session\",\"userId\":999}", token);
        assertEquals("00000", code(body), "埋点应成功：" + body);
        assertEquals(1, countBy("SELECT COUNT(*) FROM studio_page_visit WHERE visitor_id = 'v-session' "
                + "AND user_id = " + userId), "user_id 应取自登录态");
        assertEquals(0, countBy("SELECT COUNT(*) FROM studio_page_visit WHERE visitor_id = 'v-session' "
                + "AND user_id = 999"), "请求体里的 userId 不应被采信");
    }

    @Test
    @DisplayName("埋点：游客的 user_id 为 NULL")
    void reportForGuestHasNullUser() throws Exception {
        String body = postJson("/traffic/report",
                "{\"pagePath\":\"/ai\",\"visitorId\":\"v-guest\"}", null);
        assertEquals("00000", code(body), "游客埋点应成功：" + body);
        assertEquals(1, countBy("SELECT COUNT(*) FROM studio_page_visit WHERE visitor_id = 'v-guest' "
                + "AND user_id IS NULL"), "游客的 user_id 应为 NULL");
    }

    // ③ 统计口径

    @Test
    @DisplayName("统计：PV 逐次 +1、UV 按 visitor 去重、今日值只算当天、无访问日期补 0")
    void statisticsCountPvAndUv() throws Exception {
        String admin = tokenOf(createUser(UserRoleConstant.ADMIN));

        // 同一访客访问 3 次（PV 3 / UV 1），另一个访客 1 次（PV 4 / UV 2）
        for (int i = 0; i < 3; i++) {
            postJson("/traffic/report", "{\"pagePath\":\"/posts\",\"visitorId\":\"v-a\"}", null);
        }
        postJson("/traffic/report", "{\"pagePath\":\"/posts\",\"visitorId\":\"v-b\"}", null);

        String overview = getBody("/admin/traffic/overview?days=7", admin);
        JsonNode data = jsonMapper.readTree(overview).get("data");

        assertTrue(data.get("totalPv").asInt() >= 4, "总 PV 至少应含本次 4 次：" + overview);
        assertTrue(data.get("totalUv").asInt() >= 2, "总 UV 至少应含本次 2 个访客：" + overview);
        assertTrue(data.get("todayPv").asInt() >= 4, "今日 PV 至少应含本次 4 次（埋点即今天）");
        assertTrue(data.get("todayUv").asInt() >= 2, "今日 UV 至少应含本次 2 个访客");

        // 趋势：近 7 天应返回 7 行（无访问的日期补 0，不断档）
        assertEquals(7, data.get("daily").size(), "近 7 天趋势应返回 7 行（缺位补 0）");
        JsonNode todayRow = data.get("daily").get(data.get("daily").size() - 1);
        assertTrue(todayRow.get("pv").asInt() >= 4, "今日行应含本次访问：" + todayRow);
    }

    @Test
    @DisplayName("统计：热门页面按 PV 倒序，且路径不含 query")
    void topPagesSortedByPv() throws Exception {
        String admin = tokenOf(createUser(UserRoleConstant.ADMIN));

        for (int i = 0; i < 30; i++) {
            String r = postJson("/traffic/report",
                    "{\"pagePath\":\"/hot\",\"visitorId\":\"v-hot" + i + "\"}", null);
            assertEquals("00000", code(r), "第 " + i + " 次 /hot 埋点应成功：" + r);
        }
        String cold = postJson("/traffic/report",
                "{\"pagePath\":\"/cold\",\"visitorId\":\"v-cold\"}", null);
        assertEquals("00000", code(cold), "/cold 埋点应成功：" + cold);

        JsonNode topPages = jsonMapper.readTree(getBody("/admin/traffic/overview?days=7", admin))
                .get("data").get("topPages");
        assertTrue(topPages.size() > 0, "热门页面不应为空");

        int prevPv = Integer.MAX_VALUE;
        for (JsonNode row : topPages) {
            int pv = row.get("pv").asInt();
            assertTrue(pv <= prevPv, "热门页面必须按 PV 倒序 | topPages=" + topPages);
            prevPv = pv;
        }

        Integer hotPv = pvOf(topPages, "/hot");
        assertNotNull(hotPv, "热门页面应包含 /hot | topPages=" + topPages);
        assertTrue(hotPv >= 30, "/hot 应有至少 30 次 PV | topPages=" + topPages);

        if (pvOf(topPages, "/cold") != null) {
            assertTrue(indexOfPath(topPages, "/hot") < indexOfPath(topPages, "/cold"),
                    "PV 多的页面应排在前面 | topPages=" + topPages);
        }

        // 路径不带 query（埋点剥离逻辑在热门页面上的复验）
        for (JsonNode row : topPages) {
            assertFalse(row.get("pagePath").asText().contains("?"),
                    "热门页面路径不应含 query | topPages=" + topPages);
        }
    }

    // 工具方法

    private String postJson(String path, String json, String tokenValue) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String getBody(String path, String tokenValue) throws Exception {
        var request = get(path);
        if (tokenValue != null) {
            request.header("satoken", tokenValue);
        }
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String code(String body) throws Exception {
        return jsonMapper.readTree(body).get("code").asText();
    }

    private long countBy(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private Integer pvOf(JsonNode topPages, String path) {
        for (JsonNode node : topPages) {
            if (path.equals(node.get("pagePath").asText())) {
                return node.get("pv").asInt();
            }
        }
        return null;
    }

    private int indexOfPath(JsonNode topPages, String path) {
        for (int i = 0; i < topPages.size(); i++) {
            if (path.equals(topPages.get(i).get("pagePath").asText())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private long createUser(String role) {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    private String tokenOf(long userId) throws Exception {
        String account = jdbcTemplate.queryForObject(
                "SELECT user_account FROM sys_user WHERE id = ?", String.class, userId);
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        int start = body.indexOf("\"token\":\"") + 9;
        assertTrue(start > 9, "登录失败：" + body);
        return body.substring(start, body.indexOf('"', start));
    }
}
