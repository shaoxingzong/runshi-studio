package com.bhu.runshistudioweb.config;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 跨域配置：让浏览器允许前端页面（不同端口 / 不同域名）调用本服务的接口
 *
 * 用 CorsFilter 而不是 WebMvcConfigurer#addCorsMappings，这是本类唯一需要记住的理由：
 * 预检（OPTIONS）请求不带任何自定义头，因此没有 satoken。跨域处理若挂在 MVC 拦截器层，
 * 预检会先被鉴权拦下并返回未登录，浏览器随即判定预检失败，真正的请求永远不会发出。
 *
 * 用 allowedOriginPatterns 而不是 allowedOrigins：allowCredentials=true 时规范禁止来源写 *，
 * 而演示前端的端口不固定，所以用 http://localhost:* 这种 pattern。
 *
 * <b>同源请求必须直接放行</b>（本类最容易踩的坑）：
 * 浏览器对同源的 POST 也会带 Origin 头，而 Spring 的 CorsFilter 只按「Origin 与后端看到的
 * scheme/host/port」比较。生产环境 Nginx 终止 TLS 后以 HTTP 转发给后端，后端看到的是
 * http://域名，浏览器发来的是 https://域名 —— 在 Spring 眼里这是跨域，于是白名单不匹配、
 * 直接 403。表现极其迷惑：所有 GET 正常（GET 同源通常不带 Origin），一旦登录 / 发帖 /
 * 上传就 403，而 Nginx 和后端日志都看不出问题。
 *
 * 因此这里先判断「浏览器眼里的同源」：scheme 取 X-Forwarded-Proto（Nginx 已传），host 取
 * X-Forwarded-Host / Host，端口取 X-Forwarded-Port / Host 里的端口。同源就直接返回 null，
 * 交给后续链路，完全不做 CORS 校验；只有真正跨域才走下面的白名单。
 *
 * 这样即使用户忘了配 WEB_ALLOWED_ORIGINS（生产默认值是占位域名），同域部署也能正常工作。
 *
 * @author shaoshing
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    /** 代理链上取第一个值：多级代理时第一个才是浏览器原始请求的协议 / 主机 */
    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String FORWARDED_HOST = "X-Forwarded-Host";
    private static final String FORWARDED_PORT = "X-Forwarded-Port";

    /**
     * 允许的来源 pattern（逗号分隔）
     *
     * 默认值里的 * 是端口通配：http://localhost:* 匹配任意端口，但不匹配其它域名——
     * 它不是「允许所有来源」，不要理解成放开限制。
     */
    @Value("${studio.web.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private final String allowedOrigins;

    /**
     * 注册跨域过滤器，对所有路径生效
     *
     * 做成过滤器而不是拦截器，是为了让预检请求在鉴权之前被应答；做成 Bean 而不是改
     * WebMvc 配置，是为了让它独立于 MVC 生命周期，即使请求没匹配到任何 Controller，
     * 跨域头也照样存在。
     *
     * 坑一：setAllowCredentials(true) 与 setAllowedOrigins("*") 互斥，同时写会让所有跨域请求
     * 被浏览器拒绝，而服务端日志里一切正常。
     * 坑二：maxAge 单位是秒，3600 是 1 小时。
     *
     * @return 跨域过滤器，作用于全部路径
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许携带凭据（satoken 走自定义头，前端必须能带上它）
        config.setAllowCredentials(true);
        // 用 pattern 而不是 origins：与 allowCredentials=true 搭配，且支持端口通配
        config.setAllowedOriginPatterns(splitOrigins(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 允许任意请求头（satoken 就在头里）
        config.setAllowedHeaders(List.of("*"));
        // 暴露 satoken：登录接口把它放在响应头里，不暴露的话前端 JS 读不到
        config.setExposedHeaders(List.of("satoken"));
        // 预检结果缓存 1 小时：省掉每个请求前的一次 OPTIONS 往返
        config.setMaxAge(3600L);

        // 关键：同源请求返回 null，CorsFilter 会当作「不需要处理」直接放行；
        // 只有真正跨域才落到白名单校验（见类注释）
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource() {
            @Override
            public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
                return isSameOrigin(request) ? null : super.getCorsConfiguration(request);
            }
        };
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * 判断浏览器眼里的请求是否与 Origin 同源
     *
     * 比较三元组 scheme + host + port。生产经过 Nginx 时必须用转发头还原浏览器看到的协议，
     * 否则 HTTPS 站点会被误判成跨域（见类注释）。
     *
     * 安全性说明：X-Forwarded-* 可以被非浏览器客户端伪造，但 CORS 本身就是浏览器侧的约束，
     * 能伪造请求头的客户端本来就不受 CORS 限制，因此这不构成绕过；浏览器无法伪造这些头。
     *
     * @param request 当前请求，不能为 null
     * @return true 表示同源，应跳过 CORS 处理；Origin 缺失 / 为 null / 非法时返回 false
     */
    static boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (StrUtil.isBlank(origin) || "null".equalsIgnoreCase(origin.trim())) {
            return false;
        }

        // 手写解析而不是 new URI(...)：部分合法 Origin（如带下划线的主机名）会让 URI 抛异常，
        // 这里只关心 scheme://host[:port]，解析失败一律按跨域处理（安全侧默认）
        String trimmed = origin.trim();
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) {
            return false;
        }
        String originScheme = trimmed.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String authority = trimmed.substring(schemeEnd + 3);
        // Origin 不带路径 / query；出现这些说明不是浏览器发的合法 Origin
        if (authority.isEmpty() || authority.indexOf('/') >= 0 || authority.indexOf('?') >= 0) {
            return false;
        }

        HostAndPort originHost = parseHostAndPort(authority);
        if (originHost.host.isEmpty()) {
            return false;
        }

        String requestScheme = firstHeaderValue(request.getHeader(FORWARDED_PROTO));
        if (StrUtil.isBlank(requestScheme)) {
            requestScheme = request.getScheme();
        }
        requestScheme = requestScheme == null ? "" : requestScheme.toLowerCase(Locale.ROOT);

        String hostHeader = firstHeaderValue(request.getHeader(FORWARDED_HOST));
        if (StrUtil.isBlank(hostHeader)) {
            hostHeader = request.getHeader(HttpHeaders.HOST);
        }
        if (StrUtil.isBlank(hostHeader)) {
            // HTTP/1.0 等没有 Host 头的场景：退回容器解析出的主机名，端口按 scheme 默认值
            return matches(originScheme, originHost, requestScheme,
                    new HostAndPort(request.getServerName(), -1), "");
        }

        HostAndPort requestHost = parseHostAndPort(firstHeaderValue(hostHeader));
        return matches(originScheme, originHost, requestScheme, requestHost,
                firstHeaderValue(request.getHeader(FORWARDED_PORT)));
    }

    /**
     * 比较同源三元组
     *
     * 端口缺省时按 scheme 的默认端口补齐（https=443 / http=80），而不是用容器监听端口：
     * 生产是 Nginx 443 对外、后端 8080 监听，拿 8080 去比会把同源判成跨域。
     *
     * @param forwardedPort X-Forwarded-Port 的值，可为空
     */
    private static boolean matches(String originScheme, HostAndPort originHost, String requestScheme,
                                   HostAndPort requestHost, String forwardedPort) {
        if (!originScheme.equals(requestScheme)) {
            return false;
        }

        int originPort = originHost.port >= 0 ? originHost.port : defaultPort(originScheme);
        int requestPort;
        if (StrUtil.isNotBlank(forwardedPort)) {
            requestPort = parsePort(firstHeaderValue(forwardedPort), -1);
            if (requestPort < 0) {
                return false;
            }
        } else {
            requestPort = requestHost.port >= 0 ? requestHost.port : defaultPort(requestScheme);
        }

        return originPort == requestPort && originHost.host.equalsIgnoreCase(requestHost.host);
    }

    /** scheme 的默认端口；未知 scheme 返回 -1（与显式端口永远不相等，按跨域处理） */
    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    /** 取逗号分隔头里的第一个值并 trim；空值时返回空串 */
    private static String firstHeaderValue(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    /** 解析 host[:port]，兼容 IPv6 的 [::1]:8080 写法；解析不出端口时 port = -1 */
    private static HostAndPort parseHostAndPort(String value) {
        String host = StrUtil.blankToDefault(value, "").trim();
        if (host.isEmpty()) {
            return new HostAndPort("", -1);
        }

        // IPv6：方括号内是主机，方括号后的 :port 才是端口
        if (host.charAt(0) == '[') {
            int close = host.indexOf(']');
            if (close < 0) {
                return new HostAndPort("", -1);
            }
            String bareHost = host.substring(0, close + 1);
            String rest = host.substring(close + 1);
            int port = rest.startsWith(":") ? parsePort(rest.substring(1), -1) : -1;
            return new HostAndPort(bareHost, port);
        }

        int firstColon = host.indexOf(':');
        if (firstColon < 0) {
            return new HostAndPort(host, -1);
        }
        // 多个冒号却没方括号：IPv6 省略写法，不认为带端口
        if (host.indexOf(':', firstColon + 1) >= 0) {
            return new HostAndPort(host, -1);
        }
        return new HostAndPort(host.substring(0, firstColon), parsePort(host.substring(firstColon + 1), -1));
    }

    /** 端口解析失败时返回 fallback，绝不抛异常 */
    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** host + port 的轻量载体（host 保留 IPv6 的方括号，两边一致即可） */
    private record HostAndPort(String host, int port) {
    }

    /**
     * 把逗号分隔的来源配置拆成列表
     *
     * 坑一：split 后必须 trim。配置里习惯性加的空格会让来源变成 " http://localhost:*"，
     * 与浏览器发来的 Origin 头永远匹配不上，而且没有任何报错。
     * 坑二：必须过滤空项，配置末尾多写一个逗号很常见。
     *
     * 返回空列表时等于「一个来源都不允许」而不是「允许所有」——配置漏填的后果应该是
     * 「跨域不通、马上被发现」。
     *
     * @param value 配置值，可为 null 或空白
     * @return 去空白、去空串后的来源列表；入参为空白时返回空列表
     */
    private List<String> splitOrigins(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }
}
