package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 考勤模块配置（对应 application.yml 的 studio.attendance 段）
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.attendance")
public class AttendanceProperties {

    /**
     * 可信反向代理地址，支持单个 IP 或 CIDR；默认 127.0.0.1 与 ::1
     *
     * 默认值覆盖「Nginx 与应用同机部署」；Nginx 在另一台机器上时必须把它的地址加进来。
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

    /** 同一用户两次签到的最小间隔（秒），默认 60 */
    private Integer minIntervalSeconds = 60;
}
