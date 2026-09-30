package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 咨询模块配置（对应 {@code application-dev.yml} 的 {@code studio.ai} 段）
 *
 * author: shaoshing
 *
 * <p>为什么用 {@code @ConfigurationProperties} 而不是在类里写一堆 {@code @Value}：
 * <ul>
 *     <li>一组相关配置集中在一个类里，改名/加字段时 IDE 能改名重构，{@code @Value("${...}")}
 *     的字符串只能靠全局搜索；</li>
 *     <li><b>base-url 必须来自配置</b>（本任务的关键约束）：AiManager 里绝不能硬编码服务商地址，
 *     否则测试无法把它指向本地 Stub 服务——这也是本类存在的头号理由。</li>
 * </ul>
 *
 * <p><b>每一个字段都有默认值，这是刻意的</b>：AI 是「锦上添花」的能力，
 * 本地开发者不配 API Key 时，应用必须能正常启动、其它接口照常可用，
 * 只有真正调用 AI 时才返回 50001。若把字段设成必填（无默认值 + 校验），
 * 一个缺失的环境变量就会让整个应用起不来——那是本末倒置。
 *
 * <p><b>api-key 只从环境变量注入</b>（{@code AI_API_KEY}），不写进仓库：
 * 配置里的写法是 {@code ${AI_API_KEY:}}，冒号后留空表示「没有就留空」。
 * 密钥一旦提交进 Git，等于永久泄露（历史提交里删不干净）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.ai")
public class AiProperties {

    /**
     * OpenAI 兼容协议的服务端地址，<b>不带</b> {@code /chat/completions}
     * （例如 {@code https://api.deepseek.com/v1}），路径由 AiManager 拼接。
     *
     * <p>默认为空字符串：为空时 AiManager 会在调用前直接返回 50001，
     * 而不是让 RestClient 抛一个「URI 不合法」的技术异常
     */
    private String baseUrl = "";

    /**
     * 服务商 API Key（环境变量 {@code AI_API_KEY} 注入）
     *
     * <p>绝不落盘到仓库；日志里也绝不能打印它
     */
    private String apiKey = "";

    /**
     * 模型名（如 {@code deepseek-chat}、{@code gpt-4o-mini}）
     */
    private String model = "deepseek-chat";

    /**
     * 系统提示词（system prompt）：约束 AI 的身份、语气与边界
     *
     * <p>放配置而不是硬编码，是为了「调语气不用改代码、不用重新打包」——
     * 提示词是运营话术，不是业务逻辑
     */
    private String systemPrompt = "你是润石工作室官网的智能助手，请用简洁友好的中文回答，不确定时明确说明。";

    /**
     * 单个用户的提问次数上限（登录用户才计数，游客不计）
     *
     * <p>预检命中时返回 42900。默认 100 与 DESIGN 的口径一致
     */
    private Integer queryLimit = 100;
}
