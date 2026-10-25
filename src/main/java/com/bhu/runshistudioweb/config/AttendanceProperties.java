package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 考勤模块配置（对应 application.yml 的 studio.attendance 段）
 *
 * 三个配置项分别回答一个独立问题，含义不要混着理解：
 * - lanCidrs：哪些地址算「在工作室」——业务判定口径；
 * - trustedProxies：哪些对端说的话可信——安全边界，与业务无关；
 * - minIntervalSeconds：多久算重复签到——防抖阈值。
 *
 * 为什么全部给默认值、不做必填校验（与 AiProperties 同一纪律）：
 * 考勤是附加能力，配置没写全时应用必须能正常启动，只是签到一律判为「外网」并留下告警；
 * 若做成必填，一个缺失的环境变量会让整个站点起不来——那是本末倒置。
 *
 * 真正需要「fail-loud」的是网段配错这种情况，它由
 * ClientIpManager 在启动时打 warn 日志解决（而不是启动失败）。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.attendance")
public class AttendanceProperties {

    /**
     * 工作室局域网网段，支持 CIDR（如 192.168.1.0/24）或单个 IP（如 10.0.0.7）
     *
     * 多个用逗号分隔，命中任意一个即判为内网。IPv4 与 IPv6 可以混写。
     *
     * 必填的业务配置——它没有合理的默认值，只能由部署者填真实网段。
     * 留空时不会启动失败，但启动日志会有一条 warn，且所有签到都会被判为外网：
     * 这比「随便给个默认网段」安全得多——后者会让所有人都被算成内网，
     * 签到记录失去区分意义，而且没人会去看日志发现问题。
     *
     * 取值来源：在工作室任一联网电脑上执行 ipconfig，
     * 取 IPv4 地址与子网掩码推出网段（掩码 255.255.255.0 对应 /24）。
     */
    private List<String> lanCidrs = new ArrayList<>();

    /**
     * 可信反向代理地址，支持单个 IP 或 CIDR；默认 127.0.0.1 与 ::1
     *
     * 它是整套 IP 判定的安全根基：只有来自这些地址的请求，
     * ClientIpManager 才会采信 X-Real-IP / X-Forwarded-For 请求头。
     * 来自其它地址的请求一律忽略所有头，直接使用 TCP 对端地址。
     *
     * 为什么必须这样设计：请求头是客户端完全可伪造的。
     * 若不加这道判断，任何人在家用浏览器加一个 X-Real-IP: 192.168.1.5 就能伪造内网签到——
     * 整个考勤的区分能力归零。有它把关，伪造头只可能来自 Nginx 之前的真实客户端，
     * 而头部的改写权在 Nginx 手上（见 deploy/nginx.conf）。
     *
     * 默认值 127.0.0.1 覆盖了「Nginx 与应用同机部署」这一最常见形态；
     * 若 Nginx 在另一台机器上，必须把它的地址加进来，否则所有请求都会按 Nginx 的 IP 判定。
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

    /**
     * 同一用户两次签到的最小间隔（秒），默认 60
     *
     * 它挡的是误触与连点（手机端按钮点两下、页面刷新后重提），不是恶意刷接口：
     * 恶意刷由「用户身份」这一层天然限制（要刷得有账号，且记录都挂在真实账号上，可审计）。
     *
     * 设为 null 或 <= 0 表示不做间隔限制。
     *
     * 并发双击可能穿透本守卫，这是刻意接受的：两个请求同时查到「上一条签到已过去很久」
     * 就会各自落库。为它加分布式锁的代价（Redis 依赖 + 锁失败时的行为定义 + 排查复杂度）
     * 远大于收益——多出来的只是一条重复记录，按天统计时对「是否到场」的结论毫无影响。
     */
    private Integer minIntervalSeconds = 60;
}
