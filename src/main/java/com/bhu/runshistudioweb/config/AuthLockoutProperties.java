package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录失败锁定配置（账号级，对应 application.yml 的 studio.auth.lock 段）
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.auth.lock")
public class AuthLockoutProperties {

    /** 总开关（默认 true） */
    private Boolean enabled = true;

    /** 锁定阈值：窗口内连续失败多少次后锁定，默认 5 */
    private Integer maxFailures = 5;

    /** 锁定时长（分钟），默认 15，同时是计数 key 的 TTL */
    private Integer lockMinutes = 15;
}
