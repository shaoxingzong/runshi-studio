package com.bhu.runshistudioweb.utils;

import com.bhu.runshistudioweb.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码工具类验收测试
 */
class PasswordUtilsTest {

    private static final String RAW = "Studio@2026";

    @Test
    @DisplayName("密文格式：60 字符、以 $2a$/$2b$ 开头、cost 为 10")
    void hashFormat() {
        String hash = PasswordUtils.encrypt(RAW);

        assertEquals(60, hash.length(), "BCrypt 密文长度应为 60，实际：" + hash.length());
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"),
                "密文前缀不是 BCrypt 标识：" + hash);
        assertEquals(10, Integer.parseInt(hash.substring(4, 6)), "cost 因子不是 10");
        assertTrue(hash.length() <= 128, "密文超出 user_password varchar(128) 列宽");
    }

    @Test
    @DisplayName("自加盐：同一明文两次加密结果不同，但两次都能校验通过")
    void saltIsRandomButBothMatch() {
        String first = PasswordUtils.encrypt(RAW);
        String second = PasswordUtils.encrypt(RAW);

        assertNotEquals(first, second, "两次加密结果相同 —— 说明盐不是随机的，彩虹表可复用");

        assertTrue(PasswordUtils.matches(RAW, first), "第一次加密的密文校验失败");
        assertTrue(PasswordUtils.matches(RAW, second), "第二次加密的密文校验失败");
    }

    @Test
    @DisplayName("校验正确性：错误密码必须返回 false")
    void wrongPasswordReturnsFalse() {
        String hash = PasswordUtils.encrypt(RAW);

        assertFalse(PasswordUtils.matches("studio@2026", hash), "大小写不同却通过了校验");
        assertFalse(PasswordUtils.matches(RAW + "x", hash), "多一个字符却通过了校验");
        assertFalse(PasswordUtils.matches("", hash), "空密码却通过了校验");
    }

    @Test
    @DisplayName("健壮性：null / 空串 / 非法密文一律返回 false，且绝不抛异常")
    void matchesNeverThrows() {
        String validHash = PasswordUtils.encrypt(RAW);

        // 各类非法哈希串（含历史遗留的 MD5 值、截断的 BCrypt、随机字符串）
        String[] invalidHashes = {
                null,
                "",
                "   ",
                "abc",
                "e10adc3949ba59abbe56e057f20f883e",
                validHash.substring(0, 30),
                "$2a$10$not-a-real-bcrypt-hash-string-value",
                "中文密文"
        };

        for (String invalid : invalidHashes) {
            assertDoesNotThrow(() -> PasswordUtils.matches(RAW, invalid),
                    "非法密文导致抛异常，登录接口会返回 500：" + invalid);
            assertFalse(PasswordUtils.matches(RAW, invalid),
                    "非法密文却返回了 true：" + invalid);
        }

        assertDoesNotThrow(() -> PasswordUtils.matches(null, validHash));
        assertDoesNotThrow(() -> PasswordUtils.matches("", validHash));
        assertFalse(PasswordUtils.matches(null, validHash));
        assertFalse(PasswordUtils.matches("", validHash));
    }

    @Test
    @DisplayName("加密入参保护：只拦空值；密码强度（含长度）属于业务层，工具类不做限制")
    void encryptRejectsBlankInput() {
        assertThrows(BusinessException.class, () -> PasswordUtils.encrypt(null));
        assertThrows(BusinessException.class, () -> PasswordUtils.encrypt(""));
        assertThrows(BusinessException.class, () -> PasswordUtils.encrypt("   "));

        // 长度校验已按设计移出工具类（改由接口层 DTO 的 @Size 负责），
        // 这里断言「不抛异常」，把这条职责边界钉在测试里，防止以后被重新加回来
        assertDoesNotThrow(() -> PasswordUtils.encrypt("12345"));
        assertDoesNotThrow(() -> PasswordUtils.encrypt("123456"));
    }

    @Test
    @DisplayName("哈希串不可逆：密文中不得包含明文片段")
    void hashDoesNotLeakPlaintext() {
        String hash = PasswordUtils.encrypt(RAW);

        assertFalse(hash.contains(RAW), "密文中出现了明文");
        assertFalse(hash.toLowerCase().contains("studio"), "密文中出现了明文片段");
    }
}