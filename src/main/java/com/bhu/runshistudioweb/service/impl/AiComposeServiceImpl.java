package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.AiManager;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.model.dto.ai.AiComposeMetaRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiComposePolishRequest;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.vo.ai.AiComposeMetaVO;
import com.bhu.runshistudioweb.service.AiComposeService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 写作助手实现：片段润色（SSE）与标题/摘要生成（同步）
 *
 * <p>四条纪律：
 * <ol>
 *     <li>准入与发帖同一口径——只认 studio_member 在队档案，不认角色标签；</li>
 *     <li>AI 只是增强：生成失败不得影响用户已经写好的草稿（润色失败不覆盖、生成失败不回填）；</li>
 *     <li>写作有独立人设：走 compose-system-prompt.txt，不复用问答的 system prompt；</li>
 *     <li>日志只记长度、耗时、是否命中，不记正文与密钥。</li>
 * </ol>
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiComposeServiceImpl implements AiComposeService {

    /** 润色片段上限：与 DTO 的 @Size 双保险（Service 也可能被非 Web 入口直接调用） */
    private static final int POLISH_MAX_LENGTH = 2000;

    /** 生成标题/摘要时允许参考的正文上限：与 DTO 的 @Size 一致 */
    private static final int META_CONTENT_MAX_LENGTH = 8000;

    /** SSE 连接超时：与问答一致的「模型 60s + 余量」 */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    /**
     * SSE 事件名（前端按 name 分派，改名等于契约变更）
     *
     * <p>只有三个：delta* → done / error。本接口不涉及会话与检索，
     * 因此没有 meta / sources——不要为了「和问答长得一样」而发空事件。
     */
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    private final AiManager aiManager;

    private final AiProperties aiProperties;

    private final StudioMemberService studioMemberService;

    private final StudioPostMapper studioPostMapper;

    /** SSE 负载序列化：复用容器里被 JsonConfig 定制过的 JsonMapper */
    private final JsonMapper jsonMapper;

    /** 读创作提示词文件：支持 classpath: 与 file: 两种前缀 */
    private final ResourceLoader resourceLoader;

    /**
     * 创作助手系统提示词：启动时读一次
     *
     * <p>空字符串而不是 null：空值表示「本次不带 system 消息」，写作仍可用，
     * 只是少了风格约束——这与「文件缺失不炸启动」是同一条降级策略。
     */
    private String composePrompt = "";

    /**
     * SSE 专用线程池：虚拟线程
     *
     * <p>与 AI 咨询同一理由：流式调用是阻塞式回调，会占住调用线程到流结束，
     * 虚拟线程正好匹配「大量线程在等 I/O」的场景。
     */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 启动时加载创作提示词
     *
     * <p>坑：必须显式 UTF-8 并剥掉 BOM——Windows 编辑器保存的 UTF-8 常带不可见字符，
     * 它会成为提示词的第一个字符。失败策略是 warn + 置空，绝不抛异常。
     */
    @PostConstruct
    void loadComposePrompt() {
        String location = aiProperties.getComposeSystemPromptLocation();
        try {
            org.springframework.core.io.Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("创作助手提示词文件不存在，本次运行不带 system 消息 | location={}", location);
                return;
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            this.composePrompt = content.strip();
            log.info("创作助手提示词加载完成 | location={} | length={}", location, this.composePrompt.length());
        } catch (Exception e) {
            log.warn("创作助手提示词加载失败，本次运行不带 system 消息 | location={}", location, e);
            this.composePrompt = "";
        }
    }

    /**
     * 应用关闭时收掉流式线程池
     *
     * <p>与 AI 咨询同一取舍：未完成的流多半卡在模型超时上，应用都要停了，等它们没有意义。
     */
    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    /**
     * 润色（SSE 入口）
     *
     * <p>登录身份必须在请求线程取好：Sa-Token 的登录态存在 ThreadLocal，
     * 虚拟线程里读不到（表现为流里只剩一个 error 事件）。
     */
    @Override
    public SseEmitter polishStream(AiComposePolishRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicBoolean aborted = new AtomicBoolean(false);
        emitter.onCompletion(() -> aborted.set(true));
        emitter.onTimeout(() -> aborted.set(true));
        emitter.onError(throwable -> aborted.set(true));

        // 唯一依赖请求线程的一行：之后全部在虚拟线程里跑
        Long userId = currentUserIdOrNull();

        streamExecutor.execute(() -> streamPolish(request, userId, emitter, aborted));
        return emitter;
    }

    /**
     * 润色主流程（虚拟线程）
     *
     * <p>失败走 error 事件而不是抛异常：方法返回发射器后，抛异常调用方也接不到。
     * 客户端断开则直接返回，不继续推流。
     */
    private void streamPolish(AiComposePolishRequest request, Long userId,
                              SseEmitter emitter, AtomicBoolean aborted) {
        try {
            long uid = requireMember(userId);
            String text = validatePolishText(request);
            long start = System.currentTimeMillis();

            // 只给「选中片段」：不带历史、不带知识库事实。
            // 润色的边界是「不改原意」，引入外部资料反而会诱导模型往里加东西
            String userMessage = "请润色下面这段文字，只改表达、不改原意、不增删事实。\n\n<<<\n"
                    + text + "\n>>>";

            String full = aiManager.chatStreamWithSystemPrompt(composePrompt, userMessage,
                    delta -> sendEvent(emitter, EVENT_DELTA, Map.of("delta", delta), aborted));

            if (aborted.get()) {
                log.info("AI 润色被客户端中断 | userId={}", uid);
                return;
            }
            // done 带完整文本：前端不必自己拼增量（增量在高频分片下偶有丢失的可能）
            sendEvent(emitter, EVENT_DONE, Map.of("text", full), aborted);
            emitter.complete();
            log.info("AI 润色完成 | userId={} | 原长={} | 时长={}ms",
                    uid, text.length(), System.currentTimeMillis() - start);
        } catch (BusinessException e) {
            sendEvent(emitter, EVENT_ERROR,
                    Map.of("code", e.getCode(), "message", e.getMessage()), aborted);
            emitter.complete();
        } catch (Exception e) {
            // 兜底只回笼统错误码：堆栈只进日志，不向前端暴露内部实现
            log.error("AI 润色失败", e);
            sendEvent(emitter, EVENT_ERROR,
                    Map.of("code", ErrorCode.SYSTEM_ERROR.getCode(),
                            "message", ErrorCode.SYSTEM_ERROR.getMessage()), aborted);
            emitter.complete();
        }
    }

    /**
     * 生成标题与摘要
     *
     * <p>上下文只有「当前正文 + 高赞历史帖风格（可空）」，**不接知识库向量检索**：
     * 标题/摘要要概括的是这篇正文，而不是工作室资料；把检索到的资料塞进来，
     * 既多一次 embedding 往返，又容易把简介/成员信息带进标题，属于负收益增强。
     *
     * <p>风格示例取不到不影响生成——降级为「只基于正文 + 写作规范」仍是可用结果。
     */
    @Override
    public AiComposeMetaVO generateMeta(AiComposeMetaRequest request) {
        long uid = requireMember(currentUserIdOrNull());
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        String content = StrUtil.trim(request.getContent());
        ThrowUtils.throwIf(StrUtil.isBlank(content), ErrorCode.PARAMS_ERROR,
                "正文不能为空，请先写点内容再生成标题和摘要");
        ThrowUtils.throwIf(content.length() > META_CONTENT_MAX_LENGTH, ErrorCode.PARAMS_ERROR,
                "正文过长，生成标题摘要最多参考 " + META_CONTENT_MAX_LENGTH + " 字");

        long start = System.currentTimeMillis();

        // 高赞历史帖：作为风格 few-shot（普通 SQL，不是向量检索），取不到同样降级
        String styles = loadStyleExamples();

        String userMessage = buildMetaUserMessage(content, request.getTitle(), styles);
        String raw = aiManager.chatWithSystemPrompt(composePrompt, userMessage);
        AiComposeMetaVO result = parseMeta(raw);

        log.info("AI 标题摘要生成完成 | userId={} | 正文长度={} | 风格示例={} | 时长={}ms",
                uid, content.length(), StrUtil.isNotBlank(styles),
                System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 取本站高赞已通过帖作为风格示例
     *
     * <p>只取标题与摘要：风格示例要的是「信息密度与语气」，不是内容本身；
     * 把正文也塞进来会让 token 成本失控，还会诱导模型抄句子。
     *
     * @return 拼好的示例文本；配置为 0、查询失败或没有数据时返回空串
     */
    private String loadStyleExamples() {
        Integer configured = aiProperties.getComposeStyleExampleCount();
        if (configured == null || configured <= 0) {
            return "";
        }
        // 上限 10：风格示例再多也不会更准，只会把正文挤出注意力
        int limit = Math.min(configured, 10);
        List<StudioPost> posts;
        try {
            posts = studioPostMapper.selectList(new LambdaQueryWrapper<StudioPost>()
                    .eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue())
                    .orderByDesc(StudioPost::getLikeCount)
                    .orderByDesc(StudioPost::getViewCount)
                    // limit 来自配置且已收敛到 1..10，不是用户输入，拼接安全
                    .last("LIMIT " + limit));
        } catch (Exception e) {
            log.warn("创作助手读取历史高赞帖失败，本次无风格示例", e);
            return "";
        }
        if (posts == null || posts.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (StudioPost post : posts) {
            if (post == null || StrUtil.isBlank(post.getTitle())) {
                continue;
            }
            lines.add(index + ". 标题：" + StrUtil.trim(post.getTitle())
                    + " ｜ 摘要：" + StrUtil.blankToDefault(StrUtil.trim(post.getSummary()), "（无摘要）"));
            index++;
        }
        return String.join("\n", lines);
    }

    /**
     * 组装标题/摘要生成的 user 消息
     *
     * <p>正文用定界符 &lt;&lt;&lt; &gt;&gt;&gt; 包起来：不划边界时，模型容易把「输出要求」
     * 也当成正文的一部分，写出概括指令而不是概括正文的标题。
     *
     * @param content 正文（已确认非空）
     * @param title 原标题，可为空
     * @param styles 风格示例，可为空
     * @return 送进模型的 user 消息
     */
    private String buildMetaUserMessage(String content, String title, String styles) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据下面的正文，生成标题和摘要。\n\n");
        sb.append("【参考风格（本站高赞文章，模仿其信息密度与语气，不要抄内容）】\n")
                .append(StrUtil.isBlank(styles) ? "（无）" : styles)
                .append("\n\n");
        sb.append("【当前标题（可参考，也可推翻）】\n")
                .append(StrUtil.isBlank(title) ? "（无）" : StrUtil.trim(title))
                .append("\n\n");
        sb.append("【正文】\n<<<\n").append(content).append("\n>>>\n\n");
        sb.append("严格输出 JSON：{\"title\":\"...\",\"summary\":\"...\"}");
        return sb.toString();
    }

    /**
     * 解析模型输出为标题与摘要
     *
     * <p>容错策略：先按「第一个 { 到最后一个 }」截取 JSON——模型常把 JSON 包在 ```json 围栏里，
     * 或前后加一句「好的，以下是结果」。截不到或解析失败一律抛 C0200 让用户重试，
     * 不做「猜一个标题」的兜底：错误的标题被回填，比报错更难发现。
     *
     * @param raw 模型原始输出
     * @return 解析结果，两个字段都非空
     * @throws BusinessException 输出为空或无法解析（C0200）
     */
    private AiComposeMetaVO parseMeta(String raw) {
        ThrowUtils.throwIf(StrUtil.isBlank(raw), ErrorCode.AI_SERVICE_ERROR, "AI 生成结果为空，请重试");

        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        ThrowUtils.throwIf(start < 0 || end <= start, ErrorCode.AI_SERVICE_ERROR,
                "AI 生成结果格式异常，请重试");

        String json = raw.substring(start, end + 1);
        AiComposeMetaVO meta;
        try {
            meta = jsonMapper.readValue(json, AiComposeMetaVO.class);
        } catch (Exception e) {
            log.warn("AI 标题摘要解析失败 | 输出长度={}", raw.length(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 生成结果格式异常，请重试");
        }
        ThrowUtils.throwIf(meta == null || StrUtil.isBlank(meta.getTitle()) || StrUtil.isBlank(meta.getSummary()),
                ErrorCode.AI_SERVICE_ERROR, "AI 生成结果不完整，请重试");

        // 标题超长直接截断：提示词已约束 40 字，这里只是防模型失控撑破前端输入框
        String title = StrUtil.trim(meta.getTitle());
        if (title.length() > 128) {
            title = title.substring(0, 128);
        }
        return new AiComposeMetaVO(title, StrUtil.trim(meta.getSummary()));
    }

    /**
     * 成员准入：与发帖同一口径
     *
     * @param userId 当前登录用户 ID，可为 null
     * @return 用户 ID（已确认是成员）
     * @throws BusinessException 未登录（A0201）或非在队成员（A0301）
     */
    private long requireMember(Long userId) {
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "请先登录后再使用 AI 写作助手");
        ThrowUtils.throwIf(studioMemberService.getActiveMemberByUserId(userId) == null,
                ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以使用 AI 写作助手");
        return userId;
    }

    /**
     * 校验润色片段
     *
     * @param request 润色请求，可为 null
     * @return 已 trim 的待润色文本
     * @throws BusinessException 请求为空、片段为空或超长（A0401）
     */
    private String validatePolishText(AiComposePolishRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String text = StrUtil.trim(request.getText());
        ThrowUtils.throwIf(StrUtil.isBlank(text), ErrorCode.PARAMS_ERROR,
                "请先在正文中选中要润色的文字");
        ThrowUtils.throwIf(text.length() > POLISH_MAX_LENGTH, ErrorCode.PARAMS_ERROR,
                "润色片段不能超过 " + POLISH_MAX_LENGTH + " 字");
        return text;
    }

    /**
     * 取当前登录用户 ID，未登录返回 null
     *
     * <p>必须在请求线程调用：Sa-Token 的登录态存在 ThreadLocal，虚拟线程取不到。
     * 这里吞掉异常，让「未登录」统一在虚拟线程里以 error 事件返回，
     * 而不是在创建 emitter 之前抛出一个前端收不到的异常。
     */
    private Long currentUserIdOrNull() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 发送一个 SSE 事件
     *
     * <p>负载必须序列化成 JSON 字符串：SSE 的 data 是纯文本，增量里可能含换行与引号，
     * 手拼会把换行当成新的事件字段、破坏事件帧。
     */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload,
                           AtomicBoolean aborted) {
        if (aborted.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName)
                    .data(jsonMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 客户端断开时这里会抛 IOException：标记中止，让后续停止写事件
            aborted.set(true);
            log.warn("SSE 事件发送失败（客户端可能已断开）| event={}", eventName);
        }
    }
}
