package com.bhu.runshistudioweb.constant;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 游客 AI 提问的 IP 级限流常量
 *
 * author: shaoshing
 *
 * <p>R4 的背景：登录用户受 {@code studio.ai.query-limit} 约束，
 * 而<b>游客没有身份</b>——配额无从谈起，只能按 IP 兜底。
 * 匿名接口暴露在公网，不限流等于把 Embedding 与模型调用的费用开关交给了任何人。
 *
 * <p><b>为什么是「双层窗口」而不是单个窗口</b>：
 * 单窗口只能表达一种语义。分钟窗口（默认 5）挡的是<b>脚本连续刷</b>，
 * 日窗口（默认 50）挡的是<b>慢速低频但长期占用</b>——后者靠分钟窗口永远发现不了
 * （每分钟 4 次、持续一整天，也是 5000 多次调用）。两者缺一都有明显漏洞。
 */
public final class AiRateLimitConstant {

    /** 限流键前缀 */
    public static final String KEY_PREFIX = "studio:ai:rate:ip:";

    /** 分钟窗口键的时间格式（yyyyMMddHHmm，窗口 = 自然分钟） */
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 日窗口键的时间格式（yyyyMMdd，窗口 = 自然日） */
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 分钟窗口键的 TTL（秒）：120 = 窗口 60s + 余量 60s
     *
     * <p><b>余量不能省</b>：key 是在窗口内<b>任意时刻</b>创建的。
     * 若 TTL 正好等于 60s，一个 10:00:01 创建的 key 会在 10:01:01 过期，
     * 而它代表的 10:00 窗口要到 10:01:00 才结束——末尾那一分钟的请求会失去限流保护。
     */
    public static final long MINUTE_KEY_TTL_SECONDS = 120L;

    /**
     * 日窗口键 TTL 的额外余量（秒）：在「距次日零点」之上再加 60s，
     * 避免跨日瞬间因时钟误差出现「新窗口还没建、旧窗口已过期」的空档
     */
    public static final long DAY_KEY_TTL_MARGIN_SECONDS = 60L;

    /** 一天的秒数 */
    public static final long SECONDS_PER_DAY = 24 * 60 * 60L;

    /**
     * 分钟窗口键：{@code studio:ai:rate:ip:{ip}:min:{yyyyMMddHHmm}}
     *
     * @param ip     客户端 IP
     * @param minute 当前分钟（{@code yyyyMMddHHmm}）
     * @return 完整 key
     */
    public static String minuteKey(String ip, String minute) {
        return KEY_PREFIX + ip + ":min:" + minute;
    }

    /**
     * 日窗口键：{@code studio:ai:rate:ip:{ip}:day:{yyyyMMdd}}
     *
     * @param ip  客户端 IP
     * @param day 当前日期（{@code yyyyMMdd}）
     * @return 完整 key
     */
    public static String dayKey(String ip, String day) {
        return KEY_PREFIX + ip + ":day:" + day;
    }

    /** 当前分钟串 */
    public static String currentMinute() {
        return LocalDateTime.now().format(MINUTE_FORMATTER);
    }

    /** 当前日期串 */
    public static String currentDay() {
        return LocalDateTime.now().format(DAY_FORMATTER);
    }

    private AiRateLimitConstant() {
    }
}
