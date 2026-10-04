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
     * 系统提示词（system prompt）的<b>位置</b>，而不是提示词本身
     *
     * <p><b>为什么只放位置、不放内容</b>：提示词是<b>内容资源</b>（几百字的话术、带分节标题），
     * 不是配置项——把它塞进 yml 会有三个问题：
     * <ul>
     *     <li>yml 里写多行文本要处理缩进与转义，改一个标点容易改坏格式；</li>
     *     <li>话术和真正的配置（地址、密钥、超参）混在一起，review 时看不出哪个是调参；</li>
     *     <li>想改话术就得改配置并重启/重新发布配置中心。</li>
     * </ul>
     * 配置只回答一个问题：<b>提示词在哪</b>。
     *
     * <p><b>两种写法与部署方式的对应</b>：
     * <ul>
     *     <li>{@code classpath:prompts/system-prompt.txt}（默认）：随包发布，
     *     版本与代码一致，不会「代码回滚了话术没回滚」；</li>
     *     <li>{@code file:/opt/.../system-prompt.txt}：指向服务器文件，
     *     改话术<b>不用重新打包</b>，运维直接编辑文件后重启即可生效。
     *     想这样部署时设环境变量 {@code AI_SYSTEM_PROMPT_LOCATION=file:/opt/.../system-prompt.txt}。</li>
     * </ul>
     *
     * <p><b>加载时机</b>：AiManager 在启动时读一次（见其 {@code @PostConstruct}），
     * 因此改完文件需要重启；文件缺失时不抛异常，只是没有 system 消息，聊天仍可用。
     */
    private String systemPromptLocation = "classpath:prompts/system-prompt.txt";

    /**
     * 单个用户的提问次数上限（登录用户才计数，游客不计）
     *
     * <p>预检命中时返回 42900。默认 100 与 DESIGN 的口径一致
     */
    private Integer queryLimit = 100;

    /**
     * 向量模型（Embedding）名称，与对话模型分开配置
     *
     * <p>为什么分开：两者是两类完全不同的模型——对话模型按"生成"计费、向量模型按"入库文本量"计费，
     * 各自的最佳型号与价格都不一样，绑成一个键就没法单独调优或换供应商。
     */
    private String embeddingModel = "text-embedding-3-small";

    /**
     * RAG 检索最多取回多少条资料块
     *
     * <p>它是「给模型多少上下文」与「噪声」之间的权衡：
     * 取太少会漏掉真正相关的资料；取太多则把勉强相关的块也塞进 prompt，
     * 既稀释重点、又直接按 token 计费。5 条是官网站点这种规模（文档几十篇）的经验起点。
     */
    private Integer ragTopK = 5;

    /**
     * RAG 检索的相似度下限：低于此分的命中直接丢弃
     *
     * <p><b>它是下限，越高越严格</b>——这一点极容易记反，改配置前先想清楚方向。
     * 设成 0 等于不过滤（会把完全不相关的块也塞给模型，回答质量反而下降）；
     * 设得太高（如 0.9）则可能一条都不命中，RAG 静默退化成普通聊天。
     *
     * <p><b>⚠️ 这里的「相似度」不是余弦相似度本身</b>：LangChain4j 的
     * {@code minScore} 比较的是 <b>relevance score = (1 + cosine) / 2</b>，
     * 取值区间从余弦的 [-1, 1] 被线性映射到 [0, 1]。
     * 于是：<b>0.5 对应「正交」（余弦 0，两者毫不相关）而不是「有点相关」</b>，
     * 0.75 才对应余弦 0.5（弱相关），1.0 才是完全相同。
     * 把它当成余弦相似度来调，会把阈值整体压低一半，放进大量噪声。
     *
     * <p>默认 0.75（余弦 0.5）是「只要弱相关以上」的起点，不是拍脑袋的值。
     * 不同向量模型的分值分布并不可比，<b>换向量模型后必须重新校准</b>
     * （校准方法：拿 10 个真实问题看命中率与相关性，从 0.75 上下调整）。
     */
    private Double ragMinScore = 0.75;

    /**
     * 游客（未登录）每 IP 每分钟的提问上限
     *
     * <p>游客没有身份，配额无从谈起，只能按 IP 兜底。5 次/分钟挡的是<b>脚本连续刷</b>；
     * 它挡不住「每分钟 4 次、持续一整天」的慢速占用——那个由 {@link #guestIpDailyLimit} 负责。
     *
     * <p>设为 {@code null} 或 {@code <= 0} 表示关闭该层窗口。
     */
    private Integer guestIpMinuteLimit = 5;

    /**
     * 游客每 IP 每天的提问上限
     *
     * <p>分钟窗口与日窗口是<b>两种不同攻击形态</b>的对策，缺一都有明显漏洞，
     * 不要因为「有了分钟限流」就省略日限流。
     */
    private Integer guestIpDailyLimit = 50;

    /**
     * 游客 IP 限流总开关（默认 true）
     *
     * <p>为什么要有开关：<b>测试环境必须能关掉它</b>。
     * 既有的匿名提问用例非常多，一旦限流默认开启，同一 IP（MockMvc 下都是 127.0.0.1）
     * 会在跑到第 6 个用例时集体失败——那是假失败，会掩盖真实问题。
     * 因此 surefire 里把它置为 false，只有限流专项测试类用
     * {@code @DynamicPropertySource} 覆盖为 true。
     */
    private Boolean guestIpLimitsEnabled = true;

    /**
     * 切分块的最大字符数
     *
     * <p>500 是 RAG 的经验起点：块太小则语义不完整（检索命中却答非所问），
     * 块太大则单块塞进太多主题（向量被稀释，匹配精度下降）。
     * 它同时决定了「一次入库会产生多少个向量」——也就决定了成本。
     */
    private Integer chunkMaxSize = 500;

    /**
     * 相邻块之间的重叠字符数
     *
     * <p>为什么需要重叠：切分是"硬切"，一句话恰好跨在边界上时，
     * 不加重叠就会把完整语义劈成两半。重叠 50 字让相邻块共享一小段上下文，
     * 命中任一块都能拿到完整意思。代价是文本量略增（约 10%），属可接受范围。
     */
    private Integer chunkOverlap = 50;
}
