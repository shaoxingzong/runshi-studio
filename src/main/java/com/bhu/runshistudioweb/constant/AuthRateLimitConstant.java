package com.bhu.runshistudioweb.constant;

/**
 * 登录 / 注册限流的 Redis 键前缀
 *
 * 与游客提问限流（ studio:ai:rate:ip:）同一套命名规范：
 * studio:{模块}:rate:{业务}:ip:。按接口分开前缀，两个业务的计数互不占用——
 * 登录被限流不该连带堵死注册，反之亦然。
 */
public final class AuthRateLimitConstant {

    /** 登录限流键前缀 */
    public static final String LOGIN_KEY_PREFIX = "studio:auth:rate:login:ip:";

    /** 注册限流键前缀 */
    public static final String REGISTER_KEY_PREFIX = "studio:auth:rate:register:ip:";

    private AuthRateLimitConstant() {
    }
}
