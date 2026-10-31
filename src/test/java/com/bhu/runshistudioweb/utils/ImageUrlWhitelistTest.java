package com.bhu.runshistudioweb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文图片白名单验收测试（纯单测：不启 Spring、不连数据库、无外部依赖）
 *
 * @author shaoshing
 */
class ImageUrlWhitelistTest {

    @Test
    @DisplayName("配置解析：裸域名、带协议地址、端口与空格都归一化成 host")
    void parseHostsNormalizes() {
        assertEquals(List.of("cdn.example.com", "oss.example.com", "bucket.oss-cn-hangzhou.aliyuncs.com"),
                ImageUrlWhitelist.parseHosts(
                        " cdn.example.com , https://oss.example.com/ , bucket.oss-cn-hangzhou.aliyuncs.com:443 "));
    }

    @Test
    @DisplayName("配置为空 / null 时得到空列表（等价于只允许站内图片）")
    void parseHostsBlank() {
        assertTrue(ImageUrlWhitelist.parseHosts(null).isEmpty());
        assertTrue(ImageUrlWhitelist.parseHosts("   ").isEmpty());
        assertTrue(ImageUrlWhitelist.parseHosts(" , , ").isEmpty());
    }

    @Test
    @DisplayName("站内 /uploads/ 永远放行，不受白名单配置影响")
    void internalAlwaysAllowed() {
        assertTrue(ImageUrlWhitelist.isAllowed("/uploads/2026/10/x.png", List.of()));
        assertTrue(ImageUrlWhitelist.isAllowed(" /uploads/2026/10/x.png ", List.of("cdn.example.com")));
    }

    @Test
    @DisplayName("白名单内域名的绝对地址放行；其他域名、相对路径、危险协议一律拒绝")
    void externalHosts() {
        List<String> hosts = List.of("cdn.example.com");
        assertTrue(ImageUrlWhitelist.isAllowed("https://cdn.example.com/2026/10/x.png", hosts));
        assertTrue(ImageUrlWhitelist.isAllowed("HTTPS://CDN.EXAMPLE.COM/2026/10/x.png", hosts));
        assertFalse(ImageUrlWhitelist.isAllowed("https://evil.example.com/x.png", hosts));
        assertFalse(ImageUrlWhitelist.isAllowed("cdn.example.com/x.png", hosts), "缺协议不算绝对地址");
        assertFalse(ImageUrlWhitelist.isAllowed("//cdn.example.com/x.png", hosts), "协议相对地址不放行");
        assertFalse(ImageUrlWhitelist.isAllowed("data:image/png;base64,xxx", hosts));
        assertFalse(ImageUrlWhitelist.isAllowed(null, hosts));
    }

    @Test
    @DisplayName("未配置白名单时，所有站外地址都被拒绝（安全默认值）")
    void emptyWhitelistRejectsExternal() {
        assertFalse(ImageUrlWhitelist.isAllowed("https://cdn.example.com/x.png", List.of()));
        assertFalse(ImageUrlWhitelist.isAllowed("https://cdn.example.com/x.png", null));
    }

    @Test
    @DisplayName("域名匹配忽略端口；带 userinfo 的地址只取最后一个 @ 之后的 host")
    void hostEdgeCases() {
        List<String> hosts = List.of("cdn.example.com");
        assertTrue(ImageUrlWhitelist.isAllowed("https://cdn.example.com:8443/x.png", hosts));
        assertTrue(ImageUrlWhitelist.isAllowed("https://user@cdn.example.com/x.png", hosts));
        assertFalse(ImageUrlWhitelist.isAllowed("https://user@evil.example.com/x.png", hosts));
    }
}
