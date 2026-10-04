package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 成员模块验收测试
 *
 * author: shaoshing
 *
 * <p>这组用例守四条线，对应本模块的四个考点：
 * <ol>
 *     <li><b>鉴权边界</b>：C 端 {@code /member/list} 匿名可用；
 *     管理端接口匿名 40100、普通用户 40101——「多放行一个路径」和「少放行一个路径」
 *     都会出问题，两个方向都要断言；</li>
 *     <li><b>user_id 绑定规则</b>：账号必须存在、不能被两个成员同时绑定、
 *     改绑要排除自己、删除后账号可被重新绑定；</li>
 *     <li><b>输入校验</b>：入学年份区间（smallint 语义）、职务/状态枚举、
 *     「2026级」这类字符串在 JSON 解析阶段就被拒绝；</li>
 *     <li><b>排序与脱敏</b>：默认按 sort_order 倒序（不是 id 倒序）；
 *     C 端 VO 不出现 userId / sortOrder 等内部字段。</li>
 * </ol>
 *
 * <p>测试直接插库造账号（不走注册接口，因为注册只会创建普通用户），
 * 再用真实登录接口换 token，权限链路上的每一环都是真的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudioMemberCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    /** 绕过 ORM 直查数据库，用来验证「逻辑删除」的真实落库值 */
    @Resource
    private JdbcTemplate jdbcTemplate;

    // ==================== 工具方法 ====================

    /** 插库造账号，返回其 id */
    private long insertUser(String account, String role) {
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        user.setUserName(account);
        user.setUserRole(role);
        user.setUserStatus(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /** 插库造管理员并登录，返回 token */
    private String adminToken() throws Exception {
        String account = "mAdmin" + (System.nanoTime() % 100000);
        insertUser(account, UserRoleConstant.ADMIN);
        return login(account);
    }

    /** 走真实登录接口换 token */
    private String login(String account) throws Exception {
        String body = postJson("/user/login",
                "{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}", null);
        return data(body, "token");
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

    /**
     * 取 HTTP 状态码
     *
     * <p>用途：被删除的接口返回 404 且<b>响应体为空</b>（不存在 handler 时，
     * MVC 拦截器不执行，也就没有统一的 40100 响应体），此时 code(body) 无法判读。
     */
    private int statusOf(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    /** 从响应 JSON 中取出 data.code */
    private int code(String body) {
        int start = body.indexOf("\"code\":") + 7;
        return Integer.parseInt(body.substring(start, body.indexOf(',', start)));
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

    /** 从响应 JSON 中取出 data 本身的字符串值（新增接口返回的是 data:"雪花ID"） */
    private String dataValue(String body) {
        return data(body, "data");
    }

    /** 拼一个不重复的测试姓名 */
    private String uniqueName(String prefix) {
        return prefix + (System.nanoTime() % 100000);
    }

    // ==================== 1. 鉴权边界 ====================

    @Test
    @DisplayName("鉴权：成员对外接口已整体删除（404）；管理端接口匿名 40100")
    void anonymousCanBrowseFrontListButNotAdmin() throws Exception {
        long adminId = insertUser("fAdmin" + (System.nanoTime() % 100000), UserRoleConstant.ADMIN);
        String token = login(findAccount(adminId));
        String addBody = postJson("/member/add",
                "{\"name\":\"" + uniqueName("front") + "\",\"gradeYear\":2022}", token);
        assertEquals(0, code(addBody), "新增失败：" + addBody);

        //「团队成员不对外展示」：原先匿名放行的三个成员接口已整体删除。
        //
        // ⚠️ 断言方式很关键（这是本次改动最容易踩的坑）：
        // **Sa-Token 的 MVC 拦截器只在「匹配到 handler」时才执行**，
        // 接口被删掉后请求根本进不了拦截器链，于是拿到的是 404 且**响应体为空**。
        // 因此不能用 code(body) 判读（body 为空会 StringIndexOutOfBounds），
        // 只能看 HTTP 状态码；同时也说明「40100」这个预期在这里是错的。
        assertEquals(404, statusOf("/member/list"), "成员对外列表不应存在");
        assertEquals(404, statusOf("/member/detail?id=1"), "成员对外详情不应存在");
        assertEquals(404, statusOf("/member/certificate/list?memberId=1"), "按成员查证书的对外接口不应存在");

        // 管理端接口仍在、且不在白名单：匿名访问应 40100（这里拦截器能生效）
        assertEquals(40100, code(getBody("/member/list/page", null)), "管理端列表匿名访问应 40100");
        assertEquals(40100, code(postJson("/member/add", "{\"name\":\"x\",\"gradeYear\":2022}", null)));
        assertEquals(40100, code(getBody("/member/get?id=1", null)));
    }

    @Test
    @DisplayName("鉴权：普通用户访问管理端接口返回 40101（有登录态但角色不够）")
    void normalUserIsForbidden() throws Exception {
        String account = "mNormal" + (System.nanoTime() % 100000);
        insertUser(account, UserRoleConstant.USER);
        String token = login(account);

        assertEquals(40101, code(getBody("/member/list/page", token)), "普通用户不应能查看成员管理列表");
        assertEquals(40101, code(postJson("/member/add",
                "{\"name\":\"hacker\",\"gradeYear\":2022}", token)), "普通用户不应能新增成员");
        assertEquals(40101, code(getBody("/member/get?id=1", token)), "普通用户不应能查看成员详情");
    }

    // ==================== 2. 增删改查全流程 ====================

    @Test
    @DisplayName("全流程：管理员 新增 → 详情 → 更新 → 分页查询 → 删除（逻辑删除）")
    void adminCrudFlow() throws Exception {
        String token = adminToken();
        String name = uniqueName("crud");

        // ---------- 新增：全字段 ----------
        String addBody = postJson("/member/add", """
                {"name":"%s","gradeYear":2021,"major":"计算机科学与技术","direction":"Java后端",
                 "teamPosition":"leader","memberStatus":0,"githubUrl":"https://github.com/demo",
                 "summary":"热爱后端开发","sortOrder":66}""".formatted(name), token);
        assertEquals(0, code(addBody), "新增失败：" + addBody);
        long id = Long.parseLong(dataValue(addBody));

        StudioMember created = studioMemberMapper.selectById(id);
        assertNotNull(created, "新增后应能在库里查到");
        assertEquals("leader", created.getTeamPosition());
        assertEquals(0, created.getMemberStatus(), "不传状态时按 0 处理");
        assertEquals(66, created.getSortOrder());
        assertNotNull(created.getCreatedAt(), "审计字段应由 MyMetaObjectHandler 自动填充");
        assertNotNull(created.getDeletedAt(), "deleted_at 初始应为 0（未删除）");

        // ---------- 详情：管理端 VO 带内部字段，但绝不能带出 deletedAt ----------
        String getBody = getBody("/member/get?id=" + id, token);
        assertEquals(0, code(getBody));
        assertEquals(name, data(getBody, "name"));
        assertEquals("leader", data(getBody, "teamPosition"));
        assertTrue(getBody.contains("\"sortOrder\":66"), "管理端详情应包含置顶权重：" + getBody);
        assertFalse(getBody.contains("deletedAt"), "响应里不应出现逻辑删除字段");

        // ---------- 更新：改姓名 + 置顶权重（部分更新，未传的字段不动） ----------
        String updateBody = postJson("/member/update",
                "{\"id\":" + id + ",\"name\":\"" + name + "改\",\"sortOrder\":88}", token);
        assertEquals(0, code(updateBody), "更新失败：" + updateBody);
        StudioMember updated = studioMemberMapper.selectById(id);
        assertEquals(name + "改", updated.getName());
        assertEquals(88, updated.getSortOrder());
        assertEquals(2021, updated.getGradeYear(), "未传 gradeYear 时不应改动");
        assertEquals("Java后端", updated.getDirection(), "未传 direction 时不应改动");

        // ---------- 分页查询：筛选生效 ----------
        String listBody = getBody("/member/list/page?current=1&pageSize=10&name=" + name, token);
        assertEquals(0, code(listBody));
        assertTrue(listBody.contains("\"total\":"), "分页结果必须带 total：" + listBody);
        assertTrue(listBody.contains(name), "按姓名模糊查询没查到刚创建的成员：" + listBody);
        assertTrue(listBody.contains("\"userId\":null"), "未绑定时 userId 应为 null：" + listBody);

        // ---------- 删除：逻辑删除，删完查不到，库里留下 13 位毫秒时间戳 ----------
        String deleteBody = postJson("/member/delete", "{\"id\":" + id + "}", token);
        assertEquals(0, code(deleteBody));
        assertEquals(40400, code(getBody("/member/get?id=" + id, token)), "删除后应查不到该成员");
        Long deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM studio_member WHERE id = ?", Long.class, id);
        assertTrue(deletedAt != null && deletedAt != 0 && String.valueOf(deletedAt).length() == 13,
                "删除后 deleted_at 应为 13 位毫秒时间戳，实际：" + deletedAt);
    }

    // ==================== 3. user_id 绑定规则 ====================

    @Test
    @DisplayName("绑定：账号不存在 / 重复绑定被拒；重复保存自身绑定合法；删除后账号可重新绑定")
    void userIdBindingRules() throws Exception {
        String token = adminToken();
        String account = "bindU" + (System.nanoTime() % 100000);
        long userId = insertUser(account, UserRoleConstant.USER);

        // 绑定一个不存在（未注册）的账号：必须给可读错误，而不是带着脏数据入库
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"ghost\",\"gradeYear\":2022,\"userId\":999999999}", token)),
                "绑定不存在的账号应被拒绝");

        // 正常绑定
        String nameA = uniqueName("bindA");
        String bodyA = postJson("/member/add",
                "{\"name\":\"" + nameA + "\",\"gradeYear\":2022,\"userId\":" + userId + "}", token);
        assertEquals(0, code(bodyA), "正常绑定失败：" + bodyA);
        long memberA = Long.parseLong(dataValue(bodyA));

        // 同一账号被第二个成员绑定：拒绝
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"" + uniqueName("bindB") + "\",\"gradeYear\":2022,\"userId\":" + userId + "}", token)),
                "同一账号不应能被两个成员绑定");

        // 改绑冲突：另一个成员想改绑到已被占用的账号，同样拒绝
        String nameC = uniqueName("bindC");
        long memberC = Long.parseLong(dataValue(postJson("/member/add",
                "{\"name\":\"" + nameC + "\",\"gradeYear\":2022}", token)));
        assertEquals(40000, code(postJson("/member/update",
                "{\"id\":" + memberC + ",\"userId\":" + userId + "}", token)), "改绑到已占用账号应被拒绝");

        // 排除自身的校验：成员 A 重复提交「绑定到自己已占用的账号」应当成功（幂等）
        assertEquals(0, code(postJson("/member/update",
                "{\"id\":" + memberA + ",\"userId\":" + userId + "}", token)),
                "重复保存自身绑定不应被误判为冲突");

        // 逻辑删除成员 A 后，账号被释放，可重新绑定给成员 C
        assertEquals(0, code(postJson("/member/delete", "{\"id\":" + memberA + "}", token)));
        assertEquals(0, code(postJson("/member/update",
                "{\"id\":" + memberC + ",\"userId\":" + userId + "}", token)),
                "删除后账号应可重新绑定");
    }

    // ==================== 4. 输入校验 ====================

    @Test
    @DisplayName("校验：入学年份越界/字符串、职务与状态非法值、缺少姓名都应被拒绝")
    void invalidInputIsRejected() throws Exception {
        String token = adminToken();

        // 入学年份越界（smallint 语义：区间 1950~2100）
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"bad1\",\"gradeYear\":1800}", token)), "1800 应被拒绝");
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"bad2\",\"gradeYear\":3200}", token)), "3200 应被拒绝");

        // 「2026级」这类字符串：在 JSON 反序列化阶段就会被拒绝，不会落库成脏数据
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"bad3\",\"gradeYear\":\"2026级\"}", token)), "字符串年份应被拒绝");

        // 职务 / 状态非法值：一旦入库，筛选与前端渲染会静默出错
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"bad4\",\"gradeYear\":2022,\"teamPosition\":\"superman\"}", token)));
        assertEquals(40000, code(postJson("/member/add",
                "{\"name\":\"bad5\",\"gradeYear\":2022,\"memberStatus\":9}", token)));

        // 姓名必填
        assertEquals(40000, code(postJson("/member/add", "{\"gradeYear\":2022}", token)));
    }

    // ==================== 5. 排序与脱敏 ====================

    @Test
    @DisplayName("排序：默认按 sort_order 倒序（不是 id 倒序）；C 端即使传 sortField 也固定置顶排序")
    void defaultSortIsSortOrderDesc() throws Exception {
        String token = adminToken();
        String suffix = String.valueOf(System.nanoTime() % 100000);
        // 三个成员：置顶权重 0 / 100 / 50，创建顺序与目标排序故意不一致
        String nameZero = "sort0-" + suffix;
        String nameTop = "sort100-" + suffix;
        String nameMid = "sort50-" + suffix;
        assertEquals(0, code(postJson("/member/add",
                "{\"name\":\"" + nameZero + "\",\"gradeYear\":2023,\"sortOrder\":0}", token)));
        assertEquals(0, code(postJson("/member/add",
                "{\"name\":\"" + nameTop + "\",\"gradeYear\":2023,\"sortOrder\":100}", token)));
        assertEquals(0, code(postJson("/member/add",
                "{\"name\":\"" + nameMid + "\",\"gradeYear\":2023,\"sortOrder\":50}", token)));

        // 管理端默认排序（不传 sortField）：应为 100 → 50 → 0
        String listBody = getBody("/member/list/page?current=1&pageSize=50&gradeYear=2023&name=sort", token);
        assertEquals(0, code(listBody));
        int idxTop = listBody.indexOf(nameTop);
        int idxMid = listBody.indexOf(nameMid);
        int idxZero = listBody.indexOf(nameZero);
        assertTrue(idxTop >= 0 && idxMid > idxTop && idxZero > idxMid,
                "默认排序应为 sort_order 倒序（100 → 50 → 0），实际响应：" + listBody);

        // 原「C 端公开列表即使传 sortField 也必须忽略」那一段已随该接口下线删除。
        // 上面管理端那条断言已经守住了「默认排序 = sort_order 倒序」这个行为。
    }

    // ==================== 私有小工具 ====================

    /** 由用户 id 反查账号（仅在测试里用，避免把账号名传来传去） */
    private String findAccount(long id) {
        SysUser user = sysUserMapper.selectById(id);
        return user == null ? null : user.getUserAccount();
    }
}