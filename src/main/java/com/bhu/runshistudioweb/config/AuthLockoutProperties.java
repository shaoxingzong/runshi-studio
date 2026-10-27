package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录失败锁定配置（账号级，对应 application.yml 的 studio.auth.lock 段）
 *
 * 存在的理由是补 IP 限流的盲区：后者挡「一个 IP 高频试很多账号」，挡不住海量 IP 各试几次的
 * 分布式慢速爆破。账号级锁定以「同一账号连续失败 N 次」触发，与尝试来自哪个 IP 无关。
 *
 * 只对「账号存在且密码错误」计数：对不存在的账号锁定，会让「已锁定」与「账号不存在」
 * 的差异变成账号存在性探测器。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.auth.lock")
public class AuthLockoutProperties {

    /**
     * 总开关（默认 true）
     *
     * 测试环境由 surefire 统一置 false：既有登录类用例密集，不关的话跑到第 5 个就开始
     * 集体「账号已锁定」，那是假失败。锁定行为由纯单测与端到端用例覆盖。
     */
    private Boolean enabled = true;

    /**
     * 锁定阈值：窗口内连续失败多少次后锁定，默认 5
     *
     * 5 次是「误伤面」与「防护力」的平衡点：再低会误抓到真忘密码的用户，再高则给爆破更多空间。
     */
    private Integer maxFailures = 5;

    /**
     * 锁定时长（分钟），默认 15，同时是计数 key 的 TTL
     *
     * 选固定窗口（而非每次失败都续期）是刻意的：续期方案下攻击者只要每 14 分钟试一次
     * 就能把账号永久锁死（DoS），固定窗口最坏也在 15 分钟后自动恢复。
     */
    private Integer lockMinutes = 15;
}
