package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 帖子模块配置（对应 application.yml 的 studio.post 段）
 *
 * 与 AiProperties / AttendanceProperties 同一纪律：
 * 配置项全部给默认值，不做必填校验。帖子是附加能力，
 * 配置没写全时应用必须能正常启动；做成必填的话，一个缺失的环境变量会让整个站点起不来。
 *
 * 注意：本类不能把字段声明成 final。
 * @ConfigurationProperties 的绑定依赖 setter 与无参构造，
 * 字段一旦 final 就会让配置绑定彻底失效——这与「依赖注入统一用构造器注入」是两回事，
 * 不要混为一谈（注入方可以是 final，被绑定的配置类不可以）。
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
     * 关闭（false）时所有提交的内容一律直接转人工审核，完全不调用模型。
     * 它有两个用途：一是本地开发不配 AI Key 时也能跑通发帖流程，
     * 二是线上模型出故障时的紧急降级开关（改配置重启即可，不必改代码）。
     *
     * 与 AiRateLimitManager 的降级纪律是同一条原则：
     * 防护手段不能反过来变成故障源——模型不可用时宁可让管理员多看几条，
     * 也不能不让用户发帖、更不能把风险内容直接放行。
     */
    private boolean aiAuditEnabled = true;

    /**
     * 送审正文的最大长度（超出部分截断后再交给模型）
     *
     * 两个原因：一是长文的 token 消耗大，二是模型有上下文窗口上限。
     * 截断只发生在送审这一步，入库的正文不受任何影响——
     * 用户看到的仍是完整内容，不存在「发了长文结果被存成一半」的情况。
     *
     * 截断后会在送审消息里注明「（后文略）」，让模型知道它看到的是片段，
     * 免得它因为「文章看起来没头没尾」而误判成灌水。
     */
    private int auditContentMaxLength = 2000;
}
