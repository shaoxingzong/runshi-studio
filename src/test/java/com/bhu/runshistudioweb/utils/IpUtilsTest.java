package com.bhu.runshistudioweb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 工具类验收测试（纯单测：不启 Spring、不连数据库、无外部依赖）
 *
 * author: shaoshing
 *
 * <p>本类守的是<b>「同一个地址必须归一成同一个字符串」</b>这条底线。
 * 它一旦破，表现是「明明在工作室却被记为外网」，而且不报任何错——
 * 所以每个归一化分支都必须有对应用例，不能只测 happy path。
 */
class IpUtilsTest {

    // ==================== 归一化 ====================

    @Test
    @DisplayName("IPv4 原样输出")
    void normalizeIpv4() {
        assertEquals("192.168.1.5", IpUtils.normalize("192.168.1.5"));
    }

    @Test
    @DisplayName("剥离 IPv4 端口后缀（容器与部分日志格式会带端口）")
    void stripIpv4Port() {
        assertEquals("192.168.1.5", IpUtils.normalize("192.168.1.5:54321"));
    }

    @Test
    @DisplayName("剥离 IPv6 方括号端口：[::1]:8080 → ::1")
    void stripIpv6BracketPort() {
        assertEquals("::1", IpUtils.normalize("[::1]:8080"));
    }

    @Test
    @DisplayName("IPv6 本体含多个冒号时不能被误当成端口切掉")
    void ipv6BodyNotMistakenAsPort() {
        assertEquals("2001:db8::1", IpUtils.normalize("2001:db8::1"));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 降级为 IPv4（双栈监听下能否匹配网段的关键）")
    void ipv4MappedDowngrade() {
        assertEquals("192.168.1.5", IpUtils.normalize("::ffff:192.168.1.5"));
    }

    @Test
    @DisplayName("IPv6 环回完整写法归一为压缩形式：0:0:0:0:0:0:0:1 → ::1")
    void ipv6LoopbackCompress() {
        assertEquals("::1", IpUtils.normalize("0:0:0:0:0:0:0:1"));
    }

    @Test
    @DisplayName("零段在开头：必须输出两个冒号，否则产物无法再被解析")
    void leadingCompression() {
        assertEquals("::1", IpUtils.normalize("::1"),
                "归一化结果不是合法 IPv6 —— 后续判可信代理时会解析失败，且静默降级为不可信");
    }

    @Test
    @DisplayName("零段在末尾：2001:db8:: 必须保留结尾双冒号")
    void trailingCompression() {
        assertEquals("2001:db8::", IpUtils.normalize("2001:db8:0:0:0:0:0:0"));
    }

    @Test
    @DisplayName("前导零与大写归一：2001:0DB8:0000::0001 → 2001:db8::1")
    void normalizeCaseAndLeadingZeros() {
        assertEquals("2001:db8::1", IpUtils.normalize("2001:0DB8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    @DisplayName("前导零按十进制解析，不做八进制解释（010 → 10 而不是 8）")
    void leadingZeroIsDecimal() {
        assertEquals("10.1.1.1", IpUtils.normalize("010.1.1.1"));
    }

    @Test
    @DisplayName("非法输入一律返回 null 而不是抛异常（调用方按 null 兜底）")
    void invalidInputs() {
        assertNull(IpUtils.normalize(null));
        assertNull(IpUtils.normalize(""));
        assertNull(IpUtils.normalize("   "));
        assertNull(IpUtils.normalize("abc.example.com"));
        assertNull(IpUtils.normalize("999.999.999.999"));
        assertNull(IpUtils.normalize("192.168.1"));
        assertFalse(IpUtils.isValid("abc"));
    }

    @Test
    @DisplayName("拒绝十六进制等有歧义的写法：0x10.1.1.1 非法")
    void rejectAmbiguousHex() {
        assertNull(IpUtils.normalize("0x10.1.1.1"));
    }

    // ==================== CIDR ====================

    @Test
    @DisplayName("网段内命中、网段外不命中")
    void cidrBasic() {
        assertTrue(IpUtils.isInAnyCidr("192.168.1.5", List.of("192.168.1.0/24")));
        assertFalse(IpUtils.isInAnyCidr("192.168.2.5", List.of("192.168.1.0/24")));
    }

    @Test
    @DisplayName("多个网段命中其一即可")
    void anyCidr() {
        assertTrue(IpUtils.isInAnyCidr("10.0.0.7", List.of("192.168.1.0/24", "10.0.0.0/8")));
    }

    @Test
    @DisplayName("主机位不规整的写法被接受：192.168.1.5/24 等价于 192.168.1.0/24")
    void hostBitsIgnored() {
        assertTrue(IpUtils.isInAnyCidr("192.168.1.200", List.of("192.168.1.5/24")));
    }

    @Test
    @DisplayName("裸 IP 视为满位长（/32），仅精确匹配")
    void bareIpIsFullPrefix() {
        assertTrue(IpUtils.isInAnyCidr("10.0.0.7", List.of("10.0.0.7")));
        assertFalse(IpUtils.isInAnyCidr("10.0.0.8", List.of("10.0.0.7")));
    }

    @Test
    @DisplayName("归一化先于匹配：带端口 / IPv4-mapped 都能命中 IPv4 网段")
    void normalizedMatchesCidr() {
        assertTrue(IpUtils.isInAnyCidr("192.168.1.5:8080", List.of("192.168.1.0/24")));
        assertTrue(IpUtils.isInAnyCidr("::ffff:192.168.1.5", List.of("192.168.1.0/24")));
    }

    @Test
    @DisplayName("IPv6 网段匹配 IPv6 地址")
    void ipv6Cidr() {
        assertTrue(IpUtils.isInAnyCidr("2001:db8::1", List.of("2001:db8::/32")));
        assertFalse(IpUtils.isInAnyCidr("2001:dead::1", List.of("2001:db8::/32")));
    }

    @Test
    @DisplayName("地址族不同一律不匹配（IPv4 不能被 IPv6 网段命中，反之亦然）")
    void familyMismatch() {
        assertFalse(IpUtils.isInAnyCidr("192.168.1.5", List.of("2001:db8::/32")));
        assertFalse(IpUtils.isInAnyCidr("::1", List.of("192.168.1.0/24")));
    }

    @Test
    @DisplayName("非法网段被拒绝而不是静默生效：/33、/abc、空前缀")
    void invalidCidr() {
        assertNull(IpUtils.parseCidr("192.168.1.0/33"));
        assertNull(IpUtils.parseCidr("192.168.1.0/abc"));
        assertNull(IpUtils.parseCidr("192.168.1.0/"));
        assertNull(IpUtils.parseCidr("not-an-ip/24"));
    }

    @Test
    @DisplayName("空网段列表 / 非法 IP 一律判为不匹配（安全侧默认）")
    void emptyOrInvalidNeverMatches() {
        assertFalse(IpUtils.isInAnyCidr("192.168.1.5", List.of()));
        assertFalse(IpUtils.isInAnyCidr("192.168.1.5", null));
        assertFalse(IpUtils.isInAnyCidr("abc", List.of("192.168.1.0/24")));
    }
}
