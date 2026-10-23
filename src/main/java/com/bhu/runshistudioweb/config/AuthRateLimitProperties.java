package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录 / 注册防爆破限流配置（对应 application.yml 的 studio.auth.rate-limit 段）
 *
 * 为什么需要它：登录与注册此前没有任何频率限制——无验证码、无失败锁定、无限流。
 * 登录接口等于把「账号口令空间」开放给无限次尝试；注册接口还是账号存在性探测器
 * （「账号已存在」的提示差异是功能需要，防批量扫描只能靠限流）。
 *
 * 为什么按 IP 而不是按账号：爆破的特征就是「一个源 IP 对多个账号试错」，
 * 按账号锁定挡不住它（换着账号试即可），且会被用来故意锁死他人账号（DoS）。
 * 按 IP 限流不依赖账号是否存在，误伤面也最小。
 *
 * 为什么全部给默认值、不做必填校验（与 AiProperties / AttendanceProperties
 * 同一纪律）：限流是附加防护，配置没写全时应用必须能正常启动；做成必填会让一个缺失的
 * 环境变量把整个站点搞得起不来——那是本末倒置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.auth.rate-limit")
public class AuthRateLimitProperties {

    /**
     * 总开关（默认 true）
     *
     * 为什么要有开关：测试环境必须能关掉它。MockMvc 下所有请求的来源 IP 都是
     * 127.0.0.1，不关的话既有登录 / 注册用例跑几个就集体 429——那是假失败，会掩盖真实问题。
     * 因此 surefire 里把它置为 false，限流行为由 IpRateLimitManagerTest 纯单测覆盖。
     */
    private Boolean enabled = true;

    /**
     * 登录：每 IP 每分钟的尝试上限，默认 10
     *
     * 正常用户忘密码时重试几次远达不到 10；爆破脚本则是每秒级尝试。
     * 10 次/分钟足以在初期挡住并留下告警日志，又几乎不会误伤真实用户。
     */
    private Integer loginMinuteLimit = 10;

    /**
     * 登录：每 IP 每天的尝试上限，默认 100
     *
     * 分钟窗口挡不住「每分钟 9 次、持续一整天」的慢速爆破，日窗口是第二道闸。
     * 100 次/天对个人使用绰绰有余（工作室规模 < 50 人，且存在 NAT 出口共享 IP 的情况，
     * 上限不宜再低）。
     */
    private Integer loginDailyLimit = 100;

    /**
     * 注册：每 IP 每分钟的上限，默认 5
     *
     * 注册是一次性行为，正常用户不会分钟级连续注册；批量注册脚本则是密集请求。
     * 比登录更严，因为注册接口还是账号存在性探测器，限流是防批量扫描的主要手段。
     */
    private Integer registerMinuteLimit = 5;

    /**
     * 注册：每 IP 每天的上限，默认 20
     *
     * 与分钟窗口配套，挡慢速扫号。20 次/天足够一个小团队陆续注册使用。
     */
    private Integer registerDailyLimit = 20;
}
