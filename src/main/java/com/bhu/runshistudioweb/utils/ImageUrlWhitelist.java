package com.bhu.runshistudioweb.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正文图片地址白名单：站内 /uploads/ + 显式配置的 OSS/CDN 域名
 *
 * <p>为什么不是「允许所有 http(s) 外链」：帖子正文的图片会在读者浏览器里发起请求，
 * 任意外链等于允许追踪像素与第三方可用性风险；只放行运营自己配置的图片域名。</p>
 *
 * @author shaoshing
 */
public final class ImageUrlWhitelist {

    /** 站内上传目录前缀：与 FileManager 的返回值、WebMvcConfig 的静态映射保持一致 */
    public static final String INTERNAL_IMAGE_PREFIX = "/uploads/";

    /** 绝对地址的协议前缀：只认 http / https（大小写不敏感） */
    private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

    /** 从绝对地址里取 authority（协议之后、第一个 / ? # 之前） */
    private static final Pattern URL_AUTHORITY = Pattern.compile("^https?://([^/?#]+)", Pattern.CASE_INSENSITIVE);

    private ImageUrlWhitelist() {
    }

    /**
     * 解析配置里的域名列表
     *
     * @param raw 逗号分隔的域名，允许写成带协议的完整地址（如 https://cdn.example.com）；
     *            null / 空串返回空列表（等价于只允许站内图片）
     * @return 去重后的小写 host；无法解析的项直接忽略
     */
    public static List<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> hosts = new ArrayList<>();
        for (String item : raw.split(",")) {
            String host = hostOf(item);
            if (!host.isEmpty() && !hosts.contains(host)) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    /**
     * 判断一张图片的地址是否允许
     *
     * @param url          正文里的图片地址
     * @param allowedHosts 已归一化的站外域名（见 {@link #parseHosts(String)}）
     * @return 站内 /uploads/ 路径，或 http(s) 且域名在白名单内
     */
    public static boolean isAllowed(String url, List<String> allowedHosts) {
        if (url == null) {
            return false;
        }
        String src = url.trim();
        if (src.startsWith(INTERNAL_IMAGE_PREFIX)) {
            return true;
        }
        if (allowedHosts == null || allowedHosts.isEmpty() || !ABSOLUTE_HTTP_URL.matcher(src).find()) {
            return false;
        }
        String host = hostOf(src);
        return !host.isEmpty() && allowedHosts.contains(host);
    }

    /**
     * 取 host（域名，不含端口）
     *
     * <p>配置项允许省略协议：只有域名时按 https 补全再解析。相对路径解析不出 host，返回空串。</p>
     */
    private static String hostOf(String value) {
        if (value == null) {
            return "";
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return "";
        }
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        Matcher matcher = URL_AUTHORITY.matcher(candidate);
        if (!matcher.find()) {
            return "";
        }
        String authority = matcher.group(1);
        // userinfo@host：只认最后一段，避免把用户名当成域名
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        // 去掉端口；IPv6 字面量（[::1]）本项目用不到，不做特殊处理
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.toLowerCase(Locale.ROOT);
    }
}
