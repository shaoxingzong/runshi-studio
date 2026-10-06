package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 考勤模块配置（对应 {@code application.yml} 的 {@code studio.attendance} 段）
 *
 * author: shaoshing
 *
 * <p>三个配置项分别回答一个独立问题，含义不要混着理解：
 * <ul>
 *     <li>{@link #lanCidrs}：<b>哪些地址算「在工作室」</b>——业务判定口径；</li>
 *     <li>{@link #trustedProxies}：<b>哪些对端说的话可信</b>——安全边界，与业务无关；</li>
 *     <li>{@link #minIntervalSeconds}：<b>多久算重复签到</b>——防抖阈值。</li>
 * </ul>
 *
 * <p><b>为什么全部给默认值、不做必填校验</b>（与 {@code AiProperties} 同一纪律）：
 * 考勤是附加能力，配置没写全时应用必须能正常启动，只是签到一律判为「外网」并留下告警；
 * 若做成必填，一个缺失的环境变量会让整个站点起不来——那是本末倒置。
 *
 * <p>真正需要「fail-loud」的是<b>网段配错</b>这种情况，它由
 * {@code ClientIpManager} 在启动时打 {@code warn} 日志解决（而不是启动失败）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.attendance")
public class AttendanceProperties {

    /**
     * 工作室局域网网段，支持 CIDR（如 {@code 192.168.1.0/24}）或单个 IP（如 {@code 10.0.0.7}）
     *
     * <p>多个用<b>逗号分隔</b>，命中任意一个即判为内网。IPv4 与 IPv6 可以混写。
     *
     * <p><b>必填的业务配置</b>——它没有合理的默认值，只能由部署者填真实网段。
     * 留空时不会启动失败，但启动日志会有一条 {@code warn}，且<b>所有签到都会被判为外网</b>：
     * 这比「随便给个默认网段」安全得多——后者会让所有人都被算成内网，
     * 签到记录失去区分意义，而且没人会去看日志发现问题。
     *
     * <p>取值来源：在工作室任一联网电脑上执行 {@code ipconfig}，
     * 取 IPv4 地址与子网掩码推出网段（掩码 {@code 255.255.255.0} 对应 {@code /24}）。
     */
    private List<String> lanCidrs = new ArrayList<>();

    /**
     * 可信反向代理地址，支持单个 IP 或 CIDR；默认 {@code 127.0.0.1} 与 {@code ::1}
     *
     * <p><b>它是整套 IP 判定的安全根基</b>：只有来自这些地址的请求，
     * {@code ClientIpManager} 才会采信 {@code X-Real-IP} / {@code X-Forwarded-For} 请求头。
     * 来自其它地址的请求<b>一律忽略所有头</b>，直接使用 TCP 对端地址。
     *
     * <p>为什么必须这样设计：请求头是<b>客户端完全可伪造</b>的。
     * 若不加这道判断，任何人在家用浏览器加一个 {@code X-Real-IP: 192.168.1.5} 就能伪造内网签到——
     * 整个考勤的区分能力归零。加了它之后，伪造头只可能来自 Nginx 之前的真实客户端，
     * 而头部的改写权在 Nginx 手上（见 {@code deploy/nginx.conf}）。
     *
     * <p>默认值 {@code 127.0.0.1} 覆盖了「Nginx 与应用同机部署」这一最常见形态；
     * 若 Nginx 在另一台机器上，必须把它的地址加进来，否则所有请求都会按 Nginx 的 IP 判定。
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

    /**
     * 同一用户两次签到的最小间隔（秒），默认 60
     *
     * <p>它挡的是<b>误触与连点</b>（手机端按钮点两下、页面刷新后重提），不是恶意刷接口：
     * 恶意刷由「用户身份」这一层天然限制（要刷得有账号，且记录都挂在真实账号上，可审计）。
     *
     * <p>设为 {@code null} 或 {@code <= 0} 表示不做间隔限制。
     *
     * <p><b>并发双击可能穿透本守卫，这是刻意接受的</b>：两个请求同时查到「最近一条是很久以前」
     * 就会各自落库。为它加分布式锁的代价（Redis 依赖 + 锁失败时的行为定义 + 排查复杂度）
     * 远大于收益——多出来的只是一条重复记录，按天统计时对「是否到场」的结论毫无影响。
     */
    private Integer minIntervalSeconds = 60;
}
