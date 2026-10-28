package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 咨询模块配置（对应 application.yml 的 studio.ai 段）
 *
 * 用 @ConfigurationProperties 而不是一堆 @Value：一组相关配置集中在一处，改名时 IDE 能重构。
 *
 * 每个字段都有默认值：AI 是锦上添花的能力，本地不配 API Key 时应用必须能正常启动、
 * 只有真正调用 AI 时才返回 C0200。做成必填的话，一个缺失的环境变量就会让整个应用起不来。
 *
 * api-key 只从环境变量注入（${AI_API_KEY:}），不写进仓库：密钥一旦提交进 Git 等于永久泄露。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.ai")
public class AiProperties {

    /**
     * OpenAI 兼容协议的服务端地址，不带 /chat/completions（如 https://api.deepseek.com/v1）
     *
     * 默认为空：为空时 AiManager 在调用前直接返回 C0200，而不是让底层抛「URI 不合法」的技术异常
     */
    private String baseUrl = "";

    /**
     * 服务商 API Key（环境变量 AI_API_KEY 注入）
     *
     * 绝不落盘到仓库；日志里也绝不能打印它
     */
    private String apiKey = "";

    /** 模型名（如 deepseek-chat、 gpt-4o-mini） */
    private String model = "deepseek-chat";

    /**
     * 单次回答的最大输出 token 数
     *
     * 必须有这个配置项：推理模型会先输出一大段思考过程，这部分同样消耗 max_tokens 额度。
     * 不显式设置时用的是服务商默认值，思考会把额度吃光、正文为空，于是被判成「回答为空」
     * 抛 C0200——用户看到「AI 服务暂时不可用」，真实原因只是额度被思考用完了。
     *
     * 设成 null 或 <= 0 表示不传该参数，回到服务商默认值。
     */
    private Integer maxTokens = 4096;

    /**
     * 系统提示词的位置，而不是提示词本身
     *
     * 提示词是内容资源而不是配置项：塞进 yml 要处理多行缩进与转义，还会和真正的调参混在一起。
     * 配置只回答一个问题：提示词在哪。
     *
     * 两种写法对应两种部署：classpath: 随包发布，版本与代码一致；file: 指向服务器文件，
     * 改话术不用重新打包。加载时机是启动时读一次，因此改完文件需要重启。
     */
    private String systemPromptLocation = "classpath:prompts/system-prompt.txt";

    /**
     * 熔断打开前允许的连续失败次数（默认 3）
     *
     * 单次失败可能是瞬时抖动，连续 N 次才认定为持续性故障。3 次是「不漏判故障」与
     * 「不把抖动当故障」的折中；服务商确实不稳定时可调大。
     */
    private int circuitBreakerFailureThreshold = 3;

    /**
     * 熔断持续时间（秒，默认 300）
     *
     * 到期后放行一个请求试探，成功即闭合。短于故障自愈周期会反复打开，长于它则用户干等。
     */
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

    /**
     * 普通登录用户的提问次数上限（成员与管理员不限额、也不计数）
     *
     * 预检命中时返回 A0501。默认 5 次：登录用户可免费试用 5 次。
     */
    private Integer queryLimit = 5;

    /**
     * 向量模型（Embedding）名称，与对话模型分开配置
     *
     * 两者是两类完全不同的模型：对话按生成计费、向量按入库文本量计费，
     * 各自的最佳型号与价格都不一样，绑成一个键就没法单独调优或换供应商。
     */
    private String embeddingModel = "text-embedding-3-small";

    /**
     * 向量库类型：memory（内存，开发/测试）或 redis（Redis Query Engine，生产）
     *
     * 保留 memory 是为了本地开发不必为改一行 RAG 代码去装 Redis 的 Query Engine/JSON 模块，
     * 单元测试也固定在内存库上。默认值刻意选 memory，保证默认配置不要求额外组件。
     *
     * 注意持久化能力完全不同：memory 重启即丢，redis 才会在重启后保留向量。
     */
    private String vectorStoreType = "memory";

    /**
     * 启动时是否比对「切分块数」与「向量数」，不一致时自动重建
     *
     * 索引被清空、向量模型换了、或历史残留导致数量不一致时，检索会静默返回空。
     * 启动时自动发现并重建，比要求管理员记得点「重建向量」可靠。
     *
     * 测试环境由 surefire 置为 false：内存库为空是正常状态，开启会误判为索引缺失
     * 并触发真实的 Embedding 调用。
     */
    private Boolean ragStartupCheckEnabled = true;

    /**
     * RAG 检索最多取回多少条资料块
     *
     * 它是「给模型多少上下文」与「噪声」之间的权衡：取太少会漏掉真正相关的资料，
     * 取太多则把勉强相关的块也塞进 prompt，既稀释重点、又按 token 计费。
     */
    private Integer ragTopK = 5;

    /**
     * RAG 检索的相似度下限：低于此分的命中直接丢弃，越高越严格
     *
     * 坑：这里的「相似度」不是余弦相似度本身，而是 relevance score = (1 + cosine) / 2。
     * 0.5 对应「正交」（毫不相关）而不是「有点相关」，0.75 才对应余弦 0.5。
     * 把它当余弦来调会把阈值整体压低一半，放进大量噪声。
     *
     * 不同向量模型的分值分布不可比，换模型后必须拿真实问题重新校准。
     */
    private Double ragMinScore = 0.75;

    /**
     * 游客每 IP 每分钟的提问上限
     *
     * 游客没有身份，只能按 IP 兜底。它挡的是脚本连续刷，挡不住「每分钟 4 次、持续一整天」
     * 的慢速占用——那个由 {@code guestIpDailyLimit} 负责。设为 null 或 <= 0 表示关闭该窗口。
     */
    private Integer guestIpMinuteLimit = 5;

    /**
     * 游客每 IP 每天的提问上限
     *
     * 分钟窗口与日窗口是两种攻击形态的对策，缺一都有明显漏洞。
     */
    private Integer guestIpDailyLimit = 50;

    /**
     * 游客 IP 限流总开关（默认 true）
     *
     * 测试环境必须能关掉它：MockMvc 下所有请求同源，不关会在跑到第 6 个用例时集体失败，
     * 那是假失败，会掩盖真实问题。
     */
    private Boolean guestIpLimitsEnabled = true;

    /**
     * 切分块的最大字符数
     *
     * 块太小则语义不完整（检索命中却答非所问），块太大则单块塞进太多主题、向量被稀释。
     * 它同时决定了「一次入库会产生多少个向量」，也就决定了成本。
     */
    private Integer chunkMaxSize = 500;

    /**
     * 相邻块之间的重叠字符数
     *
     * 切分是硬切，一句话恰好跨在边界上时不加重叠就会把语义劈成两半。
     * 代价是文本量略增（约 10%），属可接受范围。
     */
    private Integer chunkOverlap = 50;

    /**
     * 创作助手系统提示词位置（AI 辅助发帖用）
     *
     * 与问答的 system prompt 分开：写作人设（篇幅、结构、Markdown 规范）和咨询人设
     * 是两套行为约束，共用一个会让模型在「润色」时也端着咨询腔调。
     * 与 systemPromptLocation 一样支持 classpath: 与 file: 两种写法。
     */
    private String composeSystemPromptLocation = "classpath:prompts/compose-system-prompt.txt";

    /**
     * 生成标题 / 摘要时参考的「历史优质帖」条数
     *
     * 取本站已通过审核、按点赞数排序的前 N 篇，作为 few-shot 风格示例。
     * 设为 0 表示不参考历史帖（只靠知识库事实与创作规范）。
     *
     * <p>为什么用「高赞已通过帖」而不是把历史帖也向量化：
     * 风格示例只需要「标题 + 摘要」这种短文本，一条 SQL 就能取到；
     * 为它单独建一条向量化管道，成本远大于收益。
     */
    private Integer composeStyleExampleCount = 3;
}
