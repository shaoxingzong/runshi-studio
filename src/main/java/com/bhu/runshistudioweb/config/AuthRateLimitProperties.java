package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录 / 注册防爆破限流配置（对应 application.yml 的 studio.auth.rate-limit 段）
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.auth.rate-limit")
public class AuthRateLimitProperties {

    /** 总开关（默认 true） */
    private Boolean enabled = true;

    /** 登录：每 IP 每分钟的尝试上限，默认 10 */
    private Integer loginMinuteLimit = 10;

    /** 登录：每 IP 每天的尝试上限，默认 100 */
    private Integer loginDailyLimit = 100;

    /** 注册：每 IP 每分钟的上限，默认 5 */
    private Integer registerMinuteLimit = 5;

    /** 注册：每 IP 每天的上限，默认 20，与分钟窗口配套，挡慢速扫号 */
    private Integer registerDailyLimit = 20;
}
