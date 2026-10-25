package com.bhu.runshistudioweb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 咨询模块配置（对应 application-dev.yml 的 studio.ai 段）
 *
 * 为什么用 @ConfigurationProperties 而不是在类里写一堆 @Value：
 * - 一组相关配置集中在一个类里，改名/加字段时 IDE 能改名重构，@Value("${...}")
 * 的字符串只能靠全局搜索；
 * - base-url 必须来自配置（本任务的关键约束）：AiManager 里绝不能硬编码服务商地址，
 * 否则测试无法把它指向本地 Stub 服务——这也是本类存在的头号理由。
 *
 * 每一个字段都有默认值，这是刻意的：AI 是「锦上添花」的能力，
 * 本地开发者不配 API Key 时，应用必须能正常启动、其它接口照常可用，
 * 只有真正调用 AI 时才返回 C0200。若把字段设成必填（无默认值 + 校验），
 * 一个缺失的环境变量就会让整个应用起不来——那是本末倒置。
 *
 * api-key 只从环境变量注入（ AI_API_KEY），不写进仓库：
 * 配置里的写法是 ${AI_API_KEY:}，冒号后留空表示「没有就留空」。
 * 密钥一旦提交进 Git，等于永久泄露（历史提交里删不干净）。
 *
 * @author shaoshing
 */
@Data
@Component
@ConfigurationProperties(prefix = "studio.ai")
public class AiProperties {

    /**
     * OpenAI 兼容协议的服务端地址，不带 /chat/completions
     * （例如 https://api.deepseek.com/v1），路径由 AiManager 拼接。
     *
     * 默认为空字符串：为空时 AiManager 会在调用前直接返回 C0200，
     * 而不是让 RestClient 抛一个「URI 不合法」的技术异常
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
     * <p><b>为什么必须有这个配置项</b>：
     * 推理模型（如 {@code deepseek-v4-pro}）会先输出一大段 {@code reasoning_content}
     * （思考过程），<b>这部分同样消耗 max_tokens 额度</b>。
     * 不显式设置时用的是服务商默认值，推理模型在 max_tokens=800 时
     * 800 个 token 全被思考过程耗尽、正文 {@code content} 为空，
     * 于是 {@code AiManager#extractAnswer} 判定「回答为空」并抛 C0200——
     * 用户看到的是「AI 服务暂时不可用」，而真实原因只是额度被思考吃光了。
     *
     * <p>给 4096 是「思考 + 正文」都够用的量：简单问题思考约 100–800 token、
     * 正文几十到几百 token，4096 有充分余量；同时它也是多数 OpenAI 兼容服务的常见上限，
     * 不会因为超限被服务端直接拒绝。
     *
     * <p>对非推理模型（flash / qwen-plus 等）它只是一个宽松的上限，不会改变行为。
     * 设成 null 或 <= 0 表示「不传该参数」，回到服务商默认值——
     * 换回非推理模型且想省钱时可以这样配。
     */
    private Integer maxTokens = 4096;

    /**
     * 系统提示词（system prompt）的位置，而不是提示词本身
     *
     * 为什么只放位置、不放内容：提示词是内容资源（几百字的话术、带分节标题），
     * 不是配置项——把它塞进 yml 会有三个问题：
     * - yml 里写多行文本要处理缩进与转义，改一个标点容易改坏格式；
     * - 话术和真正的配置（地址、密钥、超参）混在一起，review 时看不出哪个是调参；
     * - 想改话术就得改配置并重启/重新发布配置中心。
     * 配置只回答一个问题：提示词在哪。
     *
     * 两种写法与部署方式的对应：
     * - classpath:prompts/system-prompt.txt（默认）：随包发布，
     * 版本与代码一致，不会「代码回滚了话术没回滚」；
     * - file:/opt/.../system-prompt.txt：指向服务器文件，
     * 改话术不用重新打包，运维直接编辑文件后重启即可生效。
     * 想这样部署时设环境变量 AI_SYSTEM_PROMPT_LOCATION=file:/opt/.../system-prompt.txt。
     *
     * 加载时机：AiManager 在启动时读一次（见其 @PostConstruct），
     * 因此改完文件需要重启；文件缺失时不抛异常，只是没有 system 消息，聊天仍可用。
     */
    private String systemPromptLocation = "classpath:prompts/system-prompt.txt";

    /**
     * 熔断打开前允许的连续失败次数
     *
     * 单次失败可能是瞬时抖动（网络、服务商限流），连续 N 次才认定为持续性故障。
     * 3 次是「不漏判故障」与「不把抖动当故障」的折中；服务商确实不稳定时可调大。
     */
    private int circuitBreakerFailureThreshold = 3;

    /**
     * 熔断持续时间（秒）
     *
     * 到期后放行一个请求试探（半开），成功即闭合。取 300 秒（5 分钟）：
     * 短于故障自愈周期会反复打开，长于它则用户干等。
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
     * 预检命中时返回 A0501。默认 5 次：AI 服务对游客关闭，
     * 登录用户可免费试用 5 次，成员 / 管理员不限次
     */
    private Integer queryLimit = 5;

    /**
     * 向量模型（Embedding）名称，与对话模型分开配置
     *
     * 为什么分开：两者是两类完全不同的模型——对话模型按"生成"计费、向量模型按"入库文本量"计费，
     * 各自的最佳型号与价格都不一样，绑成一个键就没法单独调优或换供应商。
     */
    private String embeddingModel = "text-embedding-3-small";

    /**
     * RAG 检索最多取回多少条资料块
     *
     * 它是「给模型多少上下文」与「噪声」之间的权衡：
     * 取太少会漏掉真正相关的资料；取太多则把勉强相关的块也塞进 prompt，
     * 既稀释重点、又直接按 token 计费。5 条是官网站点这种规模（文档几十篇）的经验起点。
     */
    private Integer ragTopK = 5;

    /**
     * RAG 检索的相似度下限：低于此分的命中直接丢弃
     *
     * 它是下限，越高越严格——这一点极容易记反，改配置前先想清楚方向。
     * 设成 0 等于不过滤（会把完全不相关的块也塞给模型，回答质量反而下降）；
     * 设得太高（如 0.9）则可能一条都不命中，RAG 静默退化成普通聊天。
     *
     * 这里的「相似度」不是余弦相似度本身：LangChain4j 的
     * minScore 比较的是 relevance score = (1 + cosine) / 2，
     * 取值区间从余弦的 [-1, 1] 被线性映射到 [0, 1]。
     * 于是：0.5 对应「正交」（余弦 0，两者毫不相关）而不是「有点相关」，
     * 0.75 才对应余弦 0.5（弱相关），1.0 才是完全相同。
     * 把它当成余弦相似度来调，会把阈值整体压低一半，放进大量噪声。
     *
     * 默认 0.75（余弦 0.5）是「只要弱相关以上」的起点，不是拍脑袋的值。
     * 不同向量模型的分值分布并不可比，换向量模型后必须重新校准
     * （校准方法：拿 10 个真实问题看命中率与相关性，从 0.75 上下调整）。
     */
    private Double ragMinScore = 0.75;

    /**
     * 游客（未登录）每 IP 每分钟的提问上限
     *
     * 游客没有身份，配额无从谈起，只能按 IP 兜底。5 次/分钟挡的是脚本连续刷；
     * 它挡不住「每分钟 4 次、持续一整天」的慢速占用——那个由 guestIpDailyLimit 负责。
     *
     * 设为 null 或 <= 0 表示关闭该层窗口。
     */
    private Integer guestIpMinuteLimit = 5;

    /**
     * 游客每 IP 每天的提问上限
     *
     * 分钟窗口与日窗口是两种不同攻击形态的对策，缺一都有明显漏洞，
     * 不要因为「有了分钟限流」就省略日限流。
     */
    private Integer guestIpDailyLimit = 50;

    /**
     * 游客 IP 限流总开关（默认 true）
     *
     * 为什么要有开关：测试环境必须能关掉它。
     * 既有的匿名提问用例非常多，一旦限流默认开启，同一 IP（MockMvc 下都是 127.0.0.1）
     * 会在跑到第 6 个用例时集体失败——那是假失败，会掩盖真实问题。
     * 因此 surefire 里把它置为 false，只有限流专项测试类用
     * @DynamicPropertySource 覆盖为 true。
     */
    private Boolean guestIpLimitsEnabled = true;

    /**
     * 切分块的最大字符数
     *
     * 500 是 RAG 的经验起点：块太小则语义不完整（检索命中却答非所问），
     * 块太大则单块塞进太多主题（向量被稀释，匹配精度下降）。
     * 它同时决定了「一次入库会产生多少个向量」——也就决定了成本。
     */
    private Integer chunkMaxSize = 500;

    /**
     * 相邻块之间的重叠字符数
     *
     * 为什么需要重叠：切分是"硬切"，一句话恰好跨在边界上时，
     * 不加重叠就会把完整语义劈成两半。重叠 50 字让相邻块共享一小段上下文，
     * 命中任一块都能拿到完整意思。代价是文本量略增（约 10%），属可接受范围。
     */
    private Integer chunkOverlap = 50;
}
