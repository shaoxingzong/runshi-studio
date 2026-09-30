package com.bhu.runshistudioweb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.UserRoleEnum;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 角色取值与默认角色验收测试
 *
 * author: shaoshing
 *
 * <p>把「角色取值 = user / member / admin」这条约定**钉在测试里**：
 * 它同时存在于 {@code db/user.sql} 的列注释与默认值、{@code db/DESIGN.md}、
 * 以及 Java 常量三处，任何一处改了而其他两处没跟上，都会静默变成
 * 「明明给了角色却一直 40101」。这里改一处、跑一次，就能发现不一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserRoleTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    private SysUser selectByAccount(String account) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUserAccount, account);
        return sysUserMapper.selectOne(wrapper);
    }

    private String postJson(String path, String json, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("常量与枚举必须一致：三档角色都能被 UserRoleEnum 解析，且描述非空")
    void constantsMatchEnum() {
        assertSame(UserRoleEnum.USER, UserRoleEnum.of(UserRoleConstant.USER),
                "常量 USER 与枚举对不上，说明改了其中一处忘了另一处");
        assertSame(UserRoleEnum.MEMBER, UserRoleEnum.of(UserRoleConstant.MEMBER));
        assertSame(UserRoleEnum.ADMIN, UserRoleEnum.of(UserRoleConstant.ADMIN));

        for (UserRoleEnum role : UserRoleEnum.values()) {
            assertNotNull(role.getDesc(), role + " 缺少中文描述，错误提示与日志会不可读");
        }
        assertEquals("user / member / admin", UserRoleEnum.valuesText(),
                "合法取值的提示文案变了，请同步检查 DDL 注释、DESIGN.md、前端与文档");
    }

    @Test
    @DisplayName("注册默认角色：新注册用户必须是 user（最低权限），绝不能默认给 member 或 admin")
    void registerDefaultsToUser() throws Exception {
        String account = "role_reg_" + (System.nanoTime() % 100000);

        String body = postJson("/user/register", "{\"userAccount\":\"" + account
                + "\",\"userPassword\":\"Studio@2026\",\"checkPassword\":\"Studio@2026\"}", null);
        assertTrue(body.contains("\"code\":0"), "注册失败：" + body);

        // 直接查库断言：接口返回体里可能压根不带角色，只有查库才能证明真实落库值
        SysUser user = selectByAccount(account);
        assertNotNull(user, "注册后查不到用户");
        assertEquals(UserRoleConstant.USER, user.getUserRole(),
                "注册默认角色必须是 user——默认给 member/admin 等于人人可管成员档案");
    }

    @Test
    @DisplayName("管理端新增：不传角色时同样按最低权限 user 处理")
    void adminAddDefaultsToUser() throws Exception {
        String adminAccount = "role_adm_" + (System.nanoTime() % 100000);
        SysUser admin = new SysUser();
        admin.setUserAccount(adminAccount);
        admin.setUserPassword(PasswordUtils.encrypt("Studio@2026"));
        admin.setUserName(adminAccount);
        admin.setUserRole(UserRoleConstant.ADMIN);
        admin.setUserStatus(0);
        sysUserMapper.insert(admin);

        String loginBody = postJson("/user/login", "{\"userAccount\":\"" + adminAccount
                + "\",\"userPassword\":\"Studio@2026\"}", null);
        String token = loginBody.substring(loginBody.indexOf("\"token\":\"") + 9);
        token = token.substring(0, token.indexOf('"'));

        String newAccount = "role_add_" + (System.nanoTime() % 100000);
        String addBody = postJson("/user/add", "{\"userAccount\":\"" + newAccount + "\"}", token);
        assertTrue(addBody.contains("\"code\":0"), "新增失败：" + addBody);

        assertEquals(UserRoleConstant.USER, selectByAccount(newAccount).getUserRole(),
                "管理员不指定角色时应落到最低权限，而不是随便给一个高权限");
    }
}
