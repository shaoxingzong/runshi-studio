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
 * 跨域配置：让浏览器允许前端页面（不同端口/不同域名）调用本服务的接口
 *
 * <p>跨域（CORS）是什么：浏览器规定，页面只能请求「同源」（协议 + 域名 + 端口都相同）的地址。
 * 前端 dev server 跑在 5173、后端跑在 8080，就属于跨源，浏览器默认会拦截响应。
 * 注意拦截是浏览器单方面做的——服务端日志里能看到请求、curl 也能通，
 * 所以「接口在浏览器里报跨域、在命令行里正常」不是矛盾，而是同一件事的两面。
 *
 * <p>为什么用 CorsFilter 而不是 WebMvcConfigurer#addCorsMappings：这是本类唯一需要记住的理由。
 * 浏览器的 CORS 预检（正式请求前先发的那个 OPTIONS 探测请求）不带任何自定义头，
 * 因此它没有 satoken。如果跨域处理挂在 MVC 拦截器层，
 * 预检请求会先被鉴权拦截器拦下并返回未登录，浏览器随即判定预检失败，
 * 真正的请求永远不会发出。而 CorsFilter 是 Servlet 过滤器，在拦截器之前执行，
 * 预检请求在过滤器层就被直接应答，压根走不到鉴权那一层。
 *
 * <p>为什么用 allowedOriginPatterns 而不是 allowedOrigins：
 * 一旦 allowCredentials=true（前端要带 satoken，必须开），
 * 规范就禁止来源写 *，否则浏览器直接拒绝响应。
 * 但演示前端的端口是不固定的（各种 dev server 端口），
 * 所以用 pattern 形式的 http://localhost:* ——它可以带通配，
 * 又不是裸的 *，同时满足「凭据可用」与「端口不定」两个约束。
 *
 * <p>来源从配置读（studio.web.allowed-origins）：本地演示、联调环境、线上域名各不相同，
 * 写死在代码里等于每次改都要重新打包。默认值已覆盖本地两种回环地址，不配置也能直接用。
 *
 * @author shaoshing
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    /**
     * 允许的来源 pattern（逗号分隔）
     *
     * <p>默认值里的 * 是端口通配：http://localhost:* 匹配任意端口，
     * 但不匹配其它域名——它不是「允许所有来源」，不要把它理解成放开限制。
     *
     * <p>为什么写成 final 字段：本项目统一用构造器注入（@RequiredArgsConstructor + final），
     * 依赖不可变、缺失时启动期就暴露。@Value 能标在 final 字段上，
     * 靠的是 lombok.config 里把 @Value 声明为可复制注解。
     */
    @Value("${studio.web.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private final String allowedOrigins;

    /**
     * 注册跨域过滤器，对所有路径生效
     *
     * <p>为什么这样设计：跨域是「浏览器与服务器之间的约定」，必须在请求进入业务逻辑之前
     * 就把约定的响应头写回去。做成过滤器而不是拦截器，是为了让预检请求在鉴权之前被应答
     * （理由见类注释）；做成 Bean 而不是改某个 WebMvc 配置，是为了让它独立于 MVC 的生命周期，
     * 即使某个请求最终没有匹配到任何 Controller，跨域头也照样存在。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>创建配置对象：它是一份「跨域规则清单」，此时还没有绑定任何路径；</li>
     *     <li>允许携带凭据：必须开，否则浏览器不会把 satoken 头带到跨域请求上；</li>
     *     <li>设置来源 pattern：与上一步搭配使用，不能换成 allowedOrigins 写 *；</li>
     *     <li>声明允许的方法：显式列出 OPTIONS 很重要，预检请求用的就是它；</li>
     *     <li>允许任意请求头：鉴权信息就在头里，逐个列举既繁琐又容易漏；</li>
     *     <li>暴露 satoken 响应头：浏览器默认只让 JS 读少数几个安全响应头，
     *     不显式暴露的话，前端读不到服务端返回的 token；</li>
     *     <li>预检结果缓存 1 小时：省掉每个请求前的一次 OPTIONS 往返；</li>
     *     <li>把规则绑定到 /** 并包装成过滤器返回，交给 Spring 注册到 Servlet 容器。</li>
     * </ol>
     *
     * <p>容易踩的坑：setAllowCredentials(true) 与 setAllowedOrigins("*") 是互斥的，
     * 同时写会让所有跨域请求被浏览器拒绝，而服务端日志里一切正常。
     * 另一个坑是 maxAge 单位是秒，写成 3600 是 1 小时，别误以为是毫秒。
     *
     * @return 跨域过滤器，作用于 /**；它不依赖任何业务 Bean，
     *         即使其它配置出错也能正常创建
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
     * <p>为什么这样设计：配置项写成一行逗号分隔最省事（yml 里也最直观），
     * 但框架要的是 List。这个转换看着简单，坑却不少：
     * 直接 split(",") 不 trim，配置里习惯性加的空格会让来源变成 " http://localhost:*"，
     * 与浏览器发来的 Origin 头永远匹配不上——而且没有任何报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先判断整串是否为空：配置项被显式写成空串时，
     *     直接 split 会得到一个包含空串的数组，那个空串会被当成一个「来源」注册进去；</li>
     *     <li>按逗号拆分，逐项去首尾空白；</li>
     *     <li>过滤掉空项：配置末尾多写一个逗号是很常见的，
     *     不滤掉就会多出一个空来源；</li>
     *     <li>收集成不可变列表返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回空列表时，等于「一个来源都不允许」，
     * 而不是「允许所有」。这是刻意的安全默认值——配置漏填的后果应该是
     * 「跨域不通、马上被发现」，而不是「默默放开所有来源」。
     *
     * @param value 配置值，可为 null 或空白（例如配置项被显式留空）
     * @return 去空白、去空串后的来源列表；入参为空白时返回空列表，
     *         调用方不需要判空，但要知道空列表意味着拒绝一切跨域来源
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
