package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.ai.AiMessageVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AI 服务调用管理器（Manager 层）—— 基于 LangChain4j
 *
 * <p>职责：把「一段对话」交给模型，取回一段回答。它不认识数据库、不认识业务规则
 * （配额、会话归属都在 Service 层），因此既可以被提问接口调用，也能被将来的
 * 「文档问答」「批量生成」复用，还能对着本地桩服务单测。
 *
 * <p>为什么用 LangChain4j 而不是自己写 HTTP 客户端：本模块需要扩展的地方，
 * 恰好都是它能「只改一个点」的位置——流式输出只需把普通模型换成流式模型；
 * 多轮记忆可以直接换用内置的会话记忆；换服务商时上层只依赖 ChatModel 接口，
 * 改动被限制在本类的构建方法里。
 *
 * <p>为什么不用 LangChain4j 的 Spring Boot starter：它面向 Boot 3，而本项目是 Boot 4。
 * 本项目自己装配 Bean（配置类 + 本类），只用 studio.ai 一份配置，
 * 不依赖 starter 的键名约定，也不受其自动配置与本项目版本之间的兼容性影响。
 *
 * <p>三个必须踩准的点：
 * <ul>
 *     <li>base-url 必须来自配置：硬编码服务商地址会让测试无法指向本地桩服务，
 *     也会让「换服务商」变成改代码；</li>
 *     <li>超时必须显式设置：LangChain4j 的超时是「单次请求」的总超时，
 *     不设就沿用其默认值（对匿名接口而言太长）；这里压到 60 秒，
 *     并用最大重试次数限制「超时 + 重试」的累计等待；</li>
 *     <li>不开启请求 / 响应日志：开启请求日志会把 HTTP 请求打出来，其中含
 *     Authorization 头（API Key）；开启响应日志会打出用户对话内容。
 *     一个是泄密、一个是隐私，故保持关闭。</li>
 * </ul>
 *
 * <p>所有异常都在这里被吃掉并转成 C0200（第三方服务类错误码）：
 * LangChain4j 抛的是它自己的技术异常（服务端错误、超时等），直接放出去会被全局兜底
 * 成 B0001「系统错误」，还可能把服务商返回的原始报文带进日志与响应。
 * 对外统一一句「AI 服务暂时不可用，请稍后重试」。
 *
 * <p>日志纪律：只记录 base-url、模型名与异常，绝不记录 api-key。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiManager {

    /**
     * 单次请求超时（建连与读取合并计算）
     *
     * <p>为什么是 60 秒：LangChain4j 的模型客户端只暴露一个 timeout，
     * 不像裸 HTTP 客户端那样能分别设建连 5 秒、读取 60 秒。
     * 取 60 秒是因为模型生成本身慢，而「建连失败」在 60 秒内同样会失败返回，
     * 只是失败得慢一些——配上最大重试 1 次，最坏情况是两次尝试。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 最大重试次数：1（即最坏两次尝试）
     *
     * <p>LangChain4j 默认会对 5xx 与 429 做重试。对匿名接口而言，
     * 重试会把服务商的故障放大成我们自己的流量与等待，所以压到最小；
     * 真正的稳定性应该靠「快速失败 + 前端重试」而不是在服务端死磕。
     */
    private static final int MAX_RETRIES = 1;

    /** 对外统一的失败文案：不复述底层细节（超时、限流、解析失败在用户眼里是同一件事） */
    private static final String AI_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    /**
     * 熔断器
     *
     * <p>注入而非内部 new：熔断器是有状态的单例，必须与各调用点共享同一份失败计数；
     * 同时也让测试能直接替换它。
     */
    private final AiCircuitBreaker circuitBreaker;

    private final AiProperties aiProperties;

    /**
     * 资源加载器：用来读取系统提示词文件
     *
     * <p>它同时支持 classpath: 与 file: 两种前缀，
     * 因此「随包发布」和「指向服务器文件」两种部署方式不用改代码，只改配置。
     */
    private final ResourceLoader resourceLoader;

    /**
     * 系统提示词内容：启动时从配置指定的位置读一次，之后不变
     *
     * <p>默认为空字符串而不是 null：配合判空工具使用，
     * 空值就是「这次请求不带 system 消息」，不用再单独判一次 null。
     */
    private String systemPrompt = "";

    /**
     * 对话模型：只依赖 ChatModel 接口，不持有具体实现
     *
     * <p>将来换流式模型或换服务商时，改动被限制在初始化方法里。
     */
    private ChatModel chatModel;

    /**
     * 流式模型（SSE 接口用），同样只依赖 LangChain4j 的 StreamingChatModel 接口
     *
     * <p>它与普通模型是两个独立的构建器——这不是本项目的选择，
     * 而是 LangChain4j 的设计（同步与流式是两条实现路径）。
     * 因此本类同时持有两个模型实例，配置项（地址、密钥、模型名、超时）完全一致。
     */
    private StreamingChatModel streamingChatModel;

    /**
     * 向量模型（Embedding）：把文本变成向量，RAG 入库与检索都要用
     *
     * <p>与对话模型一样，只依赖 LangChain4j 的 EmbeddingModel 接口：
     * 将来换向量模型（或换成本地模型）只动初始化方法。
     */
    private EmbeddingModel embeddingModel;

    /**
     * 启动初始化：先加载系统提示词，再构建三个模型客户端
     *
     * <p>为什么这样设计：提示词加载放在最前面、且无条件执行，因为它不依赖 AI 是否配置好。
     * 若放在「配置齐全」的分支里，本地不配 api-key 时提示词就不会被加载，
     * 等线上配置生效后才发现话术是空的，问题会藏得很深。
     * 一个方法建三个模型，是因为它们共用同一份配置，且「未配置」的处理完全一致——
     * 放在一处才能确保「对话可用但向量不可用」这种半配置状态不会出现。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无条件加载系统提示词（与后面是否构建模型无关）；</li>
     *     <li>② 检查地址与密钥是否齐全：不齐就记一条 warn 并直接返回，
     *     三个模型字段保持 null。此时应用照常启动，真正调用时才返回 C0200；</li>
     *     <li>③ 构建普通对话模型：地址、密钥、模型名、输出上限、超时、重试；</li>
     *     <li>④ 构建流式模型：同一套配置，但它没有重试参数（流式下重试语义不同，
     *     已经开始推流后重试会重复输出），所以流式路径最坏就是一次 60 秒的等待；</li>
     *     <li>⑤ 构建向量模型：同一套地址与密钥，但用独立的模型名；</li>
     *     <li>⑥ 打一条汇总日志。只记地址、模型名与参数，绝不记密钥。</li>
     * </ol>
     *
     * <p>容易踩的坑：未配置时「不构建、也不抛异常」是刻意的。若这里抛异常，
     * 本地不配 AI 的人连应用都起不来，而 AI 只是锦上添花的能力。
     * 另一个坑是输出上限（maxTokens）必须显式设置：推理模型会先输出一大段思考过程，
     * 这部分同样消耗额度，不设上限时思考可能把额度吃光、正文为空，
     * 用户看到的是「AI 服务暂时不可用」，而真实原因只是额度被思考用完了。
     */
    @PostConstruct
    void initModels() {
        // 无条件先加载提示词：与后面是否构建模型无关
        loadSystemPrompt();

        if (StrUtil.isBlank(aiProperties.getBaseUrl()) || StrUtil.isBlank(aiProperties.getApiKey())) {
            // 只提示「缺什么」，绝不打印密钥内容
            log.warn("AI 未配置完整（base-url={} / apiKeyConfigured={}），提问接口将返回 C0200；"
                            + "其它接口不受影响", StrUtil.isBlank(aiProperties.getBaseUrl()) ? "空" : "已配置",
                    StrUtil.isNotBlank(aiProperties.getApiKey()));
            return;
        }

        this.chatModel = OpenAiChatModel.builder()
                // base-url 不含 /chat/completions，LangChain4j 会自行拼接（OpenAI 兼容协议）
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getModel())
                // 输出上限：**推理模型必须设**，否则思考过程会吃光额度、正文为空，
                // 表现为「AI 服务暂时不可用」（见 AiProperties#maxTokens 的说明）
                .maxTokens(resolveMaxTokens())
                .timeout(REQUEST_TIMEOUT)
                .maxRetries(MAX_RETRIES)
                // 刻意不开 logRequests / logResponses：前者会打出 Authorization 头（密钥），
                // 后者会打出用户对话内容。排查协议问题时应临时开启并在本地使用，不要带进提交
                .build();

        // 流式模型：与同步模型用同一套配置。
        // 注意 OpenAiStreamingChatModel 的 builder 没有 maxRetries（流式下重试语义不同：
        // 已经开始推流后重试会重复输出），所以流式路径最坏情况就是一次 60s 的等待
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getModel())
                // 流式路径同样要设：SSE 接口是官网主用路径，
                // 不设时推理模型的思考过程会把额度耗尽，前端只能看到「已结束但没内容」
                .maxTokens(resolveMaxTokens())
                .timeout(REQUEST_TIMEOUT)
                .build();

        // 向量模型：同一套 base-url / api-key，但用独立的模型名（studio.ai.embedding-model）。
        // 超时与重试跟对话模型保持一致——向量化同样是一次外部 HTTP，必须设天花板上限
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getEmbeddingModel())
                .timeout(REQUEST_TIMEOUT)
                .maxRetries(MAX_RETRIES)
                .build();

        log.info("AI 客户端初始化完成 | baseUrl={} | model={} | embeddingModel={} | timeout={}s | maxRetries={} | maxTokens={}",
                aiProperties.getBaseUrl(), aiProperties.getModel(), aiProperties.getEmbeddingModel(),
                REQUEST_TIMEOUT.toSeconds(), MAX_RETRIES, resolveMaxTokens());
    }

    /**
     * 解析输出 token 上限；未配置或非法时返回 null（表示不传该参数）
     *
     * <p>为什么允许返回 null：换回非推理模型、想按服务商默认值走时，可以把配置留空或配成 0。
     * LangChain4j 的构建器对 null 的语义就是「不设置」，正好对得上。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 取配置值；</li>
     *     <li>② null 或非正数一律返回 null（不设置），正数原样返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：非法值不报错而是回落成 null，是刻意的。这是「锦上添花」的调参项，
     * 一个写错的数字不该让应用起不来——与配置类所有字段都有默认值是同一条纪律。
     *
     * @return 正数时返回该值；null、0 或负数返回 null
     */
    private Integer resolveMaxTokens() {
        Integer configured = aiProperties.getMaxTokens();
        if (configured == null || configured <= 0) {
            return null;
        }
        return configured;
    }

    /**
     * 启动时加载系统提示词（只读一次）
     *
     * <p>为什么这样设计：同一次运行内提示词不该漂移——否则同一段对话前后两句的语气约束
     * 可能不一致；而且每次提问都读一次文件会产生不必要的 IO（匿名接口，流量不可控）。
     * 代价是改完文件要重启才生效，这与「话术是随包资源」的定位一致；
     * 需要热更新时用 file: 前缀指向服务器文件，改完重启即可（仍比重新打包快）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按配置的位置取资源。文件不存在时记一条明确的 warn 并返回——
     *     让人一眼看出是路径写错还是忘了放文件；</li>
     *     <li>② 按 UTF-8 读成字符串。必须显式指定编码，用平台默认编码会读出乱码；</li>
     *     <li>③ 剥掉 BOM 前缀。Windows 编辑器保存的 UTF-8 文件常带这个不可见字符，
     *     不剥掉它会成为提示词的第一个字符，肉眼看不出来却会污染 system 消息；</li>
     *     <li>④ strip 掉首尾空白（文件末尾通常有换行），避免提示词尾部带多余空白；</li>
     *     <li>⑤ 只记位置与长度。提示词全文可能与运营口径相关，没必要进日志；
     *     长度足以用来确认「文件换了、内容确实变了」。</li>
     * </ol>
     *
     * <p>容易踩的坑：失败策略是「warn + 置空」，绝不抛异常——与「AI 不配也能启动」
     * 是同一条纪律。提示词缺失只是让回答少了身份约束，不该让整个应用起不来。
     * 另外这里用全限定名写 Resource，因为本类已导入 jakarta.annotation 下的同名类，
     * 两个 Resource 同时 import 会撞名。
     */
    private void loadSystemPrompt() {
        String location = aiProperties.getSystemPromptLocation();
        try {
            // 用全限定名：本类已导入 jakarta.annotation.Resource，两个 Resource 会撞名
            org.springframework.core.io.Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                // 配置文件里写了位置但文件不在：明确 warn，让人一眼看出是路径写错还是忘了放文件
                log.warn("系统提示词文件不存在，本次运行不带 system 消息 | location={}", location);
                return;
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            // 剥 BOM + 去首尾空白（详见 javadoc）
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            this.systemPrompt = content.strip();
            // 只记位置与长度：提示词全文可能与运营口径相关，但没必要进日志；
            // 长度足以用来确认「文件换了、内容确实变了」
            log.info("系统提示词加载完成 | location={} | length={}", location, this.systemPrompt.length());
        } catch (Exception e) {
            log.warn("系统提示词加载失败，本次运行不带 system 消息 | location={}", location, e);
            this.systemPrompt = "";
        }
    }

    /**
     * 带上下文调用 AI，返回回答正文（同步接口用）
     *
     * <p>为什么这样设计：system 提示词不作为参数传入——它启动时已从文件加载进字段，
     * 调用方不需要知道它从哪来。若让调用方传，等于要求每个调用点都去配置类取值，
     * 那正是「提示词放哪」这个实现细节的泄露。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 组装消息列表：system 提示词 + 历史消息 + 本次提问（顺序见组装方法的注释）；</li>
     *     <li>② 交给统一的调用入口，由它完成「未配置检查、熔断检查、失败收敛、回答非空校验」。
     *     两步分开是为了让「消息怎么拼」与「怎么发、怎么失败」互不干扰。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法是同步阻塞的，一次可能等几十秒。调用方绝不能把它包进
     * 数据库事务——那会让数据库连接被占着几十秒，并发稍高就耗尽连接池。
     *
     * @param history 上下文消息（时间正序，由调用方取最近 N 条），可为 null 或空列表
     * @param userMessage 本次提问（已拼好参考资料），不应为空；
     *                    为空时模型多半返回空内容，最终得到 C0200
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、熔断中、调用失败或回答为空时抛出（C0200）
     */
    public String chat(List<AiMessageVO> history, String userMessage) {
        return invokeChat(buildMessages(history, userMessage));
    }

    /**
     * 用调用方指定的系统提示词调用一次 AI（不带历史上下文）
     *
     * <p>为什么这样设计：与 chat 唯一的差别是 system 消息的来源。chat 用的是聊天人设，
     * 本方法由调用方给。内容审核这类场景必须用这个方法——它需要的是「审核员」人设，
     * 若带着「工作室助手」的聊天人设去判合规，模型会跑偏
     * （两种人设对「什么算合适内容」的回答完全不同）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 提示词非空才加 system 消息。为空时跳过而不是报错——
     *     少一句身份约束不影响这次调用能否完成；</li>
     *     <li>② 追加本次的用户消息；</li>
     *     <li>③ 交给统一的调用入口（失败处理与 chat 完全一致）。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法刻意不带历史。审核是「一次一判」的独立任务，
     * 没有上下文可继承；把上一次的判定结果带进这一次，会让模型倾向于给出
     * 与上次相同的结论（锚定效应），一条被判过 review 的内容之后会一直被 review。
     *
     * @param systemMessage 本次专用的系统提示词；为空或空白则本次不带 system 消息
     * @param userMessage 用户消息（即待处理的内容），不应为空
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、熔断中、调用失败或回答为空时抛出（C0200）
     */
    public String chatWithSystemPrompt(String systemMessage, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemMessage)) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.add(UserMessage.from(userMessage));
        return invokeChat(messages);
    }

    /**
     * 真正发起一次模型调用（上面两个方法共用）
     *
     * <p>为什么这样设计：抽出来是为了让两种调用走完全相同的失败处理。
     * 未配置、超时、限流、回答为空，在任何一种调用路径上的表现都必须一致，
     * 否则「聊天不可用时审核却可用」这类不一致会在排查时浪费大量时间。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 未配置时直接失败。注意这是配置问题而非服务故障，因此不记录熔断失败——
     *     否则本地没配 AI 也会被熔断，而那是启动时就已经知道的事；</li>
     *     <li>② 熔断中直接快速失败，不再发起外部请求；</li>
     *     <li>③ 组装请求并调用模型。这一步是同步阻塞的，调用方必须保证它在事务之外；</li>
     *     <li>④ 成功则先记一次熔断成功，再从响应里取回答正文。
     *     顺序不能反：先取正文再记成功的话，取正文抛异常就会漏记，熔断会一直停在打开状态；</li>
     *     <li>⑤ 任何异常都记一次熔断失败、记 error 日志，然后收敛成 C0200。</li>
     * </ol>
     *
     * <p>容易踩的坑：不把原始异常直接抛给上层。放出去响应会变成 B0001「系统错误」，
     * 而且异常信息里可能带着服务商返回的原始报文（含内部细节）。
     * 另外「记熔断失败」与「抛业务异常」是两件独立的事，都要做，不能只做一件。
     *
     * @param messages 已组装好的消息列表（顺序由调用方保证，最后一条必须是本次提问）
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、熔断中、调用失败或回答为空时抛出（C0200）
     */
    private String invokeChat(List<ChatMessage> messages) {
        // 未配置时直接失败：ApplicationContext 已经起来了，这里只影响这一次调用。
        // 注意这是配置问题而非服务故障，因此不记录熔断失败——
        // 否则本地没配 AI 也会被「熔断」，而那是启动时就已经知道的事
        if (chatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        // 熔断中：毫秒级失败，不再发起外部请求（持续性故障下没必要反复打服务商）
        rejectIfCircuitOpen();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .build();

        ChatResponse chatResponse;
        try {
            // 注意：这里是同步阻塞调用，所以调用方（Service）绝不能把它包进数据库事务
            chatResponse = chatModel.chat(chatRequest);
        } catch (Exception e) {
            // 超时、连接失败、4xx/5xx、限流都在这里被收敛。不把 e 直接抛出：
            // 响应会变成 B0001，且异常信息里可能带着服务商返回的原始报文
            circuitBreaker.recordFailure();
            log.error("调用 AI 服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        circuitBreaker.recordSuccess();
        return extractAnswer(chatResponse);
    }

    /**
     * 熔断中则立即失败（带剩余秒数的友好文案）
     *
     * <p>为什么这样设计：与统一的失败文案分开，是因为熔断是「我们知道它在恢复中」——
     * 告诉用户还要等多久，比一句无法行动的空话更有用。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 调 allowRequest 判断是否放行。注意它带副作用：熔断到期时会把状态切到
     *     半开并消耗掉那唯一一次试探机会，因此调用它的地方必须配对记录成功或失败；</li>
     *     <li>② 放行则直接返回，正常发起请求；</li>
     *     <li>③ 拒绝则取剩余秒数，拼一句带时间的提示后抛 C0200。
     *     剩余秒数为 0 时退回通用文案——「请约 0 秒后再试」比不说还糟。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只做「拒绝」，不做「记录失败」。被熔断挡掉的请求
     * 并没有真的打到服务商，把它算成一次失败会让熔断永远无法关闭。
     *
     * @throws BusinessException 熔断中时抛出（C0200，提示里带剩余秒数）
     */
    private void rejectIfCircuitOpen() {
        if (circuitBreaker.allowRequest()) {
            return;
        }
        long seconds = circuitBreaker.remainingSeconds();
        log.warn("AI 服务熔断中，本次调用快速失败 | 剩余约 {} 秒", seconds);
        throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                seconds > 0 ? "AI 服务恢复中，请约 " + seconds + " 秒后再试" : AI_UNAVAILABLE_MESSAGE);
    }

    /**
     * 带上下文流式调用 AI（SSE 接口用）
     *
     * <p>为什么这样设计：与同步调用相比，入参、消息组装、错误收敛完全一致，
     * 区别只在「回答是边生成边推送」还是「一次性返回」。两者共用同一个消息组装方法，
     * 因此上下文窗口的口径不会漂移。
     * 回调线程：增量回调由 LangChain4j 的流式线程调用，实现里做的是「拼接字符串 + 写 SSE」，
     * 都必须轻量；重活（落库）在流结束后由调用方处理。
     * 刻意不传播取消：客户端断开后，本方法仍会把模型的流读完——LangChain4j 的流式接口
     * 没有暴露「按需中断」的句柄。代价是断连后仍消耗一次模型调用；
     * 收益是代码简单，且不会出现「半截流已被计费但没落库」。
     * 中断语义由调用方表达：停止累积、不落库、不计数。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 未配置时直接失败（与同步路径同一判断，不记熔断）；</li>
     *     <li>② 熔断中快速失败（同一口径）；</li>
     *     <li>③ 组装请求并准备两个原子引用（分别装最终响应与错误）与一个闩锁；</li>
     *     <li>④ 发起流式调用并注册回调：增量回调转给调用方、完成回调记下响应并放行闩锁、
     *     错误回调记下异常并放行闩锁；</li>
     *     <li>⑤ 等待闩锁。超时时间给「模型超时 + 5 秒余量」——真超时了也必须有结论，
     *     否则线程会一直挂在闩锁上，那比多等 5 秒危险得多；</li>
     *     <li>⑥ 处理中断：恢复中断标记后抛业务异常，但不记熔断失败
     *     （中断通常是应用关闭，不该把熔断打开）；</li>
     *     <li>⑦ 回调里报过错也要走同一个出口，记一次熔断失败并抛 C0200；</li>
     *     <li>⑧ 一切正常：记一次熔断成功，返回从最终响应里取出的正文。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是模型最终响应里的完整文本，而不是自己拼接的增量。
     * 两者理论上一致，但以模型为准可以避免「某个增量丢了导致落库内容缺字」——
     * 那是一种极难发现的静默损坏。另外增量回调里必须过滤空白增量，
     * 否则前端会收到一串空的 delta 事件。
     *
     * @param history 上下文消息（时间正序），可为 null
     * @param userMessage 本次提问
     * @param onDelta 每收到一段增量时的回调，可为 null（表示只关心最终结果）；
     *                它在本方法调用线程所属的流式回调里执行，不要在里面做耗时操作
     * @return 完整回答（取自模型的最终响应）
     * @throws BusinessException 未配置、熔断中、调用失败、等待超时或回答为空时抛出（C0200）
     */
    public String chatStream(List<AiMessageVO> history, String userMessage, Consumer<String> onDelta) {
        if (streamingChatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        // 熔断中：与同步接口同一口径，毫秒级失败
        rejectIfCircuitOpen();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(buildMessages(history, userMessage))
                .build();

        AtomicReference<ChatResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        // 闩锁兜底：OpenAiStreamingChatModel 的 chat(...) 目前是「阻塞到流结束」，
        // 但 LangChain4j 的接口是回调式的，将来若改为异步实现，这里靠闩锁仍然正确
        CountDownLatch latch = new CountDownLatch(1);

        try {
            streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (onDelta != null && StrUtil.isNotBlank(partialResponse)) {
                        onDelta.accept(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    responseRef.set(completeResponse);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });
            // 等待回调收尾。超时给模型 timeout 再加 5s 余量：真超时了也必须有结论，
            // 否则线程会一直挂在闩锁上（它比「多等 5s」危险得多）
            if (!latch.await(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                circuitBreaker.recordFailure();
                log.error("等待 AI 流式响应超时 | baseUrl={} | model={}",
                        aiProperties.getBaseUrl(), aiProperties.getModel());
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉中断会让上层（例如应用关闭）失去感知
            Thread.currentThread().interrupt();
            // 中断不算服务故障：不记录熔断失败（应用关闭时不该把熔断打开）
            log.error("AI 流式调用被中断", e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("调用 AI 流式服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        if (errorRef.get() != null) {
            // 流中途报错（连接断了、服务商限流）也走同一个出口
            circuitBreaker.recordFailure();
            log.error("AI 流式响应出错 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), errorRef.get());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        circuitBreaker.recordSuccess();
        return extractAnswer(responseRef.get());
    }

    /**
     * 批量向量化：把一批文本一次性变成对应的向量列表（RAG 入库的第一步）
     *
     * <p>为什么这样设计：Embedding 接口一次可以收一批文本，逐条调用会把
     * 「一次 HTTP 往返」放大成 N 次——网络往返时间与触发服务商限流的概率都翻 N 倍。
     * 这与本项目其它「批量」纪律同源：能一次查完的绝不循环逐条查。
     * 另一个关键取舍是「与对话共用同一个熔断器」：向量化失败同样说明 AI 服务整体不可用
     * （token 被冻结、服务商故障时，对话与向量化会一起失败），
     * 只熔断一边没有意义——那样会出现「对话已经快速失败、向量化还在硬等超时」的割裂状态。
     * 还有一点是它<b>同步阻塞</b>：调用方不能把它包进数据库事务里，
     * 否则一次几秒的外部调用会把事务一直持有到超时。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 未配置模型时直接失败，与对话路径同一句提示。放在最前面是因为
     *     这一步不消耗任何外部资源，能让本地开发环境（不配 key）在毫秒级得到明确原因，
     *     而不是等到网络超时；</li>
     *     <li>② 校验入参非空：空列表去调接口只会得到一次必然失败的请求，
     *     还会白白占一次限流配额；</li>
     *     <li>③ 熔断检查：处于打开状态时毫秒级失败，不发起真实调用。
     *     它必须排在构造请求之前，否则「快速失败」就变成了「构造完再失败」；</li>
     *     <li>④ 把字符串包成 TextSegment：LangChain4j 的批量接口只收这个类型，
     *     在这一层包掉，上层就只跟字符串打交道，不必认识 LangChain4j 的类型；</li>
     *     <li>⑤ 调接口并校验返回数量：数量必须与入参一致，否则调用方无法用
     *     「第 i 个向量对应第 i 个文本」这条约定去写库。这个校验必须在
     *     「记熔断成功」之前——顺序反了的话，一次「返回数量不符」的故障
     *     会被当成服务健康，熔断永远不会打开；</li>
     *     <li>⑥ 成功记熔断成功并返回；异常则记熔断失败、收敛成 C0200 抛出。
     *     catch 里先单独放行 BusinessException：它是第 ⑤ 步数量校验抛出来的，
     *     本身已经是一句面向用户的 C0200 提示，不必再被下面那个 catch 覆盖一遍。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的向量顺序只在「服务商遵守协议」时才与入参一一对应，
     * 因此调用方必须按<b>下标</b>使用，不能按向量内容去猜它属于哪一段文本。
     * 另一个坑是数量不符这条分支<b>不会</b>记进熔断失败计数——熔断器统计的是
     * 调用层面的失败（超时、限流、协议错误），而「调用成功但条数不对」被单独放行了。
     * 若希望这类故障也参与熔断，必须显式在 BusinessException 分支里补一次 recordFailure，
     * 否则服务商稳定地少返回向量时，熔断永远不会打开。
     * 还有一点：这里的异常提示被刻意收敛成一句「AI 服务暂时不可用」，
     * 不把服务商的原始报文带出去——那里面可能含 baseUrl、模型名甚至 key 的片段，
     * 而错误响应会直接返回给前端。排查细节只进日志。
     * 最后，本方法不做分批：一次传入过多文本会撞服务商的单次上限，
     * 分批的责任在调用方（切分块的批量入库），不要指望这里自动拆。
     *
     * @param texts 待向量化的文本列表，不能为 null 或空（否则抛 A0401）；
     *              列表里不应含 null 元素——本方法不逐个判空，
     *              传入 null 元素会在服务端报错后统一收敛成 C0200，提示里看不出是哪一条
     * @return 与入参<b>顺序一一对应</b>的向量列表，长度恒等于 texts.size()；
     *         永不为 null，也不会返回长度不符的列表（不符会抛异常而不是截断）
     * @throws BusinessException 入参为 null 或空列表时抛 A0401（这是调用方传错了，
     *         重试同样的参数不会成功）；模型未配置、熔断打开中、调用失败、
     *         返回 null 或数量与入参不符时抛 C0200。
     *         C0200 表示第三方服务的问题而不是本系统的 bug，等对方恢复后重试可能成功
     */
    public List<Embedding> embedAll(List<String> texts) {
        if (embeddingModel == null) {
            log.warn("调用 Embedding 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        ThrowUtils.throwIf(texts == null || texts.isEmpty(), ErrorCode.PARAMS_ERROR, "待向量化的文本为空");

        // 与对话共用同一个熔断器：向量化失败同样说明 AI 服务整体不可用
        // （token 被冻结时对话与向量化会一起失败，只熔断一边没有意义）
        rejectIfCircuitOpen();

        // LangChain4j 的 embedAll 收 TextSegment：这里先包一层，
        // 让上层只跟「字符串」打交道（切分块的文本本身就是字符串）
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        try {
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            List<Embedding> embeddings = response == null ? null : response.content();
            ThrowUtils.throwIf(embeddings == null || embeddings.size() != texts.size(),
                    ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
            circuitBreaker.recordSuccess();
            return embeddings;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 超时、限流、协议错误都收敛成同一句提示，不把服务商的原始报文带出去
            circuitBreaker.recordFailure();
            log.error("调用 Embedding 服务失败 | baseUrl={} | model={} | 文本数={}",
                    aiProperties.getBaseUrl(), aiProperties.getEmbeddingModel(), texts.size(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 组装消息列表：system 提示词 + 历史消息 + 本次提问
     *
     * <p>为什么这样设计：顺序遵循协议要求（时间正序），模型把最后一条当作「当前问题」；
     * system 消息始终在最前，用来约束身份与语气。这个顺序不是风格问题——
     * 把本次提问放在中间，模型会把它当成历史的一部分，回答的往往是「上一轮的延续」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 系统提示词：为空（文件缺失或未配置）就不发这条消息，聊天照常可用；</li>
     *     <li>② 历史消息：逐条映射成 LangChain4j 的消息类型，映射不出来的直接跳过；</li>
     *     <li>③ 本次提问放最后。</li>
     * </ol>
     *
     * <p>容易踩的坑：历史消息里的角色映射失败必须「跳过」而不是「报错」。
     * 历史只是给模型的参考素材，少一条不影响本次回答；为一条脏数据让整个提问失败才是错的。
     *
     * @param history 历史消息（可空）；内部会过滤掉内容为空或角色无法识别的条目
     * @param userMessage 本次提问（已拼好参考资料）
     * @return LangChain4j 的消息列表，最后一条一定是本次提问
     */
    private List<ChatMessage> buildMessages(List<AiMessageVO> history, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        // ① 系统提示词：为空（文件缺失/未配置）就不发这条消息，聊天照常可用
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        // ② 历史消息：库里的 role 是 user / assistant，映射成 LangChain4j 的消息类型
        if (history != null) {
            for (AiMessageVO item : history) {
                ChatMessage chatMessage = toChatMessage(item);
                if (chatMessage != null) {
                    messages.add(chatMessage);
                }
            }
        }
        // ③ 本次提问放最后
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    /**
     * 把一条历史消息映射成 LangChain4j 的消息类型
     *
     * <p>为什么这样设计：两种「不映射、直接跳过」的情况都是刻意容错，而不是报错——
     * 内容是空的、或角色是枚举外的脏值。历史消息只是给模型的参考素材，
     * 少一条不影响本次回答；为此让整个提问失败才是错的。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 条目本身为 null 或内容为空：跳过；</li>
     *     <li>② 角色无法识别：记一条 warn（把合法取值也打出来，便于排查），然后跳过；</li>
     *     <li>③ 按角色映射：user 映射成用户消息、assistant 映射成 AI 消息。
     *     用 switch 表达式让编译器强制覆盖全部枚举值——将来新增角色时会直接编译报错，
     *     而不是静默走进某个默认分支。</li>
     * </ol>
     *
     * <p>容易踩的坑：role 字段在数据库里是字符串，映射必须走枚举的查找方法而不是
     * 字符串比较。直接比字符串会把大小写、前后空格的差异当成「未知角色」，
     * 表现成「历史消息莫名其妙少了几条」，且日志里只有 warn。
     *
     * @param item 历史消息（来自数据库），可为 null
     * @return 对应的消息；无法映射时返回 null，调用方必须判空后跳过
     */
    private ChatMessage toChatMessage(AiMessageVO item) {
        if (item == null || StrUtil.isBlank(item.getContent())) {
            return null;
        }
        AiMessageRoleEnum role = AiMessageRoleEnum.of(item.getRole());
        if (role == null) {
            log.warn("跳过无法识别的历史消息角色 | role={}（合法取值：{}）",
                    item.getRole(), AiMessageRoleEnum.valuesText());
            return null;
        }
        return switch (role) {
            case USER -> UserMessage.from(item.getContent());
            case ASSISTANT -> AiMessage.from(item.getContent());
        };
    }

    /**
     * 从响应里取回答正文
     *
     * <p>为什么这样设计：响应结构不符（例如被网关拦截、服务商改了协议）与模型返回空内容，
     * 对用户而言都是「这次没答上来」，不做区分，统一收敛成 C0200。
     * 单独抽一个方法，是为了让两条调用路径（同步与流式）用同一套非空判定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 三层判空：响应本身、响应里的 AI 消息、消息文本。任何一层为空都算失败；</li>
     *     <li>② 记一条 warn 并把响应对象写进日志（它不含密钥，只有模型输出与用量信息）；</li>
     *     <li>③ 抛 C0200；否则返回正文。</li>
     * </ol>
     *
     * <p>容易踩的坑：必须用「是否空白」而不是「是否为 null」来判断正文。
     * 推理模型额度被思考过程吃光时，返回的正文往往是一串空白字符而不是 null——
     * 只判 null 会让空回答一路落库，用户在历史里看到一条空白气泡。
     *
     * @param chatResponse LangChain4j 响应，可为 null
     * @return 回答正文（已保证非空白）
     * @throws BusinessException 响应为空或正文为空时抛出（C0200）
     */
    private String extractAnswer(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.aiMessage() == null
                || StrUtil.isBlank(chatResponse.aiMessage().text())) {
            // 结构不符（例如被网关拦截、服务商改了协议）或模型返回空内容，
            // 对用户而言都是「这次没答上来」，不做区分
            log.warn("AI 响应中没有可用内容 | response={}", chatResponse);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        return chatResponse.aiMessage().text();
    }
}
