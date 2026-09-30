package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务调用管理器（Manager 层）
 *
 * author: shaoshing
 *
 * <p>职责：把「一段对话」翻译成 OpenAI 兼容协议的 HTTP 请求，再把响应翻回一段文本。
 * 它<b>不认识数据库、不认识业务规则</b>（配额、会话归属都在 Service），
 * 因此既可以被提问接口调用，也能被将来的「文档问答」「批量生成」复用，还能对着本地 Stub 单测。
 *
 * <p><b>三个必须踩准的点（都是实测结论，不是照搬 Boot 3 的写法）</b>：
 * <ol>
 *     <li><b>用 {@code RestClient.builder()} 自己构建，不要注入 {@code RestClient.Builder}</b>：
 *     Boot 3 时代容器里有 {@code RestClient.Builder} 自动配置 bean，但 Boot 4.1.1 把
 *     HTTP 客户端相关自动配置拆到了独立模块，当前 classpath（spring-boot-starter-webmvc）
 *     下<b>并没有</b>这个 bean——照直觉写 {@code @Resource RestClient.Builder} 会直接启动失败；</li>
 *     <li><b>超时必须显式设置</b>：{@code SimpleClientHttpRequestFactory} 默认超时为 0（无限等待），
 *     一次网络抖动就能把请求线程永久占住（连接池耗尽后整个应用不可用）。
 *     这里建连 5s、读 60s：读超时给得宽是因为模型生成本身慢，但必须有天花板；</li>
 *     <li><b>base-url 必须来自配置</b>（{@link AiProperties}）：硬编码服务商地址会让
 *     测试无法把它指向本地 Stub 服务，也会让「换个服务商」变成改代码。</li>
 * </ol>
 *
 * <p><b>所有异常都在这里被吃掉并转成 {@code OPERATION_ERROR(50001)}</b>：
 * RestClient 抛的是 {@code RestClientException} 这类技术异常，直接放出去会被全局兜底成
 * 50000 且把服务商地址、响应内容打进日志与响应里。对外统一一句「AI 服务暂时不可用，请稍后重试」。
 *
 * <p><b>日志纪律</b>：只记录 base-url / model / 异常，<b>绝不记录 api-key</b>
 * （日志会被收集、转发、截图，泄密排查时它是第一个被翻的地方）。
 */
@Slf4j
@Component
public class AiManager {

    /** 建连超时：连不上要快速失败，不能让用户干等 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 读超时：模型生成较慢，但必须有上限（默认无限等待是生产事故的常见来源） */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /** OpenAI 兼容协议的对话补全路径（拼接在 base-url 之后） */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * 系统提示词在协议里的角色名
     *
     * <p>它不属于本项目的业务取值（库里只会存 user / assistant），
     * 而是协议层面的角色，所以不放进 {@link AiMessageRoleEnum}
     */
    private static final String ROLE_SYSTEM = "system";

    /** 对外统一的失败文案：不复述底层细节（服务商报错、超时、解析失败在用户眼里是同一件事） */
    private static final String AI_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    @Resource
    private AiProperties aiProperties;

    /**
     * 用容器里的 {@code JsonMapper}（Boot 4 自动配置 + {@code JsonConfig} 定制），
     * 不自己 new：自建实例不共享全局配置，时间格式、null 策略都可能与接口不一致
     */
    @Resource
    private JsonMapper jsonMapper;

    /** 只构建一次：RestClient 是线程安全的，每次调用都 new 会白白浪费连接池与配置开销 */
    private RestClient restClient;

    /**
     * 初始化 RestClient（{@code @PostConstruct} 保证配置已绑定完成）
     *
     * <p>base-url 为空时<b>不抛异常</b>：本地没配 AI 配置也要能启动应用，
     * 真正调用时再返回 50001（见 {@link #chat}）
     */
    @PostConstruct
    void initRestClient() {
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory());
        if (StrUtil.isNotBlank(aiProperties.getBaseUrl())) {
            builder.baseUrl(aiProperties.getBaseUrl());
        }
        this.restClient = builder.build();
        // 只打印地址与模型；api-key 是否配置用布尔值表示，绝不打印内容
        log.info("AI 客户端初始化完成 | baseUrl={} | model={} | apiKeyConfigured={}",
                aiProperties.getBaseUrl(), aiProperties.getModel(), StrUtil.isNotBlank(aiProperties.getApiKey()));
    }

    /**
     * 带上下文调用 AI，返回回答正文
     *
     * @param systemPrompt 系统提示词（约束身份与语气，来自配置）
     * @param history      上下文消息（<b>时间正序</b>，由 Service 取最近 N 条），可为空
     * @param userMessage  本次提问
     * @return AI 回答正文（已校验非空）
     * @throws BusinessException 未配置、调用失败、响应结构不符或回答为空时抛出（50001）
     */
    public String chat(String systemPrompt, List<AiMessageVO> history, String userMessage) {
        // 未配置时直接失败：ApplicationContext 已经起来了，这里只影响这一次调用
        if (StrUtil.isBlank(aiProperties.getBaseUrl()) || StrUtil.isBlank(aiProperties.getApiKey())) {
            log.warn("调用 AI 失败：未配置 studio.ai.base-url 或 api-key（本地不配置不影响其它接口）");
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        String payloadJson = buildPayloadJson(systemPrompt, history, userMessage);
        String responseJson;
        try {
            responseJson = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                    .body(payloadJson)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // 网络异常、超时、非 2xx 响应都在这里被收敛。不要把 e 直接抛给上层：
            // 那样响应会变成 50000，并且异常信息里可能带着服务商返回的原始报文
            log.error("调用 AI 服务失败 | baseUrl={} | model={}", aiProperties.getBaseUrl(),
                    aiProperties.getModel(), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }

        return parseAnswer(responseJson);
    }

    /**
     * 构造 OpenAI 兼容协议的请求体
     *
     * <p>用 {@code Map} 拼装再交给 {@code JsonMapper} 序列化，而不是手写 JSON 字符串：
     * 手拼字符串在内容里出现引号、换行、中文时极易产生非法 JSON。
     *
     * @param systemPrompt 系统提示词
     * @param history      上下文（时间正序）
     * @param userMessage  本次提问
     * @return 请求体 JSON
     */
    private String buildPayloadJson(String systemPrompt, List<AiMessageVO> history, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        // ① 系统提示词永远在最前：它定义 AI 的身份与边界
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(message(ROLE_SYSTEM, systemPrompt));
        }
        // ② 历史消息原样带上（role 与 content 都取自库里的记录）
        if (history != null) {
            for (AiMessageVO item : history) {
                messages.add(message(item.getRole(), item.getContent()));
            }
        }
        // ③ 本次提问放最后：协议要求 messages 按时间正序，模型把最后一条当作「当前问题」
        messages.add(message(AiMessageRoleEnum.USER.getValue(), userMessage));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", aiProperties.getModel());
        payload.put("messages", messages);
        // 显式声明非流式：本项目接口是一次性返回整段回答，
        // 不写这个字段时部分服务商默认值不同，可能返回 SSE 流导致解析失败
        payload.put("stream", false);
        return jsonMapper.writeValueAsString(payload);
    }

    /**
     * 解析响应，取出回答正文
     *
     * <p>只取 {@code choices[0].message.content}：OpenAI 兼容协议的响应结构固定，
     * 其它字段（usage、finish_reason）本期不用，不做多余解析。
     *
     * @param responseJson 响应体
     * @return 回答正文
     * @throws BusinessException 结构不符或正文为空时抛出（50001）
     */
    private String parseAnswer(String responseJson) {
        try {
            JsonNode root = jsonMapper.readTree(responseJson);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String answer = contentNode.asString();
            if (StrUtil.isBlank(answer)) {
                // 结构不符（例如被网关拦截、服务商改了协议）或模型返回空内容，
                // 对用户而言都是「这次没答上来」，不做区分
                log.warn("AI 响应中没有可用内容 | response={}", StrUtil.maxLength(responseJson, 500));
                throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
            }
            return answer;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // JSON 解析失败：响应不是合法 JSON（网关错误页、HTML 等）
            log.error("解析 AI 响应失败 | response={}", StrUtil.maxLength(responseJson, 500), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, AI_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 构造一条协议消息（role + content）
     *
     * @param role    角色
     * @param content 内容
     * @return 不可变的消息 Map
     */
    private Map<String, String> message(String role, String content) {
        // Map.of 不接受 null：role / content 由调用方保证非空（库里 NOT NULL，配置有默认值）
        return Map.of("role", role, "content", content);
    }

    /**
     * 构造带超时的请求工厂
     *
     * @return 已设置建连与读超时的工厂
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 默认是「无限等待」，必须显式设置（见类注释第 2 点）
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
