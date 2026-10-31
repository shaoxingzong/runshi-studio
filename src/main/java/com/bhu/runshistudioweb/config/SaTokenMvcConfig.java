package com.bhu.runshistudioweb.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Sa-Token 拦截器注册：全局登录校验 + 注解鉴权 + 白名单
 *
 * @author shaoshing
 */
@Configuration
public class SaTokenMvcConfig implements WebMvcConfigurer {

    /**
     * 白名单：无需登录即可访问的路径
     *
     * 路径相对 context-path（本项目是 /api，但这里不能写 /api/xxx），支持 Ant 风格，如 /member/**。
     */
    private static final String[] EXCLUDE_PATHS = {
            // C 端公开接口（匿名可访问，产品要求游客也能用）
            "/user/register",   // 注册：还没有账号的人必须能访问
            "/user/login",      
            "/certificate/list",
            "/statistic/overview",
            "/project/list",
            "/project/detail",
            "/ai/chat/stream",
            "/post/list/page",
            "/post/detail",
            "/post/comment/list",
            "/traffic/report",
            "/uploads/**",

            "/favicon.ico",

            "/actuator/health"
    };

    /** 注册 Sa-Token 的全局拦截器，并挂上白名单 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
                    if (handler instanceof ResourceHttpRequestHandler) {
                        return;
                    }
                    StpUtil.checkLogin();
                }))
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS);
    }
}
