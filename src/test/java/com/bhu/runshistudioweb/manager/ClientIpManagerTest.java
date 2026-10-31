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
 * 客户端 IP 解析验收测试（纯单测：不启 Spring、不连数据库）
 *
 * @author shaoshing
 */
class ClientIpManagerTest {

    /** 直接 new 一个 Manager：它不依赖 HTTP，也不需要 Spring 容器 */
    private static ClientIpManager manager(List<String> trustedProxies) {
        AttendanceProperties props = new AttendanceProperties();
        props.setTrustedProxies(trustedProxies);
        return new ClientIpManager(props);
    }

    // 信任链（安全边界）

    @Test
    @DisplayName("对端不可信：一律忽略 X-Real-IP 与 XFF，直接用 TCP 对端")
    void untrustedPeerHeadersIgnored() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        String ip = manager.resolveClientIp("8.8.8.8", "192.168.1.5", "192.168.1.5");

        assertEquals("8.8.8.8", ip, "采信了不可信来源的请求头 —— 记录的 IP 可以被任意伪造");
    }

    @Test
    @DisplayName("对端可信：采信 X-Real-IP")
    void trustedPeerUsesXRealIp() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        assertEquals("192.168.1.5", manager.resolveClientIp("127.0.0.1", "192.168.1.5", null));
    }

    @Test
    @DisplayName("没有任何可信代理时，所有请求头都不采信")
    void noTrustedProxyIgnoresHeaders() {
        ClientIpManager manager = manager(List.of());

        assertEquals("8.8.8.8", manager.resolveClientIp("8.8.8.8", "10.0.0.9", "10.0.0.9"));
    }

    @Test
    @DisplayName("XFF 从右往左取：客户端伪造的最左段必须被跳过")
    void xffPicksFromRight() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        String ip = manager.resolveClientIp("127.0.0.1", null, "192.168.1.5, 10.0.0.9, 127.0.0.1");

        assertEquals("10.0.0.9", ip, "取到了伪造段 —— XFF 扫描方向错了，等于把伪造权交给客户端");
    }

    @Test
    @DisplayName("XFF 整条都是代理：回退到对端地址（不返回 null，也不采信伪造段）")
    void xffAllProxiesFallback() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        assertEquals("127.0.0.1", manager.resolveClientIp("127.0.0.1", null, "127.0.0.1, 127.0.0.1"));
    }

    @Test
    @DisplayName("XFF 含无法解析的段（unknown / 空）时跳过，不整条失败")
    void xffSkipsUnparsable() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        assertEquals("10.0.0.9", manager.resolveClientIp("127.0.0.1", null, "unknown, 10.0.0.9, , 127.0.0.1"));
    }

    @Test
    @DisplayName("对端地址无法解析时返回 null：不猜测、也不采信请求头")
    void unparsablePeerReturnsNull() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        assertNull(manager.resolveClientIp("not-an-ip", "192.168.1.5", "192.168.1.5"));
    }

    @Test
    @DisplayName("IPv6 环回作为可信代理时，其带来的 X-Real-IP 必须被采信")
    void ipv6LoopbackPeerIsTrusted() {
        ClientIpManager manager = manager(List.of("127.0.0.1", "::1"));

        String ip = manager.resolveClientIp("::1", "192.168.1.5", null);

        assertEquals("192.168.1.5", ip, "::1 未被判为可信代理 —— 归一化后的地址串无法再解析");
    }

    @Test
    @DisplayName("IPv6 环回的完整写法与压缩写法等价，都应命中可信代理")
    void ipv6LoopbackEquivalentForms() {
        ClientIpManager manager = manager(List.of("127.0.0.1", "::1"));

        assertTrue(manager.isTrustedProxy("::1"));
        assertTrue(manager.isTrustedProxy("0:0:0:0:0:0:0:1"));
        assertFalse(manager.isTrustedProxy("8.8.8.8"));
    }

    @Test
    @DisplayName("带端口 / IPv4-mapped 的客户端地址能被正确归一化")
    void clientIpNormalized() {
        ClientIpManager manager = manager(List.of("127.0.0.1"));

        assertEquals("192.168.1.5", manager.resolveClientIp("192.168.1.5:54321", null, null));
        assertEquals("192.168.1.5", manager.resolveClientIp("::ffff:192.168.1.5", null, null));
    }
}
