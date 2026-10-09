package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 帖子模块配置（对应 {@code application.yml} 的 {@code studio.post} 段）
 *
 * author: shaoshing
 *
 * <p>与 {@link AiProperties} / {@link AttendanceProperties} 同一纪律：
 * 配置项<b>全部给默认值</b>，不做必填校验。帖子是附加能力，
 * 配置没写全时应用必须能正常启动；做成必填的话，一个缺失的环境变量会让整个站点起不来。
 *
 * <p><b>注意：本类不能把字段声明成 final</b>。
 * {@code @ConfigurationProperties} 的绑定依赖 setter 与无参构造，
 * 字段一旦 final 就会让配置绑定彻底失效——这与「依赖注入统一用构造器注入」是两回事，
 * 不要混为一谈（注入方可以是 final，被绑定的配置类不可以）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.post")
public class PostProperties {

    /**
     * 敏感词库文件位置，默认 {@code classpath:sensitive-words.txt}
     *
     * <p>与 AI 提示词（{@link AiProperties#getSystemPromptLocation()}）同一套路：
     * 支持 {@code classpath:} 与 {@code file:} 两种前缀，
     * 因此「随包发布」和「指向服务器文件」两种部署方式不用改代码，只改配置——
     * 词库是要经常增补的东西，放在包里意味着每次加词都要重新发版。
     *
     * <p>文件缺失时的行为是<b>降级为不启用敏感词过滤</b>并打 warn，不是启动失败：
     * 见 {@code SensitiveWordManager} 的「安全侧降级」说明。
     */
    private String sensitiveWordsLocation = "classpath:sensitive-words.txt";
}
