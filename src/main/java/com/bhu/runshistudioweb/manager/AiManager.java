package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AI 服务调用管理器（Manager 层）—— 基于 <b>LangChain4j</b>
 *
 * author: shaoshing
 *
 * <p>职责：把「一段对话」交给模型，取回一段回答。它<b>不认识数据库、不认识业务规则</b>
 * （配额、会话归属都在 Service），因此既可以被提问接口调用，也能被将来的
 * 「文档问答」「批量生成」复用，还能对着本地 Stub 单测。
 *
 * <p><b>为什么用 LangChain4j 而不是自写 HTTP 客户端</b>：本模块后续要扩展的地方，
 * 恰好都是它能「只改一个点」的位置：
 * <ul>
 *     <li><b>流式输出</b>：把 {@code OpenAiChatModel} 换成 {@code OpenAiStreamingChatModel}，
 *     Service 只多一个订阅回调，业务代码不用重写（SSE 接口是既定演进方向）；</li>
 *     <li><b>多轮记忆</b>：{@code MessageWindowChatMemory} 直接替代我们手工拼 messages；</li>
 *     <li><b>声明式接口</b>：{@code @AiService} + {@code AiServices.builder(...)}，
 *     接口即 AI 服务（带 memory、tools、retriever）；</li>
 *     <li><b>RAG</b>：{@code DocumentSplitter / EmbeddingStore / ContentRetriever} 一整套；
 *     {@code studio_project.content / studio_ai_message} 正是为它准备的数据源；</li>
 *     <li><b>换服务商</b>：上层只依赖 {@link ChatModel} 接口，换模型只动本类的构建方法。</li>
 * </ul>
 *
 * <p><b>为什么不用 langchain4j 的 Spring Boot starter</b>：它面向 Boot 3，
 * 而本项目是 Boot 4（Jackson 3 在 {@code tools.jackson} 下）。本项目
 * <b>自己装配 Bean</b>（{@link AiProperties} + 本类），只用 {@code studio.ai.*} 一份配置，
 * 不依赖 starter 的键名约定，也不受其自动配置与 Boot 4 的兼容性影响。
 *
 * <p><b>三个必须踩准的点</b>：
 * <ol>
 *     <li><b>{@code base-url} 必须来自配置</b>：硬编码服务商地址会让测试无法指向本地 Stub，
 *     也会让「换服务商」变成改代码；</li>
 *     <li><b>超时必须显式设置</b>：LangChain4j 的 {@code timeout} 是「单次请求」的总超时，
 *     不设就沿用其默认值（对匿名接口而言太长）；这里压到 60s，
 *     并用 {@code maxRetries(1)} 限制「超时 + 重试」的累计等待；</li>
 *     <li><b>不开启请求/响应日志</b>：{@code logRequests(true)} 会把 HTTP 请求打出来——
 *     其中含 {@code Authorization} 头（API Key），{@code logResponses(true)} 会打出
 *     用户对话内容。两者一个是泄密、一个是隐私，故保持关闭。</li>
 * </ol>
 *
 * <p><b>所有异常都在这里被吃掉并转成 {@code OPERATION_ERROR(50001)}</b>：
 * LangChain4j 抛的是 {@code LangChain4jException} 这类技术异常
 * （{@code InternalServerException / TimeoutException / ...}），直接放出去会被全局兜底成
 * 50000，还可能把服务商返回的原始报文带进日志与响应。对外统一一句
 * 「AI 服务暂时不可用，请稍后重试」。
 *
 * <p><b>日志纪律</b>：只记录 base-url / model / 异常，<b>绝不记录 api-key</b>。
 */
@Slf4j
@Component
public class AiManager {

    /**
     * 单次请求超时（建连 + 读取合并计算）
     *
     * <p>LangChain4j 的 {@code OpenAiChatModel} 只暴露一个 {@code timeout}，
     * 不像裸 {@code RestClient} 那样能分别设建连 5s / 读取 60s。
     * 取 60s 是因为模型生成本身慢，而「建连失败」在 60s 内同样会失败返回，
     * 只是失败得慢一些——配上 {@code maxRetries(1)}，最坏情况是 2 次尝试。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 最大重试次数：1（即最坏 2 次尝试）
     *
     * <p>LangChain4j 默认会对 5xx / 429 做重试。对匿名接口而言，
     * 重试会把服务商的故障放大成我们自己的流量与等待，所以压到最小；
     * 真正的稳定性应该靠「快速失败 + 前端重试」而不是在服务端死磕。
     */
    private static final int MAX_RETRIES = 1;

    /** 对外统一的失败文案：不复述底层细节（超时、限流、解析失败在用户眼里是同一件事） */
    private static final String AI_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    @Resource
    private AiProperties aiProperties;

    /**
     * 只依赖 {@link ChatModel} 接口，不持有具体实现：
     * 将来换流式模型或换服务商时，改动被限制在 {@link #initChatModel()} 里
     */
    private ChatModel chatModel;

    /**
     * 流式模型（SSE 接口用），同样只依赖 LangChain4j 的 {@link StreamingChatModel} 接口
     *
     * <p>{@code OpenAiStreamingChatModel} 与 {@code OpenAiChatModel} 是两个独立的 Builder——
     * 这不是我们的选择，而是 LangChain4j 的设计（同步与流式是两条实现路径）。
     * 因此本类同时持有两个模型实例，配置项（base-url / api-key / model / timeout）完全一致。
     */
    private StreamingChatModel streamingChatModel;

    /**
     * 初始化模型客户端（{@code @PostConstruct} 保证配置已绑定完成）
     *
     * <p>未配置 base-url / api-key 时<b>不构建、也不抛异常</b>：
     * 本地不配 AI 也能正常启动应用，真正调用时返回 50001（见 {@link #chat}）。
     */
    @PostConstruct
    void initChatModel() {
        if (StrUtil.isBlank(aiProperties.getBaseUrl()) || StrUtil.isBlank(aiProperties.getApiKey())) {
            // 只提示「缺什么」，绝不打印密钥内容
            log.warn("AI 未配置完整（base-url={} / apiKeyConfigured={}），提问接口将返回 50001；"
                            + "其它接口不受影响", StrUtil.isBlank(aiProperties.getBaseUrl()) ? "空" : "已配置",
                    StrUtil.isNotBlank(aiProperties.getApiKey()));
            return;
        }

        this.chatModel = OpenAiChatModel.builder()
                // base-url 不含 /chat/completions，LangChain4j 会自行拼接（OpenAI 兼容协议）
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getModel())
                .timeout(REQUEST_TIMEOUT)
                .maxRetries(MAX_RETRIES)
                // 刻意不开 logRequests / logResponses：前者会打出 Authorization 头（密钥），
                // 后者会打出用户对话内容。排查协议问题时应临时开启并在本地使用，不要带进提交
                .build();

        // 流式模型：与同步模型用同一套配置。
        // 注意 OpenAiStreamingChatModel 的 builder **没有 maxRetries**（流式下重试语义不同：
        // 已经开始推流后重试会重复输出），所以流式路径最坏情况就是一次 60s 的等待
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getModel())
                .timeout(REQUEST_TIMEOUT)
                .build();

        log.info("AI 客户端初始化完成 | baseUrl={} | model={} | timeout={}s | maxRetries={}",
                aiProperties.getBaseUrl(), aiProperties.getModel(),
                REQUEST_TIMEOUT.toSeconds(), MAX_RETRIES);
    }

    /**
     * 带上下文调用 AI，返回回答正文
     *
     * @param systemPrompt 系统提示词（约束身份与语气，来自配置）
     * @param history      上下文消息（<b>时间正序</b>，由 Service 取最近 N 条），可为空
     * @param userMessage  本次提问
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、调用失败或回答为空时抛出（50001）
     */
    public String chat(String systemPrompt, List<AiMessageVO> history, String userMessage) {
        // 未配置时直接失败：ApplicationContext 已经起来了，这里只影响这一次调用
        if (chatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(buildMessages(systemPrompt, history, userMessage))
                .build();

        ChatResponse chatResponse;
        try {
            // 注意：这里是同步阻塞调用，所以调用方（Service）绝不能把它包进数据库事务
            chatResponse = chatModel.chat(chatRequest);
        } catch (Exception e) {
            // 超时、连接失败、4xx/5xx、限流都在这里被收敛。不把 e 直接抛出：
            // 响应会变成 50000，且异常信息里可能带着服务商返回的原始报文
            log.error("调用 AI 服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        return extractAnswer(chatResponse);
    }

    /**
     * 带上下文<b>流式</b>调用 AI（SSE 接口用）
     *
     * <p>与 {@link #chat} 的关系：入参、消息组装、错误收敛完全一致，
     * 区别只在「回答是边生成边推送」还是「一次性返回」。
     * 两者共用 {@link #buildMessages}，因此上下文窗口的口径不会漂移。
     *
     * <p><b>回调线程</b>：{@code onDelta} 由 LangChain4j 的流式线程调用，
     * 实现里做的是「拼接字符串 + 写 SSE」，都必须轻量；重活（落库）在流结束后由调用方处理。
     *
     * <p><b>为什么不传播取消</b>：客户端断开后，本方法仍会把模型的流读完
     * （LangChain4j 的流式接口没有暴露「按需中断」的句柄）。
     * 代价是断连后仍消耗一次模型调用；收益是代码简单、且不会出现「半截流已被计费但没落库」。
     * 调用方通过「不再累积、不再落库、不计数」来表达中断语义（见 AiChatServiceImpl）。
     *
     * @param systemPrompt 系统提示词
     * @param history      上下文消息（时间正序），可为空
     * @param userMessage  本次提问
     * @param onDelta      每收到一段增量时的回调（可为 null，表示只关心最终结果）
     * @return 完整回答（取自模型的最终响应，而不是自己拼接的增量——两者理论上一致，
     *         但以模型为准可以避免「增量丢失导致落库内容缺字」）
     * @throws BusinessException 未配置、调用失败或回答为空时抛出（50001）
     */
    public String chatStream(String systemPrompt, List<AiMessageVO> history, String userMessage,
                             Consumer<String> onDelta) {
        if (streamingChatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(buildMessages(systemPrompt, history, userMessage))
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
                log.error("等待 AI 流式响应超时 | baseUrl={} | model={}",
                        aiProperties.getBaseUrl(), aiProperties.getModel());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉中断会让上层（例如应用关闭）失去感知
            Thread.currentThread().interrupt();
            log.error("AI 流式调用被中断", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        } catch (Exception e) {
            log.error("调用 AI 流式服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        if (errorRef.get() != null) {
            // 流中途报错（连接断了、服务商限流）也走同一个出口
            log.error("AI 流式响应出错 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), errorRef.get());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        return extractAnswer(responseRef.get());
    }

    /**
     * 组装消息列表：system 提示词 + 历史消息 + 本次提问
     *
     * <p>顺序遵循协议要求（时间正序），模型把<b>最后一条</b>当作「当前问题」；
     * system 消息始终在最前，用来约束身份与语气。
     *
     * @param systemPrompt 系统提示词
     * @param history      历史消息（可空）
     * @param userMessage  本次提问
     * @return LangChain4j 的消息列表
     */
    private List<ChatMessage> buildMessages(String systemPrompt, List<AiMessageVO> history,
                                            String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        // ① 系统提示词
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
     * <p>两种「不映射、直接跳过」的情况都是刻意容错，而不是报错：
     * 内容是空的、或 role 是枚举外的脏值。历史消息只是给模型的参考素材，
     * 少一条不影响本次回答；为此让整个提问失败才是错的。
     *
     * @param item 历史消息（来自数据库）
     * @return 对应的消息；无法映射时返回 null
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
     * @param chatResponse LangChain4j 响应
     * @return 回答正文
     * @throws BusinessException 响应为空或正文为空时抛出（50001）
     */
    private String extractAnswer(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.aiMessage() == null
                || StrUtil.isBlank(chatResponse.aiMessage().text())) {
            // 结构不符（例如被网关拦截、服务商改了协议）或模型返回空内容，
            // 对用户而言都是「这次没答上来」，不做区分
            log.warn("AI 响应中没有可用内容 | response={}", chatResponse);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        return chatResponse.aiMessage().text();
    }
}
