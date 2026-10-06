package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AttendanceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端 IP 解析与内外网判定验收测试（纯单测：不启 Spring、不连数据库）
 *
 * author: shaoshing
 *
 * <p>本类最重要的用例是<b>「伪造内网签到」</b>那几条：考勤的区分能力完全取决于
 * 「请求头不可信时是否被忽略」。这些用例是安全边界，不是普通的功能校验——
 * 它们要是挂了，说明任何人都能在家里签到成「在工作室」。
 */
class ClientIpManagerTest {

    /**
     * 直接 new 一个 Manager：因它改用构造器注入且不依赖 HTTP，
     * 测一个网段判定不需要 Mock 请求上下文，也就不需要启 Spring
     */
    private static ClientIpManager manager(List<String> lanCidrs, List<String> trustedProxies) {
        AttendanceProperties props = new AttendanceProperties();
        props.setLanCidrs(lanCidrs);
        props.setTrustedProxies(trustedProxies);
        return new ClientIpManager(props);
    }

    // ==================== 信任链（安全边界） ====================

    @Test
    @DisplayName("对端不可信：一律忽略 X-Real-IP 与 XFF，直接用 TCP 对端（防伪造内网签到）")
    void untrustedPeerHeadersIgnored() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        // 攻击者在公网伪造内网头，企图让自己「在工作室签到」
        String ip = manager.resolveClientIp("8.8.8.8", "192.168.1.5", "192.168.1.5");

        assertEquals("8.8.8.8", ip, "采信了不可信来源的请求头 —— 伪造内网签到已成功");
        assertFalse(manager.isInLan(ip));
    }

    @Test
    @DisplayName("对端可信：采信 X-Real-IP")
    void trustedPeerUsesXRealIp() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        String ip = manager.resolveClientIp("127.0.0.1", "192.168.1.5", null);

        assertEquals("192.168.1.5", ip);
        assertTrue(manager.isInLan(ip));
    }

    @Test
    @DisplayName("XFF 从右往左取：客户端伪造的最左段必须被跳过")
    void xffPicksFromRight() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        // Nginx 用 $proxy_add_x_forwarded_for 时是**追加**语义，
        // 客户端自带的伪造段会原样留在最左。真实客户端是 10.0.0.9
        String ip = manager.resolveClientIp("127.0.0.1", null, "192.168.1.5, 10.0.0.9, 127.0.0.1");

        assertEquals("10.0.0.9", ip, "取到了伪造段 —— XFF 扫描方向错了，等于把伪造权交给客户端");
        assertFalse(manager.isInLan(ip));
    }

    @Test
    @DisplayName("XFF 整条都是代理：回退到对端地址（不返回 null，也不采信伪造段）")
    void xffAllProxiesFallback() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        assertEquals("127.0.0.1", manager.resolveClientIp("127.0.0.1", null, "127.0.0.1, 127.0.0.1"));
    }

    @Test
    @DisplayName("XFF 含无法解析的段（unknown / 空）时跳过，不整条失败")
    void xffSkipsUnparsable() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        assertEquals("10.0.0.9", manager.resolveClientIp("127.0.0.1", null, "unknown, 10.0.0.9, , 127.0.0.1"));
    }

    @Test
    @DisplayName("对端地址无法解析时返回 null：不猜测、也不采信请求头")
    void unparsablePeerReturnsNull() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        assertNull(manager.resolveClientIp("not-an-ip", "192.168.1.5", "192.168.1.5"));
    }

    @Test
    @DisplayName("IPv6 环回作为可信代理时，其带来的 X-Real-IP 必须被采信")
    void ipv6LoopbackPeerIsTrusted() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1", "::1"));

        // ::1 若被归一化成非法串（少一个冒号），这里就会退化成「不采信头」，
        // 表现为「Nginx 明明就在本机，真实 IP 却取不到」，且不报任何错
        String ip = manager.resolveClientIp("::1", "192.168.1.5", null);

        assertEquals("192.168.1.5", ip, "::1 未被判为可信代理 —— 归一化后的地址串无法再解析");
        assertTrue(manager.isInLan(ip));
    }

    @Test
    @DisplayName("IPv6 环回的完整写法与压缩写法等价，都应命中可信代理")
    void ipv6LoopbackEquivalentForms() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1", "::1"));

        assertTrue(manager.isTrustedProxy("::1"));
        assertTrue(manager.isTrustedProxy("0:0:0:0:0:0:0:1"));
        assertFalse(manager.isTrustedProxy("8.8.8.8"));
    }

    // ==================== 内外网判定 ====================

    @Test
    @DisplayName("命中网段判内网，未命中判外网")
    void lanJudgement() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        assertTrue(manager.isInLan("192.168.1.200"));
        assertFalse(manager.isInLan("192.168.2.1"));
    }

    @Test
    @DisplayName("未配置网段：一律判外网（安全侧默认，绝不误发通行证）")
    void emptyLanCidrsAlwaysWan() {
        ClientIpManager manager = manager(List.of(), List.of("127.0.0.1"));

        assertFalse(manager.isInLan("192.168.1.5"));
        assertNull(manager.matchLanCidr("192.168.1.5"));
    }

    @Test
    @DisplayName("IP 非法时判外网，不抛异常")
    void invalidIpIsWan() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        assertFalse(manager.isInLan("abc"));
        assertFalse(manager.isInLan(null));
    }

    @Test
    @DisplayName("matchLanCidr 返回命中的网段原文，供 C 端展示「当前位于哪个网段」")
    void matchLanCidrReturnsSource() {
        ClientIpManager manager = manager(List.of("10.0.0.0/8", "192.168.1.0/24"), List.of("127.0.0.1"));

        assertEquals("192.168.1.0/24", manager.matchLanCidr("192.168.1.5").source());
    }

    @Test
    @DisplayName("带端口 / IPv4-mapped 的客户端地址仍能正确判定内外网")
    void normalizedClientIpStillJudged() {
        ClientIpManager manager = manager(List.of("192.168.1.0/24"), List.of("127.0.0.1"));

        assertTrue(manager.isInLan("192.168.1.5:54321"));
        assertTrue(manager.isInLan("::ffff:192.168.1.5"));
    }
}
