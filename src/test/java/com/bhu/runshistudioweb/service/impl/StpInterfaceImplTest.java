package com.bhu.runshistudioweb.service.impl;

import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StpInterface 角色数据源验收测试
 *
 * author: shaoshing
 *
 * <p>插入临时用户验证角色读取，{@code @Transactional} 让每个用例结束后自动回滚，
 * 不污染开发库（角色相关的用例尤其要注意：残留数据会影响其他测试里「账号唯一」的断言）。
 *
 * <p>覆盖的核心是「什么情况下必须拿不到角色」：封禁、已逻辑删除、ID 非法。
 * 这些分支一旦漏判，就等于封禁用户仍然能访问管理员接口。
 */
@SpringBootTest
@Transactional
class StpInterfaceImplTest {

    @Resource
    private StpInterfaceImpl stpInterface;

    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * 造一个测试用户并直接插入（不经过 Service，避免把注册的校验规则耦合进本测试）
     *
     * @param account 账号，调用方负责加随机后缀保证唯一
     * @param role    角色，直接决定断言结果
     * @param status  用户状态：0-正常，1-封禁
     */
    private SysUser newUser(String account, String role, Integer status) {
        SysUser user = new SysUser();
        user.setUserAccount(account);
        // 写死一个合法格式的 BCrypt 哈希串即可：本测试与密码无关，
        // 调 BCrypt 加密一次要几十毫秒，没必要为此拖慢用例
        user.setUserPassword("$2a$10$VYOi4TBr/3q5opBEopv8C.0tTyk/9.AnSlFXx8.MjZvdy5TSBza.K");
        user.setUserName("角色测试");
        user.setUserRole(role);
        user.setUserStatus(status);
        user.setAiQueryCount(0);
        sysUserMapper.insert(user);
        return user;
    }

    @Test
    @DisplayName("正常账号：getRoleList 返回库中的角色值")
    void normalUserGetsRole() {
        SysUser user = newUser("role_n_" + System.nanoTime(), "admin", 0);

        List<String> roles = stpInterface.getRoleList(user.getId(), "login");

        assertEquals(List.of("admin"), roles, "正常管理员账号应返回 admin 角色");
    }

    @Test
    @DisplayName("封禁账号：即使存在，也不授予任何角色（封禁即失效）")
    void bannedUserGetsNoRole() {
        SysUser user = newUser("role_b_" + System.nanoTime(), "admin", 1);

        List<String> roles = stpInterface.getRoleList(user.getId(), "login");

        assertTrue(roles.isEmpty(), "封禁账号不应返回任何角色");
    }

    @Test
    @DisplayName("逻辑删除的账号：查不到即无角色")
    void logicalDeletedUserGetsNoRole() {
        SysUser user = newUser("role_d_" + System.nanoTime(), "member", 0);
        Long id = user.getId();
        sysUserMapper.deleteById(id);

        List<String> roles = stpInterface.getRoleList(id, "login");

        assertTrue(roles.isEmpty(), "逻辑删除的账号不应返回任何角色");
    }

    @Test
    @DisplayName("不存在的 ID / 非 Long 的 loginId：返回空集合而非抛异常")
    void invalidLoginIdGetsNoRole() {
        assertNotNull(stpInterface.getRoleList(999999999999L, "login"));
        assertTrue(stpInterface.getRoleList(999999999999L, "login").isEmpty());

        List<String> strResult = stpInterface.getRoleList("999999999999", "login");
        assertTrue(strResult.isEmpty(), "String 形式的 loginId 不应抛类型转换异常");
    }
}