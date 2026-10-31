package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传配额配置（对应 application.yml 的 studio.upload 段）
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.upload")
public class UploadProperties {

    /**
     * 总开关（默认 true）
     *
     * 与限流配置同样的理由：测试环境必须能关掉，否则同一账号跑几个上传用例就撞上限。
     */
    private Boolean enabled = true;

    /** 每个账号每天的允许上传次数，默认 20 */
    private Integer dailyLimit = 20;
}
