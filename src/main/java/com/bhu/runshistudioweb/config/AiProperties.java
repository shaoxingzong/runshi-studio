package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 咨询模块配置（对应 application.yml 的 studio.ai 段）
 *
 * api-key 只从环境变量注入（${AI_API_KEY:}），不写进仓库：密钥一旦提交进 Git 等于永久泄露。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.ai")
public class AiProperties {

    /** OpenAI 兼容协议的服务端地址，不带 /chat/completions（如 https://api.deepseek.com/v1） */
    private String baseUrl = "";

    /**
     * 服务商 API Key（环境变量 AI_API_KEY 注入）
     *
     * 绝不落盘到仓库；日志里也绝不能打印它
     */
    private String apiKey = "";

    /** 模型名（如 deepseek-chat、gpt-4o-mini） */
    private String model = "deepseek-chat";

    /** 单次回答的最大输出 token 数 */
    private Integer maxTokens = 4096;

    /** 系统提示词的位置，而不是提示词本身 */
    private String systemPromptLocation = "classpath:prompts/system-prompt.txt";

    /** 熔断打开前允许的连续失败次数（默认 3） */
    private int circuitBreakerFailureThreshold = 3;

    /** 熔断持续时间（秒，默认 300） */
    private long circuitBreakerOpenSeconds = 300L;

    /**
     * 连续失败多少次后打开熔断
     *
     * @return 阈值
     */
    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    /**
     * 设置熔断失败阈值
     *
     * @param circuitBreakerFailureThreshold 阈值
     */
    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
    }

    /**
     * 熔断打开后多久尝试恢复
     *
     * @return 秒数
     */
    public long getCircuitBreakerOpenSeconds() {
        return circuitBreakerOpenSeconds;
    }

    /**
     * 设置熔断打开时长
     *
     * @param circuitBreakerOpenSeconds 秒数
     */
    public void setCircuitBreakerOpenSeconds(long circuitBreakerOpenSeconds) {
        this.circuitBreakerOpenSeconds = circuitBreakerOpenSeconds;
    }

    /** 普通登录用户的提问次数上限（成员与管理员不限额、也不计数） */
    private Integer queryLimit = 5;

    /** 向量模型（Embedding）名称，与对话模型分开配置 */
    private String embeddingModel = "text-embedding-3-small";

    /** 向量库类型：memory（内存，开发/测试）或 redis（Redis Query Engine，生产） */
    private String vectorStoreType = "memory";

    /** 启动时是否比对「切分块数」与「向量数」，不一致时自动重建 */
    private Boolean ragStartupCheckEnabled = true;

    /** RAG 检索最多取回多少条资料块 */
    private Integer ragTopK = 5;

    /**
     * RAG 检索的相似度下限：低于此分的命中直接丢弃，越高越严格
     *
     * 不同向量模型的分值分布不可比，换模型后必须拿真实问题重新校准。
     */
    private Double ragMinScore = 0.75;

    /** 游客每 IP 每分钟的提问上限 */
    private Integer guestIpMinuteLimit = 5;

    /** 游客每 IP 每天的提问上限 */
    private Integer guestIpDailyLimit = 50;

    /** 游客 IP 限流总开关（默认 true） */
    private Boolean guestIpLimitsEnabled = true;

    /** 切分块的最大字符数 */
    private Integer chunkMaxSize = 500;

    /** 相邻块之间的重叠字符数 */
    private Integer chunkOverlap = 50;

    /** 创作助手系统提示词位置（AI 辅助发帖用） */
    private String composeSystemPromptLocation = "classpath:prompts/compose-system-prompt.txt";

    /** 生成标题 / 摘要时参考的「历史优质帖」条数 */
    private Integer composeStyleExampleCount = 3;
}
