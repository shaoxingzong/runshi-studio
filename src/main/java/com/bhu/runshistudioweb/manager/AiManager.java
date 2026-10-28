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
 * AI 服务调用管理器 —— 基于 LangChain4j
 *
 * 只负责「把一段对话交给模型，取回一段回答」：不认识数据库与业务规则，配额与会话归属在 Service。
 *
 * 三个必须踩准的点：base-url 来自配置（硬编码会让测试无法指向本地桩服务）；超时与重试显式设置；
 * 不开启请求 / 响应日志——前者会打出 Authorization 头，后者会打出用户对话内容。
 *
 * 所有异常收敛成 C0200，对外统一一句「AI 服务暂时不可用」；日志只记 base-url 与模型名，绝不记 api-key。
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
     * LangChain4j 的客户端只暴露一个 timeout，取 60 秒是因为模型生成本身慢，
     * 而建连失败同样会在这段时间内返回。配最大重试 1 次，最坏两次尝试。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 最大重试次数：1（即最坏两次尝试）
     *
     * 重试会把服务商的故障放大成我们自己的流量与等待，稳定性应靠「快速失败 + 前端重试」。
     */
    private static final int MAX_RETRIES = 1;

    /** 对外统一的失败文案：不复述底层细节（超时、限流、解析失败在用户眼里是同一件事） */
    private static final String AI_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    /**
     * 熔断器：注入而非内部 new——它是有状态的单例，必须与各调用点共享同一份失败计数
     */
    private final AiCircuitBreaker circuitBreaker;

    private final AiProperties aiProperties;

    /**
     * 资源加载器：读系统提示词文件。支持 classpath: 与 file: 两种前缀，
     * 因此「随包发布」与「指向服务器文件」只改配置，不改代码
     */
    private final ResourceLoader resourceLoader;

    /**
     * 系统提示词：启动时读一次，之后不变。默认空字符串而非 null，
     * 空值即「本次请求不带 system 消息」
     */
    private String systemPrompt = "";

    /**
     * 对话模型：只依赖 ChatModel 接口，换服务商或换流式时改动被限制在初始化方法里
     */
    private ChatModel chatModel;

    /**
     * 流式模型（SSE 接口用）：LangChain4j 的同步与流式是两条实现路径，
     * 因此本类同时持有两个实例，配置项完全一致
     */
    private StreamingChatModel streamingChatModel;

    /**
     * 向量模型（Embedding）：把文本变成向量，RAG 入库与检索都要用
     */
    private EmbeddingModel embeddingModel;

    /**
     * 启动初始化：先加载系统提示词，再构建三个模型客户端
     *
     * 提示词加载放在最前且无条件执行：它不依赖 AI 是否配置好，放进「配置齐全」分支里，
     * 本地不配 api-key 时提示词就不会被加载，问题会藏得很深。
     *
     * 坑：未配置时「不构建、也不抛异常」是刻意的，否则本地不配 AI 的人连应用都起不来。
     * 另外 maxTokens 必须显式设置：推理模型的思考过程同样消耗额度，不设上限时
     * 思考会把额度吃光、正文为空，用户看到的却是「AI 服务暂时不可用」。
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
     * 解析输出 token 上限；未配置或非法时返回 null（LangChain4j 对 null 的语义是不设置）
     *
     * 非法值回落成 null 而不报错：这是锦上添花的调参项，写错的数字不该让应用起不来。
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
     * 读一次而不是每次提问都读：提示词不该在同一次运行内漂移，也避免匿名接口的高频 IO。
     * 代价是改完文件要重启，这与「话术是随包资源」的定位一致。
     *
     * 坑：必须显式用 UTF-8（平台默认编码会读出乱码），并剥掉 BOM——
     * Windows 编辑器保存的 UTF-8 常带这个不可见字符，它会成为提示词的第一个字符。
     * 失败策略是 warn + 置空，绝不抛异常。
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
     * system 提示词不作为参数传入：它启动时已加载进字段，调用方不需要知道它从哪来。
     *
     * 坑：本方法同步阻塞，一次可能等几十秒。调用方绝不能把它包进数据库事务——
     * 那会让连接被占着几十秒，并发稍高就耗尽连接池。
     *
     * @param history 上下文消息（时间正序），可为 null 或空列表
     * @param userMessage 本次提问（已拼好参考资料），不应为空
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、熔断中、调用失败或回答为空时抛出（C0200）
     */
    public String chat(List<AiMessageVO> history, String userMessage) {
        return invokeChat(buildMessages(history, userMessage));
    }

    /**
     * 用调用方指定的系统提示词调用一次 AI（不带历史上下文）
     *
     * 内容审核这类场景必须用它：需要的是「审核员」人设，带着聊天人设去判合规，模型会跑偏。
     *
     * 坑：刻意不带历史。审核是「一次一判」，把上次的判定带进来会让模型锚定在相同结论上，
     * 一条被判过 review 的内容之后会一直被 review。
     *
     * @param systemMessage 本次专用的系统提示词；为空或空白则本次不带 system 消息
     * @param userMessage 用户消息（待处理的内容），不应为空
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
     * 抽出来是为了让两种调用走完全相同的失败处理，否则「聊天不可用时审核却可用」
     * 这类不一致会在排查时浪费大量时间。
     *
     * 坑：必须先记熔断成功再从响应里取正文。顺序反了的话，取正文抛异常就会漏记，
     * 熔断一直停在打开状态。「记熔断失败」与「抛业务异常」也是两件独立的事，都要做。
     *
     * @param messages 已组装好的消息列表（最后一条必须是本次提问）
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
     * 熔断中则立即失败，提示里带上剩余秒数
     *
     * 剩余为 0 时退回通用文案——「请约 0 秒后再试」比不说还糟。
     *
     * 坑：本方法只做「拒绝」，不做「记录失败」。被挡掉的请求并没有打到服务商，
     * 算成一次失败会让熔断永远无法关闭。
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
     * 消息组装与错误收敛和同步路径完全一致，区别只在回答是边生成边推送。增量回调跑在流式线程上，
     * 只能做「拼接 + 写 SSE」，重活（落库）由调用方在流结束后处理。
     *
     * 坑一：等待闩锁的超时给「模型超时 + 5 秒余量」，真超时了也必须有结论，否则线程会一直挂着。
     * 坑二：返回模型最终响应里的完整文本，而不是自己拼接的增量——以模型为准可避免增量丢失导致落库缺字。
     *
     * @param history 上下文消息（时间正序），可为 null
     * @param userMessage 本次提问
     * @param onDelta 增量回调，可为 null；不要在里面做耗时操作
     * @return 完整回答（取自模型的最终响应）
     * @throws BusinessException 未配置、熔断中、调用失败、等待超时或回答为空时抛出（C0200）
     */
    public String chatStream(List<AiMessageVO> history, String userMessage, Consumer<String> onDelta) {
        return streamMessages(buildMessages(history, userMessage), onDelta);
    }

    /**
     * 用调用方指定的系统提示词做一次流式调用（不带历史上下文）
     *
     * <p>与 {@link #chatWithSystemPrompt(String, String)} 对应的流式版本：创作助手、内容审核
     * 这类「独立人设」场景必须用它，否则会把问答的咨询腔调带进写作。
     *
     * <p>坑：同样刻意不带历史。写作助手每次只处理「本次选中的片段 / 本次正文」，
     * 把上一轮生成的段落带进上下文，会让模型不断复制上一轮的措辞。
     *
     * @param systemMessage 本次专用的系统提示词；为空或空白则本次不带 system 消息
     * @param userMessage 用户消息（待处理的内容），不应为空
     * @param onDelta 增量回调，可为 null；不要在里面做耗时操作
     * @return 完整回答（取自模型的最终响应）
     * @throws BusinessException 未配置、熔断中、调用失败、等待超时或回答为空时抛出（C0200）
     */
    public String chatStreamWithSystemPrompt(String systemMessage, String userMessage,
                                             Consumer<String> onDelta) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemMessage)) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.add(UserMessage.from(userMessage));
        return streamMessages(messages, onDelta);
    }

    /**
     * 真正发起一次流式调用（上面两个流式方法共用）
     *
     * <p>抽出来是为了让「带历史」与「带自定义人设」两条路径走完全相同的失败处理与熔断计数，
     * 否则「问答流能熔断、写作流不熔断」这类不一致会在排查时浪费大量时间。
     *
     * <p>坑：熔断与超时语义与 {@link #chatStream} 完全一致，不要在这里另开一套。
     *
     * @param messages 已组装好的消息列表（最后一条必须是本次请求）
     * @param onDelta 增量回调，可为 null
     * @return 完整回答（取自模型的最终响应）
     * @throws BusinessException 未配置、熔断中、调用失败、等待超时或回答为空时抛出（C0200）
     */
    private String streamMessages(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (streamingChatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        // 熔断中：与同步接口同一口径，毫秒级失败
        rejectIfCircuitOpen();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
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
     * 一次 HTTP 而不是逐条：逐条会把一次往返放大成 N 次，网络时间与触发限流的概率都翻 N 倍。
     * 与对话共用同一个熔断器：向量化失败同样说明 AI 服务整体不可用，只熔断一边没有意义。
     *
     * 坑一：返回数量必须与入参一致，这条校验必须在记熔断成功之前，否则「数量不符」会被当成服务健康。
     * 坑二：调用方必须按下标使用向量，不能按内容去猜它属于哪段文本。
     * 坑三：本方法同步阻塞，不能包进事务；也不做分批，单次上限由调用方负责。
     *
     * @param texts 待向量化的文本列表，不能为 null 或空（否则抛 A0401），列表内不应含 null 元素
     * @return 与入参顺序一一对应的向量列表，长度恒等于 texts.size()
     * @throws BusinessException 入参为空时抛 A0401；未配置、熔断中、调用失败或数量不符时抛 C0200
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
     * 顺序是协议要求而非风格问题：模型把最后一条当作当前问题，
     * 把本次提问放在中间，模型会把它当成历史的一部分，回答往往是「上一轮的延续」。
     *
     * 坑：历史消息映射失败必须跳过而不是报错——历史只是参考素材，为一条脏数据让整个提问失败才是错的。
     *
     * @param history 历史消息，可空；内容为空或角色无法识别的条目会被过滤
     * @param userMessage 本次提问（已拼好参考资料）
     * @return 消息列表，最后一条一定是本次提问
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
     * 两种「不映射、直接跳过」的情况都是刻意容错：内容为空、角色是枚举外的脏值。
     * 用 switch 表达式让编译器强制覆盖全部枚举值，将来新增角色会直接编译报错。
     *
     * 坑：角色映射必须走枚举的查找方法，直接比字符串会把大小写与空格的差异当成未知角色，
     * 表现为「历史消息莫名其妙少了几条」。
     *
     * @param item 历史消息，可为 null
     * @return 对应的消息；无法映射时返回 null，调用方需判空后跳过
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
     * 响应结构不符与模型返回空内容，对用户而言都是「这次没答上来」，统一收敛成 C0200。
     *
     * 坑：必须用「是否空白」而不是「是否为 null」判断正文。推理模型额度被思考吃光时，
     * 正文往往是一串空白字符而不是 null，只判 null 会让空回答一路落库。
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
