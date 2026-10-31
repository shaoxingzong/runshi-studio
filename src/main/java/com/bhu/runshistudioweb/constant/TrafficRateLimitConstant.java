package com.bhu.runshistudioweb.constant;

/**
 * 流量埋点相关的常量
 *
 * 限流键前缀单独成类、而不是散在 Controller 字符串里：键前缀是「限流语义的一部分」，
 * 写错一个字符不会报错，只会静默变成另一个计数器（例如与登录限流共用前缀时，
 * 刷埋点会把人正常登录也一起限掉）。集中在此便于对照与复用。
 *
 * @author shaoshing
 */
public final class TrafficRateLimitConstant {

    /**
     * 埋点上报的限流键前缀
     *
     * 必须以冒号结尾：IpRateLimitManager 直接拼接 IP，缺少分隔符时
     * 1.1.1.1 与 1.1.1.10 会撞进同一个键。
     */
    public static final String REPORT_KEY_PREFIX = "studio:traffic:rate:report:ip:";

    /**
     * 限流触发时提示文案里的动作名
     */
    public static final String REPORT_ACTION = "页面访问上报";

    private TrafficRateLimitConstant() {
    }
}
