package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录 / 注册防爆破限流配置（对应 application.yml 的 studio.auth.rate-limit 段）
 *
 * 登录接口不设频率限制，等于把「账号口令空间」开放给无限次尝试；注册接口的「账号已存在」
 * 提示是功能需要，防批量扫描只能靠限流。
 *
 * 按 IP 而不是按账号：爆破的特征就是一个源 IP 对多个账号试错，按账号锁定挡不住它
 * （换着账号试即可），且会被用来故意锁死他人账号。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.auth.rate-limit")
public class AuthRateLimitProperties {

    /**
     * 总开关（默认 true）
     *
     * 测试环境必须能关掉它：MockMvc 下所有请求来源都是 127.0.0.1，不关的话既有登录 / 注册用例
     * 跑几个就集体 429。因此 surefire 里置为 false，限流行为由纯单测覆盖。
     */
    private Boolean enabled = true;

    /**
     * 登录：每 IP 每分钟的尝试上限，默认 10
     *
     * 正常用户忘密码时重试几次远达不到 10；爆破脚本则是每秒级尝试。
     */
    private Integer loginMinuteLimit = 10;

    /**
     * 登录：每 IP 每天的尝试上限，默认 100
     *
     * 分钟窗口挡不住「每分钟 9 次、持续一整天」的慢速爆破，日窗口是第二道闸。
     * 存在 NAT 出口共享 IP 的情况，上限不宜再低。
     */
    private Integer loginDailyLimit = 100;

    /**
     * 注册：每 IP 每分钟的上限，默认 5
     *
     * 比登录更严，因为注册接口还是账号存在性探测器，限流是防批量扫描的主要手段。
     */
    private Integer registerMinuteLimit = 5;

    /**
     * 注册：每 IP 每天的上限，默认 20，与分钟窗口配套，挡慢速扫号
     */
    private Integer registerDailyLimit = 20;
}
