package com.bhu.runshistudioweb.mapper;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.model.entity.SysUser;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SysUserMapper 验收测试
 *
 * author: shaoshing
 *
 * <p>这是**真连数据库**的集成测试，验证的是「框架配置是否真的生效」，而不是业务逻辑：
 * <ul>
 *     <li>主键是否回填（雪花 ID）、审计字段是否被自动填充 —— 对应 ADR-2；</li>
 *     <li>逻辑删除是否生效、落库值是否为毫秒时间戳 —— 对应 ADR-1、ADR-7。</li>
 * </ul>
 *
 * <p>注意：测试数据必须保证唯一（账号后缀用 {@code System.nanoTime()}），
 * 否则 (user_account, deleted_at) 唯一索引会让第二次运行直接失败。
 */
@SpringBootTest
class SysUserMapperTest {

    // 用 @Resource 而不是 @Autowired：按名称注入，报错信息更直观（MyBatis 官方示例也这么写）
    @Resource
    private SysUserMapper sysUserMapper;

    // 直接查库校验真实落库值：有些行为（如逻辑删除到底写入了什么）必须绕过 ORM 才能看清
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 构造测试用户：这里只填必填字段，审计字段（createdAt 等）故意不填，
     * 因为要验证的就是「不填也会被自动填充」
     *
     * @param account 登录账号，调用方负责保证唯一
     * @return 待插入的实体
     */
    private SysUser newUser(String account) {
        SysUser user = new SysUser();
        user.setUserAccount(account);
        // 只占位即可：本测试不校验密码强度，避免引入 BCrypt 依赖与耗时
        user.setUserPassword("$2a$10$placeholderpasswordhashplaceholderpasswordhashplacehold");
        user.setUserName("测试用户");
        // 用常量而不是写死字符串：角色取值只在 UserRoleConstant 里定义一处
        user.setUserRole(UserRoleConstant.USER);
        user.setUserStatus(0);
        user.setAiQueryCount(0);
        return user;
    }

    @Test
    @Transactional
    @DisplayName("插入：主键应回填，4 个审计字段应被自动填充")
    void insertShouldFillAuditFields() {
        // 账号加时间戳后缀，避免与上一次运行残留的数据撞唯一索引
        SysUser user = newUser("it_" + System.nanoTime());

        int rows = sysUserMapper.insert(user);

        // 影响行数校验不能省：有些失败表现为「返回 0 但不抛异常」
        assertEquals(1, rows);
        // 雪花 ID 由 MP 生成后回填到实体，接口才能立刻拿到 ID 返回给前端
        assertNotNull(user.getId(), "主键未回填");
        // 这 4 个字段在 DDL 中是 NOT NULL 且无 DB 默认值：没被填充就说明 MetaObjectHandler 失效了
        assertNotNull(user.getCreatedAt(), "createdAt 未被 MetaObjectHandler 填充");
        assertNotNull(user.getUpdatedAt(), "updatedAt 未被 MetaObjectHandler 填充");
        assertEquals(0L, user.getCreatedBy(), "createdBy 未被填充");
        assertEquals(0L, user.getUpdatedBy(), "updatedBy 未被填充");
    }

    @Test
    @Transactional
    @DisplayName("逻辑删除：deleteById 后 selectById 应查不到该记录")
    void logicalDeleteShouldHideRow() {
        SysUser user = newUser("ld_" + System.nanoTime());
        sysUserMapper.insert(user);
        Long id = user.getId();
        assertNotNull(id);

        // deleteById 实际发出的是 UPDATE sys_user SET deleted_at = ...（不是物理 DELETE）
        sysUserMapper.deleteById(id);

        // MP 会自动给查询追加 deleted_at = 0，所以这里必须查不到
        assertNull(sysUserMapper.selectById(id), "逻辑删除后仍能查到记录");
    }

    @Test
    @DisplayName("逻辑删除落库值：deleted_at 必须是 13 位毫秒时间戳，而非固定值 1")
    void logicalDeleteWritesMillisecondTimestamp() {
        SysUser user = newUser("ts_" + System.nanoTime());
        sysUserMapper.insert(user);
        Long id = user.getId();
        assertNotNull(id);

        try {
            sysUserMapper.deleteById(id);

            // 绕过 ORM 直接查库：只有看真实落库值，才能确认 yml 里的表达式被原样拼进了 SQL
            Long deletedAt = jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM sys_user WHERE id = ?", Long.class, id);

            assertNotNull(deletedAt, "deleted_at 为空");
            assertNotEquals(0L, deletedAt, "deleted_at 仍为 0，逻辑删除未生效");
            assertNotEquals(1L, deletedAt,
                    "deleted_at 被写成固定值 1 —— logic-delete-value 仍是静态值，会导致同账号二次删除撞唯一索引");
            // 长度 13 是毫秒时间戳的特征：秒级只有 10 位，写入秒级会导致极端情况下同秒二次删除冲突
            assertEquals(13, String.valueOf(deletedAt).length(),
                    "deleted_at 不是 13 位毫秒时间戳，实际值：" + deletedAt);
        } finally {
            // 本用例未开启 @Transactional（要验证真实落库且不依赖事务实现），因此必须手工清理，
            // 否则残留数据会污染后续运行（且逻辑删除行仍占用唯一索引）
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", id);
        }
    }
}
