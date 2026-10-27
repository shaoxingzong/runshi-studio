package com.bhu.runshistudioweb.config;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

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
 * @author shaoshing
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

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

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
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
