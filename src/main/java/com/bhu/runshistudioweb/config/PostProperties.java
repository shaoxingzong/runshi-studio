package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 帖子模块配置（对应 application.yml 的 studio.post 段）
 *
 * 配置项全部给默认值、不做必填校验：帖子是附加能力，配置没写全时应用必须能正常启动。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.post")
public class PostProperties {

    /** 是否启用 AI 内容审核（三分法：安全放行 / 违规驳回 / 灰色转人工） */
    private boolean aiAuditEnabled = true;

    /**
     * 送审正文的最大长度（超出部分截断后再交给模型）
     *
     * 截断后会在送审消息里注明这是节选，免得模型因为「文章没头没尾」而误判成灌水。
     */
    private int auditContentMaxLength = 2000;
}
