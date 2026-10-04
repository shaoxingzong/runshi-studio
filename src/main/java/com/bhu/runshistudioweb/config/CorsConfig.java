package com.bhu.runshistudioweb.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域配置
 *
 * author: shaoshing
 *
 * <p><b>为什么用 {@link CorsFilter} 而不是 {@code WebMvcConfigurer#addCorsMappings}</b>：
 * 这是本类唯一需要记住的理由。浏览器的 CORS <b>预检</b>（{@code OPTIONS}）不带任何自定义头，
 * 因此它<b>没有 satoken</b>——如果跨域处理挂在 MVC 拦截器层，
 * 预检请求会先被 Sa-Token 拦截器拦下返回 401，浏览器随即判定预检失败，
 * 真正的请求永远不会发出。表现为「接口用 curl 能通、页面上报 CORS 错误」，极难排查。
 * 而 {@link CorsFilter} 在<b>过滤器层</b>就把预检短路返回，压根走不到拦截器。
 *
 * <p><b>为什么用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins}</b>：
 * 一旦 {@code allowCredentials=true}（前端要带 satoken，必须开），
 * 规范就<b>禁止</b>来源写 {@code *}，否则浏览器直接拒绝响应。
 * 但演示前端的端口是不固定的（file:// 直开、或 5173/8081 各种 dev server），
 * 所以用 <b>pattern</b> 形式的 {@code http://localhost:*} ——它可以带通配，
 * 又不是裸的 {@code *}，同时满足「凭据可用」与「端口不定」两个约束。
 *
 * <p><b>来源从配置读（{@code studio.web.allowed-origins}）</b>：
 * 本地演示、联调环境、将来真上线时的域名各不相同，写死在代码里等于每次改都要重新打包。
 * 默认值已覆盖本地两种回环地址，不配置也能直接用。
 */
@Configuration
public class CorsConfig {

    /**
     * 允许的来源 pattern（逗号分隔）
     *
     * <p>默认值里的 {@code *} 是端口通配：{@code http://localhost:*} 匹配任意端口，
     * 但不匹配其它域名——它不是「允许所有来源」
     */
    @Value("${studio.web.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    /**
     * 注册跨域过滤器
     *
     * @return 过滤器（作用于 {@code /**}）
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
     * 拆分逗号分隔的来源配置
     *
     * @param value 配置值
     * @return 来源列表（已去空白与空串）
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
