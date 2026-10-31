package com.bhu.runshistudioweb.constant;

/**
 * 登录 / 注册限流的 Redis 键前缀
 *
 * @author shaoshing
 */
public final class AuthRateLimitConstant {

    /** 登录限流键前缀 */
    public static final String LOGIN_KEY_PREFIX = "studio:auth:rate:login:ip:";

    /** 注册限流键前缀 */
    public static final String REGISTER_KEY_PREFIX = "studio:auth:rate:register:ip:";

    private AuthRateLimitConstant() {
    }
}
