package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传配额配置（对应 application.yml 的 studio.upload 段）
 *
 * 为什么需要配额：上传接口只要求登录，单文件 10MB，不设上限时任何注册账号都能把磁盘当免费图床。
 * 单文件大小由 Spring 的 multipart 配置兜底，这里限制的是「每天能传几个」——
 * 前者防一次传爆，后者防一天传满。
 *
 * 默认值刻意保守（20 次/天）：正常发帖插图一天用不到几张，调试时也能立刻感知到限制生效。
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

    /**
     * 每个账号每天的允许上传次数，默认 20
     *
     * 小于等于 0 表示不限制（与 enabled=false 等价，保留两种写法是因为运维习惯不同）。
     */
    private Integer dailyLimit = 20;
}
