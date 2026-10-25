package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.utils.PasswordUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 账号级登录失败锁定 · 端到端验收
 *
 * 为什么需要端到端而不仅是纯单测：锁定逻辑的正确性一半在 AuthLockManager，
 * 另一半在 UserServiceImpl.userLogin 的接线——assertNotLocked 有没有在查库前调用、
 * recordFailure 有没有只在「账号存在」时触发、clear 有没有在成功时调用。
 * 这些纯单测覆盖不到，必须走真实登录链路。
 *
 * 类名必须是 Auth 开头（排序约束，见 AiKnowledgeIngestTest 类注释）：
 * 本类用 @DynamicPropertySource 开锁，会新建 Spring 上下文；
 * 而 Sa-Token 用静态 SaManager 持有 Redis DAO，永远绑定「最后创建的上下文」。
 * 新建上下文的类必须全部排在 CertificateCrudTest（第一个用默认上下文的类）之前，
 * 否则后续登录类测试会集体 B0001 / A0201。Auth < Certificate，排位正确。
 *
 * 为什么关掉 IP 限流：本类每个用例要连续登录 6 次以上，
 * 不关的话会先撞 IP 限流（10 次/分钟），锁定的断言就失去意义了。
 * 限流本身由 IpRateLimitManagerTest 覆盖，两者不互相干扰。
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthLoginLockTest {

    private static final String PASSWORD = "Studio@2026";

    /** 锁定计数键前缀，与 AuthLockManager 内部保持一致（清理用） */
    private static final String LOCK_KEY_PREFIX = "studio:auth:lock:";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    /** 直接操作 Redis 清理锁定键：@Transactional 回滚不了外部系统 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String account;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("studio.auth.lock.enabled", () -> true);
        registry.add("studio.auth.rate-limit.enabled", () -> false);
    }

    /** 每个用例造一个全新账号：锁键按账号计数，账号唯一即互不污染 */
    private void freshUser() {
        account = "lock" + (System.nanoTime() % 100000000);
        SysUser user = new SysUser();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(PASSWORD));
        user.setUserName(account);
        user.setUserRole("user");
        user.setUserStatus(0);
        sysUserMapper.insert(user);
    }

    @AfterEach
    void cleanLockKey() {
        if (account != null) {
            stringRedisTemplate.delete(LOCK_KEY_PREFIX + account);
        }
    }

    private String login(String userAccount, String password) throws Exception {
        String json = "{\"userAccount\":\"" + userAccount + "\",\"userPassword\":\"" + password + "\"}";
        return mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
    }

    private String code(String body) {
        int start = body.indexOf("\"code\":\"") + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private String message(String body) {
        int start = body.indexOf("\"message\":\"") + 11;
        if (start < 10) {
            return null;
        }
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    @DisplayName("连续 5 次密码错误后，第 6 次连正确密码也被锁（A0501）")
    void fiveFailures_thenEvenCorrectPasswordIsLocked() throws Exception {
        freshUser();

        // 前 5 次错密码：每次都得到统一的「账号或密码错误」
        for (int i = 0; i < 5; i++) {
            String body = login(account, "Wrong" + i + "Pass");
            assertEquals("A0401", code(body), "第 " + (i + 1) + " 次错密码应被正常拒绝");
            assertEquals("账号或密码错误", message(body), "提示必须统一，不泄露是账号错还是密码错");
        }

        // 第 6 次：密码完全正确，但账号已在锁定窗口内——锁定的意义正在于此
        String locked = login(account, PASSWORD);
        assertEquals("A0501", code(locked), "达到阈值后，正确密码也必须被锁挡住：" + locked);
        assertEquals("登录失败次数过多，账号已锁定，请 15 分钟后再试", message(locked),
                "锁定提示必须带时长，用户才知道要等多久");
    }

    @Test
    @DisplayName("锁定窗口过期（键被清）后恢复可登录")
    void afterWindowExpiry_canLoginAgain() throws Exception {
        freshUser();

        for (int i = 0; i < 5; i++) {
            login(account, "Wrong" + i + "Pass");
        }
        assertEquals("A0501", code(login(account, PASSWORD)), "先确认已锁");

        // 模拟 15 分钟窗口过期：真实场景里 key 靠 TTL 自动消失，这里直接删
        stringRedisTemplate.delete(LOCK_KEY_PREFIX + account);

        assertEquals("00000", code(login(account, PASSWORD)),
                "窗口过期后应恢复可登录——固定窗口（而非每次失败续期）保证最坏 15 分钟自愈");
    }

    @Test
    @DisplayName("登录成功清零：错 4 次再登录成功，失败计数一笔勾销")
    void successClearsFailureCount() throws Exception {
        freshUser();

        // 错 4 次（差 1 次到阈值）
        for (int i = 0; i < 4; i++) {
            login(account, "Wrong" + i + "Pass");
        }
        // 第 5 次用正确密码：成功，且应清零此前的 4 次失败
        assertEquals("00000", code(login(account, PASSWORD)), "未达阈值时应能正常登录");

        // 验证清零真的生效：再错 4 次仍不应被锁（若没清零，4+4 已超阈值）
        for (int i = 0; i < 4; i++) {
            assertEquals("A0401", code(login(account, "Wrong" + i + "Pass")),
                    "第 " + (i + 1) + " 次再失败应仍未锁——证明成功后的清零生效了");
        }
        assertEquals("00000", code(login(account, PASSWORD)), "最终仍能正常登录");
    }
}
