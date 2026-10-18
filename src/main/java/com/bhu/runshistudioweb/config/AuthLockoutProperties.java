package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录失败锁定配置（账号级，对应 {@code application.yml} 的 {@code studio.auth.lock} 段）
 *
 * author: shaoshing
 *
 * <p><b>与 IP 限流（{@link AuthRateLimitProperties}）的分工</b>，这是本配置存在的理由：
 * IP 限流挡「一个 IP 高频试很多账号」，但挡不住<b>分布式慢速爆破</b>——
 * 海量 IP 各试几次、每分钟的频次远低于上限，却能持续消耗目标账号的口令空间
 * （{@code application.yml} 的注释里记着这个已知盲区）。账号级锁定正是补这个盲区：
 * 以「同一账号连续失败 N 次」为触发条件，与尝试来自哪个 IP 无关。
 *
 * <p><b>为什么只对「账号存在且密码错误」计数</b>（实现见 {@code AuthLockManager}）：
 * 账号不存在时没有可锁对象；更重要的是，若对不存在的账号也锁定并给出不同提示，
 * 「已锁定」与「账号不存在」的差异会变成新的账号存在性探测器——那是自己给自己开的门。
 *
 * <p>默认值全部可缺省（与 {@code AuthRateLimitProperties} 同一纪律）：锁定是附加防护，
 * 配置没写全时应用必须能正常启动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.auth.lock")
public class AuthLockoutProperties {

    /**
     * 总开关（默认 true）
     *
     * <p>测试环境由 surefire 统一置 false：既有登录类用例密集（改密码、换角色等
     * 前置步骤都要登录），不关的话跑到第 5 个就开始集体「账号已锁定」，那是假失败。
     * 锁定行为本身由 {@code AuthLockManagerTest} 纯单测 + 端到端用例覆盖。
     */
    private Boolean enabled = true;

    /**
     * 锁定阈值：窗口内连续失败多少次后锁定，默认 5
     *
     * <p>正常用户忘密码连错 5 次是小概率事件（有「确认密码」输入框挡着）；
     * 爆破脚本则会在同一个账号上连续尝试。5 次是「误伤面」与「防护力」的平衡点：
     * 再低（如 3 次）会开始误抓到真忘密码的用户，再高则给爆破更多空间。
     */
    private Integer maxFailures = 5;

    /**
     * 锁定时长（分钟），默认 15
     *
     * <p>同时是计数 key 的 TTL：从<b>第一次失败</b>起算，期间累积失败次数；
     * key 过期后计数清零、账号恢复可登录。选固定窗口（而非每次失败都续期）是刻意的：
     * 续期方案下攻击者只要每 14 分钟试一次就能把账号<b>永久锁死</b>（DoS），
     * 固定窗口最坏也在 15 分钟后自动恢复。持续失败维持锁定的问题由 IP 限流兜
     * （单 IP 持续失败会先撞 10 次/分钟的限流），分布式慢速本就该由验证码类方案
     * 进一步收口，属于后续增强。
     */
    private Integer lockMinutes = 15;
}
