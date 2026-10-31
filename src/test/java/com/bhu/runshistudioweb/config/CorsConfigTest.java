package com.bhu.runshistudioweb.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同源识别与 CorsFilter 放行行为的回归测试
 *
 * 重点覆盖「Nginx 终止 TLS、后端看到 http」的生产形态：浏览器发 https://域名，
 * 若不看 X-Forwarded-Proto 就会把同源 POST 判成跨域并 403。
 */
class CorsConfigTest {

    private static MockHttpServletRequest request(String origin, String scheme, String host, String forwardedProto) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/post");
        request.setScheme(scheme);
        request.setServerName("backend");
        request.setServerPort(8080);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (host != null) {
            request.addHeader("Host", host);
        }
        if (forwardedProto != null) {
            request.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        return request;
    }

    @Test
    @DisplayName("HTTPS 经 Nginx 转发：浏览器同源，应识别为同源")
    void httpsBehindProxyIsSameOrigin() {
        MockHttpServletRequest request = request("https://runshi.example.com", "http",
                "runshi.example.com", "https");
        assertTrue(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("未带转发协议：https 站点会被误判为跨域（这正是要修的 bug）")
    void missingForwardedProtoIsNotSameOrigin() {
        MockHttpServletRequest request = request("https://runshi.example.com", "http",
                "runshi.example.com", null);
        assertFalse(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("域名不同：跨域")
    void differentHostIsNotSameOrigin() {
        MockHttpServletRequest request = request("https://evil.example.com", "http",
                "runshi.example.com", "https");
        assertFalse(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("端口不同：跨域")
    void differentPortIsNotSameOrigin() {
        MockHttpServletRequest request = request("https://runshi.example.com:8443", "http",
                "runshi.example.com", "https");
        assertFalse(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("显式 443 与默认 443 等价：同源")
    void explicitDefaultPortIsSameOrigin() {
        MockHttpServletRequest request = request("https://runshi.example.com:443", "http",
                "runshi.example.com", "https");
        assertTrue(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("本地开发同源：http://localhost:8080")
    void localhostSameOrigin() {
        MockHttpServletRequest request = request("http://localhost:8080", "http",
                "localhost:8080", null);
        assertTrue(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("前端 5173 调后端 8080：跨域（交给白名单，不误判为同源）")
    void devPortMismatchIsCors() {
        MockHttpServletRequest request = request("http://localhost:5173", "http",
                "localhost:8080", null);
        assertFalse(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("Origin 缺失 / 为 null / 非法：都不算同源")
    void invalidOriginIsNotSameOrigin() {
        assertFalse(CorsConfig.isSameOrigin(request(null, "http", "runshi.example.com", "https")));
        assertFalse(CorsConfig.isSameOrigin(request("null", "http", "runshi.example.com", "https")));
        assertFalse(CorsConfig.isSameOrigin(request("runshi.example.com", "http", "runshi.example.com", "https")));
    }

    @Test
    @DisplayName("多级代理：X-Forwarded-* 取第一个值")
    void firstForwardedValueWins() {
        MockHttpServletRequest request = request("https://runshi.example.com", "http",
                "runshi.example.com", "https, http");
        assertTrue(CorsConfig.isSameOrigin(request));
    }

    @Test
    @DisplayName("同源请求经 CorsFilter 应放行（不返回 403）")
    void sameOriginPassesThroughFilter() throws Exception {
        CorsConfig config = new CorsConfig("http://localhost:*");
        MockHttpServletRequest request = request("https://runshi.example.com", "http",
                "runshi.example.com", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        config.corsFilter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("跨域且不在白名单：CorsFilter 应拒绝")
    void crossOriginOutsideAllowListIsRejected() throws Exception {
        CorsConfig config = new CorsConfig("http://localhost:*");
        MockHttpServletRequest request = request("https://evil.example.com", "http",
                "runshi.example.com", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        config.corsFilter().doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }
}
