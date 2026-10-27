package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 考勤模块配置（对应 application.yml 的 studio.attendance 段）
 *
 * 三个配置项各回答一个独立问题：{@code lanCidrs} 哪些地址算「在工作室」（业务口径）；
 * {@code trustedProxies} 哪些对端说的话可信（安全边界）；{@code minIntervalSeconds} 多久算重复签到。
 *
 * 全部给默认值、不做必填校验：考勤是附加能力，配置没写全时应用必须能正常启动，
 * 只是签到一律判为外网。真正需要「fail-loud」的网段配错，由 ClientIpManager 启动时打 warn 解决。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.attendance")
public class AttendanceProperties {

    /**
     * 工作室局域网网段，支持 CIDR（如 192.168.1.0/24）或单个 IP；命中任意一个即判为内网
     *
     * 它没有合理的默认值，只能由部署者填真实网段。留空不会启动失败，但所有签到都会被判为外网——
     * 这比「随便给个默认网段」安全：后者会让所有人被算成内网，签到记录失去区分意义。
     *
     * 取值来源：在工作室任一联网电脑上执行 ipconfig，取 IPv4 地址与子网掩码推出网段。
     */
    private List<String> lanCidrs = new ArrayList<>();

    /**
     * 可信反向代理地址，支持单个 IP 或 CIDR；默认 127.0.0.1 与 ::1
     *
     * 这是整套 IP 判定的安全根基：只有来自这些地址的请求，才采信 X-Real-IP / X-Forwarded-For。
     * 请求头是客户端完全可伪造的，不加这道判断，任何人在家加一个 X-Real-IP: 192.168.1.5
     * 就能伪造内网签到，整套考勤的区分能力归零。
     *
     * 默认值覆盖「Nginx 与应用同机部署」；Nginx 在另一台机器上时必须把它的地址加进来。
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

    /**
     * 同一用户两次签到的最小间隔（秒），默认 60
     *
     * 挡的是误触与连点，不是恶意刷接口（恶意刷由「用户身份」这一层天然限制）。
     * 设为 null 或 <= 0 表示不做间隔限制。
     *
     * 并发双击可能穿透本守卫，这是刻意接受的：为它加分布式锁的代价远大于收益，
     * 多出来的只是一条重复记录，对「是否到场」的结论毫无影响。
     */
    private Integer minIntervalSeconds = 60;
}
