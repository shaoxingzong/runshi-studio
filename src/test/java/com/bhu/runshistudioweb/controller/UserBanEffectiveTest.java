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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 封禁 / 删除的即时生效验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserBanEffectiveTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    // 工具方法（与 UserAdminCrudTest 保持同一套）

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

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private String data(String body, String key) {
        int start = body.indexOf("\"" + key + "\":\"");
        if (start < 0) {
            return null;
        }
        int from = start + key.length() + 4;
        return body.substring(from, body.indexOf('"', from));
    }

    /** 取响应里的 message 字段（断言封禁提示用） */
    private String message(String body) {
        int start = body.indexOf("\"message\":\"") + 11;
        if (start < 10) {
            return null;
        }
        return body.substring(start, body.indexOf('"', start));
    }

    /** 唯一账号名：测试可重复执行，且与其它用例造的数据互不干扰 */
    private String uniqueAccount(String prefix) {
        return prefix + (System.nanoTime() % 100000000);
    }

    // 封禁即时生效

    @Test
    @DisplayName("封禁即时生效：原 token 立即失效（A0201），且登录被明确拒绝")
    void banKicksSessionImmediately() throws Exception {
        String adminAccount = uniqueAccount("banadm_");
        String targetAccount = uniqueAccount("bantgt_");
        insertUser(adminAccount, UserRoleConstant.ADMIN);
        long targetId = insertUser(targetAccount, UserRoleConstant.USER);

        // 目标用户先正常登录，拿到有效 token
        String targetToken = login(targetAccount);
        assertEquals("00000", code(getBody("/user/current", targetToken)), "封禁前当前用户接口应正常");

        // 管理员封禁目标账号
        String adminToken = login(adminAccount);
        String banResult = postJson("/user/update",
                "{\"id\":" + targetId + ",\"userStatus\":1}", adminToken);
        assertEquals("00000", code(banResult), "管理员封禁应成功：" + banResult);

        // 踢会话：原 token 立即失效（不再等到 7 天后过期）
        assertEquals("A0201", code(getBody("/user/current", targetToken)),
                "封禁后原 token 必须立即失效，否则封禁在用户侧毫无效果");
        // 登录入口同样挡住，且提示明确（FORBIDDEN_ERROR = A0302）
        String relogin = postJson("/user/login",
                "{\"userAccount\":\"" + targetAccount + "\",\"userPassword\":\"Studio@2026\"}", null);
        assertEquals("A0302", code(relogin), "封禁账号再次登录应被拒绝");
        assertEquals("账号已被封禁", message(relogin), "拒绝提示应说明原因");
    }

    @Test
    @DisplayName("解封可恢复：封禁不是永久封号，解封后能重新登录")
    void unbanRestoresLogin() throws Exception {
        String adminAccount = uniqueAccount("unbadm_");
        String targetAccount = uniqueAccount("unbtgt_");
        insertUser(adminAccount, UserRoleConstant.ADMIN);
        long targetId = insertUser(targetAccount, UserRoleConstant.USER);

        String adminToken = login(adminAccount);
        postJson("/user/update", "{\"id\":" + targetId + ",\"userStatus\":1}", adminToken);
        assertEquals("A0302", code(postJson("/user/login",
                "{\"userAccount\":\"" + targetAccount + "\",\"userPassword\":\"Studio@2026\"}", null)),
                "封禁期间不应能登录");

        // 解封
        String unbanResult = postJson("/user/update",
                "{\"id\":" + targetId + ",\"userStatus\":0}", adminToken);
        assertEquals("00000", code(unbanResult), "解封应成功：" + unbanResult);

        String newToken = login(targetAccount);
        assertEquals("00000", code(getBody("/user/current", newToken)), "解封后应能重新登录并正常使用");
    }

    @Test
    @DisplayName("删除同样踢会话：逻辑删除后原 token 立即失效")
    void deleteKicksSession() throws Exception {
        String adminAccount = uniqueAccount("deladm_");
        String targetAccount = uniqueAccount("deltgt_");
        insertUser(adminAccount, UserRoleConstant.ADMIN);
        long targetId = insertUser(targetAccount, UserRoleConstant.USER);

        String targetToken = login(targetAccount);
        String adminToken = login(adminAccount);

        String deleteResult = postJson("/user/delete", "{\"id\":" + targetId + "}", adminToken);
        assertEquals("00000", code(deleteResult), "删除应成功：" + deleteResult);

        assertEquals("A0201", code(getBody("/user/current", targetToken)),
                "删除后原 token 必须立即失效（getLoginUser 的 user==null 兜底 + 踢会话双保险）");
    }
}
