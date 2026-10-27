package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 帖子模块配置（对应 application.yml 的 studio.post 段）
 *
 * 配置项全部给默认值、不做必填校验：帖子是附加能力，配置没写全时应用必须能正常启动。
 *
 * 注意字段不能声明成 final。@ConfigurationProperties 的绑定依赖 setter 与无参构造，
 * 字段一旦 final 就会让绑定彻底失效——这与「依赖注入统一用构造器注入」是两回事，
 * 注入方可以是 final，被绑定的配置类不可以。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.post")
public class PostProperties {

    /**
     * 是否启用 AI 内容审核（三分法：安全放行 / 违规驳回 / 灰色转人工）
     *
     * 关闭时所有内容一律直接转人工，完全不调用模型。两个用途：本地开发不配 AI Key 时
     * 也能跑通发帖流程；线上模型故障时作为紧急降级开关（改配置重启即可）。
     *
     * 与限流器同一条原则：防护手段不能反过来变成故障源——模型不可用时宁可让管理员多看几条，
     * 也不能不让用户发帖、更不能把风险内容直接放行。
     */
    private boolean aiAuditEnabled = true;

    /**
     * 送审正文的最大长度（超出部分截断后再交给模型）
     *
     * 两个原因：长文的 token 消耗大，模型也有上下文窗口上限。截断只发生在送审这一步，
     * 入库与展示的正文不受影响。
     *
     * 截断后会在送审消息里注明这是节选，免得模型因为「文章没头没尾」而误判成灌水。
     */
    private int auditContentMaxLength = 2000;
}
