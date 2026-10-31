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
 * 只负责调用模型，不碰业务规则；超时与重试显式设置，异常统一收敛成 C0200。
 * 不开启请求 / 响应日志，日志只记 base-url 与模型名，绝不记 api-key。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiManager {

    /** 单次请求超时（建连与读取合并计算） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** 最大重试次数：1（即最坏两次尝试） */
    private static final int MAX_RETRIES = 1;

    /** 对外统一的失败文案：不复述底层细节（超时、限流、解析失败在用户眼里是同一件事） */
    private static final String AI_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    /** 熔断器：注入而非内部 new——它是有状态的单例，必须与各调用点共享同一份失败计数 */
    private final AiCircuitBreaker circuitBreaker;

    private final AiProperties aiProperties;

    /** 资源加载器：读系统提示词文件。支持 classpath: 与 file: 两种前缀，因此「随包发布」与「指向服务器文件」只改配置，不改代码 */
    private final ResourceLoader resourceLoader;

    /** 系统提示词：启动时读一次，之后不变。默认空字符串而非 null，空值即「本次请求不带 system 消息」 */
    private String systemPrompt = "";

    /** 对话模型：只依赖 ChatModel 接口，换服务商或换流式时改动被限制在初始化方法里 */
    private ChatModel chatModel;

    /** 流式模型（SSE 接口用）：LangChain4j 的同步与流式是两条实现路径，因此本类同时持有两个实例，配置项完全一致 */
    private StreamingChatModel streamingChatModel;

    /** 向量模型（Embedding）：把文本变成向量，RAG 入库与检索都要用 */
    private EmbeddingModel embeddingModel;

    /** 启动初始化：先加载系统提示词，再构建三个模型客户端 */
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
                .maxTokens(resolveMaxTokens())
                .timeout(REQUEST_TIMEOUT)
                .maxRetries(MAX_RETRIES)
                .build();

        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getModel())
                .maxTokens(resolveMaxTokens())
                .timeout(REQUEST_TIMEOUT)
                .build();

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

    /** 解析输出 token 上限；未配置或非法时返回 null（LangChain4j 对 null 的语义是不设置） */
    private Integer resolveMaxTokens() {
        Integer configured = aiProperties.getMaxTokens();
        if (configured == null || configured <= 0) {
            return null;
        }
        return configured;
    }

    /** 启动时加载系统提示词（只读一次） */
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
            log.info("系统提示词加载完成 | location={} | length={}", location, this.systemPrompt.length());
        } catch (Exception e) {
            log.warn("系统提示词加载失败，本次运行不带 system 消息 | location={}", location, e);
            this.systemPrompt = "";
        }
    }

    /**
     * 带上下文调用 AI，返回回答正文（同步接口用）
     *
     * @param history 上下文消息（时间正序），可为 null 或空列表
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

    /** 真正发起一次模型调用（上面两个方法共用） */
    private String invokeChat(List<ChatMessage> messages) {
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
            // 同步阻塞调用，调用方不能把它包进数据库事务
            chatResponse = chatModel.chat(chatRequest);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("调用 AI 服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        circuitBreaker.recordSuccess();
        return extractAnswer(chatResponse);
    }

    /** 熔断中则立即失败，提示里带上剩余秒数 */
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
     * @param history 上下文消息（时间正序），可为 null
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
     * 熔断与超时语义与 {@link #chatStream} 保持一致，不要另开一套。
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

        rejectIfCircuitOpen();

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

    private List<ChatMessage> buildMessages(List<AiMessageVO> history, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        // 系统提示词：为空（文件缺失/未配置）就不发这条消息，聊天照常可用
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        // 历史消息：库里的 role 是 user / assistant，映射成 LangChain4j 的消息类型
        if (history != null) {
            for (AiMessageVO item : history) {
                ChatMessage chatMessage = toChatMessage(item);
                if (chatMessage != null) {
                    messages.add(chatMessage);
                }
            }
        }
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    /** 把一条历史消息映射成 LangChain4j 的消息类型 */
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

    /** 从响应里取回答正文 */
    private String extractAnswer(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.aiMessage() == null
                || StrUtil.isBlank(chatResponse.aiMessage().text())) {
            log.warn("AI 响应中没有可用内容 | response={}", chatResponse);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        return chatResponse.aiMessage().text();
    }
}
