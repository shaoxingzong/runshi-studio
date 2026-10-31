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

    /**
     * 帖子正文允许引用的站外图片域名（逗号分隔，可写成带协议的完整地址）
     *
     * <p>接入 OSS / CDN 后填其域名，正文里的绝对地址才不会被图片校验拒掉；
     * 留空时只允许编辑器上传的 /uploads/ 站内图片（安全默认值）。</p>
     */
    private String allowedImageHosts = "";
}
