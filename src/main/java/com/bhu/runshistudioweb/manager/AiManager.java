package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
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
 * <p><b>所有异常都在这里被吃掉并转成 {@code OPERATION_ERROR(C0200)}</b>：
 * LangChain4j 抛的是 {@code LangChain4jException} 这类技术异常
 * （{@code InternalServerException / TimeoutException / ...}），直接放出去会被全局兜底成
 * B0001，还可能把服务商返回的原始报文带进日志与响应。对外统一一句
 * 「AI 服务暂时不可用，请稍后重试」。
 *
 * <p><b>日志纪律</b>：只记录 base-url / model / 异常，<b>绝不记录 api-key</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
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

    private final AiProperties aiProperties;

    /**
     * 资源加载器：用来读取系统提示词文件
     *
     * <p>它同时支持 {@code classpath:} 与 {@code file:} 两种前缀，
     * 因此「随包发布」和「指向服务器文件」两种部署方式不用改代码，只改配置
     */
    private final ResourceLoader resourceLoader;

    /**
     * 系统提示词内容：启动时从 {@link AiProperties#getSystemPromptLocation()} 读一次，之后不变
     *
     * <p>默认为空字符串而不是 null：配合 {@code StrUtil.isNotBlank} 判断，
     * 空值就是「这次请求不带 system 消息」，不用再判一次 null
     */
    private String systemPrompt = "";

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
     * 向量模型（Embedding）：把文本变成向量，RAG 入库与检索都要用
     *
     * <p>与对话模型一样，只依赖 LangChain4j 的 {@link EmbeddingModel} 接口：
     * 将来换向量模型（或换成本地模型）只动 {@link #initModels()}。
     */
    private EmbeddingModel embeddingModel;

    /**
     * 启动初始化：先加载系统提示词，再构建三个模型客户端
     * （{@code @PostConstruct} 保证配置已绑定完成）
     *
     * <p><b>提示词加载放在最前面、且无条件执行</b>：它不依赖 AI 是否配置好。
     * 否则「本地不配 api-key」时提示词不会被加载，等线上配置生效后才发现话术是空的，
     * 问题会藏得很深。
     *
     * <p>为什么一个方法建三个模型：它们共用同一份 {@code studio.ai.*} 配置，
     * 且「未配置」的处理完全一致——放在一处才能确保
     * 「对话可用但向量不可用」这种半配置状态不会出现。
     *
     * <p>未配置 base-url / api-key 时<b>不构建、也不抛异常</b>：
     * 本地不配 AI 也能正常启动应用，真正调用时才返回 C0200（见各方法内的判断）。
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

        // 向量模型：同一套 base-url / api-key，但用独立的模型名（studio.ai.embedding-model）。
        // 超时与重试跟对话模型保持一致——向量化同样是一次外部 HTTP，必须设天花板上限
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getEmbeddingModel())
                .timeout(REQUEST_TIMEOUT)
                .maxRetries(MAX_RETRIES)
                .build();

        log.info("AI 客户端初始化完成 | baseUrl={} | model={} | embeddingModel={} | timeout={}s | maxRetries={}",
                aiProperties.getBaseUrl(), aiProperties.getModel(), aiProperties.getEmbeddingModel(),
                REQUEST_TIMEOUT.toSeconds(), MAX_RETRIES);
    }

    /**
     * 启动时加载系统提示词（<b>只读一次</b>）
     *
     * <p><b>为什么只在启动时读</b>：
     * <ul>
     *     <li>同一次运行内提示词不该漂移——否则同一段对话前后两句的语气约束可能不一致；</li>
     *     <li>避免每次提问都产生一次文件 IO（匿名接口，流量不可控）。</li>
     * </ul>
     * 代价是<b>改完文件要重启</b>才生效，这与「话术是随包资源」的定位一致；
     * 需要热更新时用 {@code file:} 前缀指向服务器文件，改完重启即可（仍比重新打包快）。
     *
     * <p><b>失败策略：warn + 置空，绝不抛异常</b>——与「AI 不配也能启动」是同一条纪律。
     * 提示词缺失只是让回答少了身份约束，不该让整个应用起不来。
     *
     * <p><b>剥 BOM 与 strip</b>：Windows 编辑器保存的 UTF-8 文件常带 {@code ﻿} 前缀，
     * 不剥掉的话它会成为提示词的第一个字符（肉眼看不出来，但会污染 system 消息）；
     * {@code strip()} 去掉文件末尾的换行，避免提示词尾部带多余空白。
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
     * 带上下文调用 AI，返回回答正文
     *
     * <p><b>为什么不再把 system 提示词当参数传</b>：它启动时已从文件加载进字段
     * {@link #systemPrompt}，调用方不需要知道它从哪来。让调用方传，等于要求
     * 每个调用点都去 AiProperties 取值——那正是「提示词放哪」这个实现细节的泄露。
     *
     * @param history     上下文消息（<b>时间正序</b>，由 Service 取最近 N 条），可为空
     * @param userMessage 本次提问
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、调用失败或回答为空时抛出（C0200）
     */
    public String chat(List<AiMessageVO> history, String userMessage) {
        return invokeChat(buildMessages(history, userMessage));
    }

    /**
     * 用<b>调用方指定</b>的系统提示词调用一次 AI（<b>不带历史上下文</b>）
     *
     * <p>与 {@link #chat} 唯一的差别是 system 消息的来源：
     * {@link #chat} 用的是聊天人设（启动时从 {@link AiProperties#getSystemPromptLocation()} 加载），
     * 本方法由调用方给。<b>内容审核这类场景必须用这个方法</b>——
     * 它需要的是「审核员」人设，若带着「工作室助手」的聊天人设去判合规，模型会跑偏
     * （两种人设对「什么算合适内容」的回答完全不同）。
     *
     * <p><b>不带历史</b>是刻意的：审核是「一次一判」的独立任务，没有上下文可继承。
     * 把上一次的判定结果带进这一次，会让模型倾向于给出与上次相同的结论（锚定效应）。
     *
     * @param systemMessage 本次专用的系统提示词；为空则本次不带 system 消息
     * @param userMessage   用户消息（即待处理的内容）
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、调用失败或回答为空时抛出（C0200）
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
     * 真正发起一次模型调用（{@link #chat} 与 {@link #chatWithSystemPrompt} 共用）
     *
     * <p>抽出来的目的是<b>让两种调用走完全相同的失败处理</b>：
     * 未配置、超时、限流、回答为空，在任何一种调用路径上的表现都必须一致，
     * 否则「聊天不可用时审核却可用」这类不一致会在排查时浪费大量时间。
     *
     * @param messages 已组装好的消息列表（顺序由调用方保证）
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、调用失败或回答为空时抛出（C0200）
     */
    private String invokeChat(List<ChatMessage> messages) {
        // 未配置时直接失败：ApplicationContext 已经起来了，这里只影响这一次调用
        if (chatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

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
            log.error("调用 AI 服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
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
     * @param history     上下文消息（时间正序），可为空
     * @param userMessage 本次提问
     * @param onDelta     每收到一段增量时的回调（可为 null，表示只关心最终结果）
     * @return 完整回答（取自模型的最终响应，而不是自己拼接的增量——两者理论上一致，
     *         但以模型为准可以避免「增量丢失导致落库内容缺字」）
     * @throws BusinessException 未配置、调用失败或回答为空时抛出（C0200）
     */
    public String chatStream(List<AiMessageVO> history, String userMessage, Consumer<String> onDelta) {
        if (streamingChatModel == null) {
            log.warn("调用 AI 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

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
                log.error("等待 AI 流式响应超时 | baseUrl={} | model={}",
                        aiProperties.getBaseUrl(), aiProperties.getModel());
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉中断会让上层（例如应用关闭）失去感知
            Thread.currentThread().interrupt();
            log.error("AI 流式调用被中断", e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        } catch (Exception e) {
            log.error("调用 AI 流式服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        if (errorRef.get() != null) {
            // 流中途报错（连接断了、服务商限流）也走同一个出口
            log.error("AI 流式响应出错 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), errorRef.get());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        return extractAnswer(responseRef.get());
    }

    /**
     * 批量向量化：把一批文本变成向量（RAG 入库的第一步）
     *
     * <p><b>为什么是批量而不是逐条</b>：Embedding 接口一次可以收一批文本，
     * 逐条调用会把「一次 HTTP」放大成 N 次——网络往返与限流风险都翻 N 倍。
     * 这也和本项目其它「批量」纪律同源（场景 E 的 messageCount、场景 H 的回查）。
     *
     * <p><b>返回顺序必须与入参一一对应</b>：调用方要用第 i 个向量对应第 i 个切分块，
     * 因此这里额外校验了数量一致——数量对不上时宁可报错，
     * 也不能让向量与块错位（错位会导致检索命中内容张冠李戴，且极难排查）。
     *
     * @param texts 待向量化的文本（非空）
     * @return 与入参顺序一致的向量列表
     * @throws BusinessException 未配置、调用失败或返回数量不符时抛出（C0200，与对话同文案）
     */
    public List<Embedding> embedAll(List<String> texts) {
        if (embeddingModel == null) {
            log.warn("调用 Embedding 失败：studio.ai.base-url 或 api-key 未配置（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
        ThrowUtils.throwIf(texts == null || texts.isEmpty(), ErrorCode.PARAMS_ERROR, "待向量化的文本为空");

        // LangChain4j 的 embedAll 收 TextSegment：这里先包一层，
        // 让上层只跟「字符串」打交道（切分块的文本本身就是字符串）
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        try {
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            List<Embedding> embeddings = response == null ? null : response.content();
            ThrowUtils.throwIf(embeddings == null || embeddings.size() != texts.size(),
                    ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
            return embeddings;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 超时、限流、协议错误都收敛成同一句提示，不把服务商的原始报文带出去
            log.error("调用 Embedding 服务失败 | baseUrl={} | model={} | 文本数={}",
                    aiProperties.getBaseUrl(), aiProperties.getEmbeddingModel(), texts.size(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 组装消息列表：system 提示词 + 历史消息 + 本次提问
     *
     * <p>顺序遵循协议要求（时间正序），模型把<b>最后一条</b>当作「当前问题」；
     * system 消息始终在最前，用来约束身份与语气。
     *
     * <p>提示词取自字段 {@link #systemPrompt}（启动时从文件加载）：
     * 为空时<b>跳过 system 消息</b>而不是报错——这与「AI 不配也能启动」是同一条纪律，
     * 少一句身份约束不影响聊天本身可用。
     *
     * @param history     历史消息（可空）
     * @param userMessage 本次提问
     * @return LangChain4j 的消息列表
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
