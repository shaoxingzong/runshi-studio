package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.dev33.satoken.context.SaHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.constant.AiChatConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.AiManager;
import com.bhu.runshistudioweb.manager.AiQueryCountManager;
import com.bhu.runshistudioweb.manager.AiRateLimitManager;
import com.bhu.runshistudioweb.mapper.StudioAiMessageMapper;
import com.bhu.runshistudioweb.mapper.StudioAiSessionMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
import com.bhu.runshistudioweb.model.vo.AiSessionVO;
import com.bhu.runshistudioweb.service.AiChatService;
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 咨询服务实现
 *
 * author: shaoshing
 *
 * <p><b>本类的四道关键约束（改代码前先读）</b>：
 * <ol>
 *     <li><b>会话归属</b>：绑定用户的会话仅本人可续；匿名会话凭不可枚举雪花 ID 可续。
 *     不存在与无权<b>统一返回 A0402 同一句提示</b>，不暴露存在性；</li>
 *     <li><b>配额自增必须原子</b>：{@code SET ai_query_count = ai_query_count + 1}，
 *     绝不「读出来 +1 再写回去」（并发下互相覆盖，计数偏小＝白送额度）；</li>
 *     <li><b>HTTP 在事务之外</b>：AI 调用可能几十秒，包进事务会长时间占用数据库连接。
 *     失败时保留用户消息，返回 C0200；成功后才用 {@code TransactionTemplate}
 *     把「assistant 消息 + 会话 updated_at + 计数」包成一步写；</li>
 *     <li><b>排序必须带 id</b>：{@code created_at} 只有秒级精度，同一秒的
 *     user / assistant 两条消息时间相同，只按时间排序会出现对话记录偶发颠倒。</li>
 * </ol>
 *
 * <p><b>为什么 TransactionTemplate 是「自己 new 的」</b>：Boot 4 的
 * {@code TransactionAutoConfiguration} 只提供 {@code TransactionalOperator}（响应式事务），
 * 容器里<b>没有</b> {@code TransactionTemplate} bean（Boot 3 时代有）。直接
 * {@code @Resource TransactionTemplate} 会启动失败——这里注入
 * {@code PlatformTransactionManager}（DataSource 事务管理器一定存在）再自行构造。
 *
 * <p><b>为什么用 TransactionTemplate 而不是 @Transactional</b>：需要包进事务的
 * 只有「调用 AI 成功之后」那一小段。若把整个 chat 方法标上 {@code @Transactional}，
 * 那条外部 HTTP 请求就落进了事务里——这正是本任务要避免的事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    /** 会话不存在 / 无权访问的统一提示：两种情况共用一句，不暴露存在性 */
    private static final String SESSION_NOT_FOUND_MESSAGE = "会话不存在";

    /** 会话列表的分页默认值与上限（上限防「一次拉全表」） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /**
     * 批量统计里的列名与别名（硬编码常量，不是用户输入）
     *
     * <p>统一用常量：避免「这里写 session_id、那里写 sessionId」这种拼写漂移导致
     * 取值恒为 null、再被兜底成 0——表现为「消息数永远是 0」且不报任何错。
     */
    private static final String SESSION_ID_COLUMN = "session_id";
    private static final String COUNT_ALIAS = "cnt";

    /**
     * SSE 连接超时：模型自身超时 60s + 余量
     *
     * <p>必须大于模型的读超时：Spring 的 {@code SseEmitter} 默认只有 30s，
     * 比模型的 60s 还短——那会让长回答在服务端被提前掐断，前端看到的是「流突然没了」，
     * 而日志里没有任何错误。
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    /**
     * SSE 事件名（前端按 name 分派，改名等于契约变更）
     *
     * <p>序列固定为：{@code meta} → {@code sources} → {@code delta}* → {@code done} / {@code error}
     *
     * <p><b>{@code sources} 恒发</b>：即使没有命中资料也发一个空数组。
     * 前端因此可以无分支地写 {@code on('sources', s => setSources(s))}；
     * 若只在命中时发，前端就得靠「等一段时间没收到」来判断没有资料——那是竞态。
     */
    private static final String EVENT_META = "meta";
    private static final String EVENT_SOURCES = "sources";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    private final StudioAiSessionMapper studioAiSessionMapper;

    private final StudioAiMessageMapper studioAiMessageMapper;

    private final SysUserMapper sysUserMapper;

    private final AiManager aiManager;

    /**
     * 提问配额计数（Redis INCR + 定时回刷，）
     *
     * <p>它替代了原来的 {@code UPDATE sys_user SET ai_query_count = ai_query_count + 1}：
     * 计数从「每次提问一次 DB 写」变成「Redis 内存自增 + 批量回刷」。
     */
    private final AiQueryCountManager aiQueryCountManager;

    /**
     * 游客 IP 限流
     *
     * <p>只在「未登录」时生效，且位于最外层（配额预检与检索之前）。
     */
    private final AiRateLimitManager aiRateLimitManager;

    private final AiProperties aiProperties;

    /**
     * 知识库检索：提问前先检索资料注入模型（RAG，）
     *
     * <p>依赖方向是单向的：{@code KnowledgeDocServiceImpl} 只注入 Mapper 与配置，
     * 不依赖本类，因此不构成循环。
     */
    private final KnowledgeDocService knowledgeDocService;

    /** SSE 事件负载的 JSON 序列化：复用容器里被 JsonConfig 定制过的那个 JsonMapper */
    private final JsonMapper jsonMapper;

    /** 事务管理器：容器里没有 TransactionTemplate bean，靠它自行构造（见类注释） */
    private final PlatformTransactionManager transactionManager;

    /**
     * 事务模板：由 {@code transactionManager} 在依赖注入完成后构造
     *
     * <p>它<b>不能</b>声明成 final：Lombok 生成的构造器只做「参数 → 字段」的直接赋值，
     * 表达不了「由另一个参数二次构造」；写成 final 会让它被当成构造器参数，
     * 而容器里并不存在 TransactionTemplate 这个 bean，启动即失败。
     * 放到 {@code @PostConstruct} 里赋值，此时构造器注入已完成，取值安全。
     */
    private TransactionTemplate transactionTemplate;

    /**
     * SSE 专用线程池：<b>虚拟线程</b>（Java 21）
     *
     * <p>为什么必须换线程：SSE 要求控制器立刻返回 emitter 让 Tomcat 释放请求线程，
     * 而 LangChain4j 的流式调用是<b>阻塞式回调</b>——调用线程会被占用到流结束（最长 60s）。
     * 留在请求线程上会钉死工作线程；换成平台线程池的话，并发的流会把池吃满并排队；
     * 虚拟线程正好匹配「大量线程都在等 I/O」的场景，代价也最低。
     *
     * <p>没有额外限流：登录用户受 {@code studio.ai.query-limit} 约束；
     * 游客的 IP 级限流是后续工程化任务（DESIGN 第 7 节 R4）。
     */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 应用关闭时收掉流式线程池
     *
     * <p>用 {@code shutdownNow} 而不是优雅关闭：未完成的流此刻多半卡在模型超时上，
     * 等它们没有意义（应用都要停了），直接中断更快。
     */
    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    @Override
    public AiChatResponseVO chat(AiChatRequest request) {
        // 登录身份与来源 IP 都必须在这里（请求线程）取好：
        // Sa-Token 的登录态与 Spring 的 RequestContextHolder 都存在 ThreadLocal 里，
        // 后面的流式分支跑在虚拟线程上，拿不到（见 chatStream 里的说明）
        Long userId = currentUserIdOrNull();

        // ① 游客 IP 限流：**最外层**——
        // 超限的请求不该再走检索 / Embedding / 模型调用，那些才是真正的开销
        assertGuestRateLimit(userId, currentIpOrNull());

        // ② 校验 / 会话 / 配额 / 上下文：与流式接口走**同一个**私有方法，
        // 保证「鉴权与归属规则完全一致」是靠同一份代码，而不是靠两处人肉同步
        PreparedChat prepared = prepareChat(request, userId);

        // ④ 先落用户消息，再调 AI —— 顺序不能反。
        // 反过来的话，AI 失败时这条提问就丢了：用户明明问了，历史里却什么都没有，
        // 而且前端往往已经把它渲染在界面上了
        insertMessage(prepared.sessionId(), AiMessageRoleEnum.USER, prepared.message());

        // ⑤ 检索资料（RAG，）：位置刻意放在「配额预检与会话解析之后、模型调用之前」——
        // 检索失败不扣额度（扣额度发生在 persistAnswer），也不影响用户消息已落库
        KnowledgeDocService.RetrievalResult retrieval = retrieveSafely(prepared.message());

        // ⑥ 调 AI：注意这里**没有** @Transactional，HTTP 请求不在数据库事务内
        String answer = aiManager.chat(prepared.history(), buildUserMessage(prepared.message(), retrieval));

        // ⑦ 成功：三处写必须原子（assistant 消息 + 会话 updated_at + 配额计数）
        persistAnswer(prepared, answer);

        AiChatResponseVO responseVO = new AiChatResponseVO();
        // 每次回答都回传会话 ID：前端首次提问后存下它，即可无感续聊
        responseVO.setSessionId(prepared.sessionId());
        responseVO.setAnswer(answer);
        responseVO.setSources(retrieval.sources());
        return responseVO;
    }

    /**
     * SSE 流式提问
     *
     * <p><b>契约例外（必须知道）</b>：本接口的响应<b>不经过 {@code BaseResponse} 包装</b>——
     * SSE 的响应体是「事件流」而不是一次性 JSON，套上 {@code {code,data,message}} 反而会让
     * 前端无法边收边渲染。代价是<b>所有失败也走事件流</b>（{@code error} 事件），
     * 连参数错误、会话不存在、配额超限也不例外：它们的 HTTP 状态码都是 200，
     * 业务码放在 {@code error} 事件的 data 里（见类注释的「事件契约」）。
     *
     * <p><b>线程模型</b>：方法立刻返回 {@link SseEmitter}（让 Tomcat 释放请求线程），
     * 真正的取数与推流交给虚拟线程执行——LangChain4j 的流式调用是<b>阻塞式回调</b>，
     * 若留在请求线程上会把工作线程钉死几十秒。
     *
     * @param request 提问请求（message 必填；sessionId 可空表示新建会话）
     * @return SSE 发射器：先 {@code meta}，再若干 {@code delta}，最后 {@code done} 或 {@code error}
     */
    @Override
    public SseEmitter chatStream(AiChatRequest request) {
        // 超时要给足「模型超时 + 余量」：Spring 的 SseEmitter 默认 30s，
        // 比模型的 60s 读超时还短，会让长回答在服务端被提前掐断（表现为流突然中断）
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

        StreamState state = new StreamState();
        // 客户端断开 / 超时 / 写入报错：标记中止。
        // 标记之后：不再推送 delta，**不再落 assistant 消息、不再计数**
        // （语义与「失败」一致：用户没拿到回答，就不该扣额度、也不该留下半截回答）
        emitter.onCompletion(state::markAborted);
        emitter.onTimeout(state::markAborted);
        emitter.onError(throwable -> state.markAborted());

        // ⚠️ 登录身份必须在这里（请求线程）取好再传进去。
        // 原因：Sa-Token 的登录态与 Spring 的 RequestContextHolder 都存在 **ThreadLocal** 里，
        // 虚拟线程拿不到（实测表现为 StpUtil.isLogin() 直接抛异常，
        // 于是流里只剩一个 error 事件、连 meta 都发不出来）。
        // 这一行是本类唯一依赖「请求线程」的地方，改动时务必保留。
        Long userId = currentUserIdOrNull();
        // 来源 IP 同理必须在请求线程取好：虚拟线程里没有 RequestContextHolder
        String clientIp = currentIpOrNull();

        streamExecutor.execute(() -> streamChat(request, userId, clientIp, emitter, state));
        return emitter;
    }

    @Override
    public List<AiMessageVO> listHistory(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");
        // 历史接口的归属校验与提问完全一致：否则它会成为「探测某会话是否存在」的入口
        Long userId = currentUserIdOrNull();
        assertSessionAccessible(parsedSessionId, userId);
        return recentMessages(parsedSessionId, AiChatConstant.HISTORY_MESSAGE_LIMIT);
    }

    @Override
    public Page<AiSessionVO> listSessions(AiSessionQueryRequest request) {
        Long userId = currentUserIdOrNull();
        // 会话列表必须登录：它天然是「我的会话」，游客没有这个维度。
        // 控制器上还有 @SaCheckLogin 双保险（见 AiChatController 的说明）
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");

        AiSessionQueryRequest query = request == null ? new AiSessionQueryRequest() : request;
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<StudioAiSession> wrapper = new LambdaQueryWrapper<>();
        // 按 user_id 等值 + updated_at 倒序，正好走 预留的 idx_user_updated
        wrapper.eq(StudioAiSession::getUserId, userId);
        // 次级排序键必须是 id：updated_at 是秒级精度，同一秒活跃过的会话会并列，
        // 没有稳定键时翻页会出现「同一条会话在两页里都出现 / 都不出现」
        wrapper.orderByDesc(StudioAiSession::getUpdatedAt).orderByDesc(StudioAiSession::getId);

        // searchCount 保持默认 true —— 分页组件需要 total
        Page<StudioAiSession> sessionPage = studioAiSessionMapper.selectPage(new Page<>(current, pageSize), wrapper);

        Page<AiSessionVO> voPage = new Page<>(sessionPage.getCurrent(), sessionPage.getSize(),
                sessionPage.getTotal());
        voPage.setRecords(toSessionVOs(sessionPage.getRecords()));
        return voPage;
    }

    @Override
    public boolean deleteSession(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");

        // 归属校验与提问、历史完全一致：不存在与无权统一 A0402，不暴露存在性。
        // 这一步顺带实现了「重复删除 → A0402」：第一次删除后会话已被逻辑删除，
        // selectById 带 deleted_at = 0 条件查不到 → 与「不存在」走同一条路径
        Long userId = currentUserIdOrNull();
        assertSessionAccessible(parsedSessionId, userId);

        // 双逻辑删：父表（会话）与子表（消息）各自 UPDATE deleted_at，必须在同一事务内。
        // 与/19 的关联表清理对比：那两张关联表是**物理删除**（关系解除即无业务意义），
        // 而对话属于「用户资产」，误删需要可追溯，所以父子两张表都用**逻辑删除**——
        // 两种机制的取舍见 db/DESIGN.md 2.2
        transactionTemplate.executeWithoutResult(status -> {
            // 先子后父：与「先清关联、再删主表」的既有约定一致。
            // 若反序，父表删成功而子表删除失败时，会留下「会话已删、消息却还在」的孤儿数据
            studioAiMessageMapper.delete(new LambdaQueryWrapper<StudioAiMessage>()
                    .eq(StudioAiMessage::getSessionId, parsedSessionId));
            ThrowUtils.throwIf(studioAiSessionMapper.deleteById(parsedSessionId) != 1,
                    ErrorCode.OPERATION_ERROR, "删除会话失败");
        });
        return true;
    }

    // ==================== 同步与流式共用的「准备」与「落库」 ====================

    /**
     * 提问前的公共准备：参数校验 → 会话定位/新建 → 配额预检 → 上下文窗口
     *
     * <p><b>为什么抽成公共方法</b>：同步（{@code /ai/chat}）与流式（{@code /ai/chat/stream}）
     * 的鉴权与归属规则必须「完全一致」——唯一可靠的做法是走同一份代码。
     * 复制一份出来早晚会漂移（改了一处忘了另一处，就是越权漏洞）。
     *
     * @param request 提问请求
     * @param userId  当前登录用户 ID（null 表示游客）。
     *                <b>由调用方在请求线程上取好传进来</b>——流式路径的调用方在虚拟线程里，
     *                而 Sa-Token 的登录态存在 ThreadLocal 中，跨线程取不到
     * @return 准备结果（用户、会话、提问、上下文）
     */
    private PreparedChat prepareChat(AiChatRequest request, Long userId) {
        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getMessage() 抛 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 后再判空与计长：避免「   」这种纯空格绕过必填校验
        String message = StrUtil.trim(request.getMessage());
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提问内容不能为空");
        // 长度校验与 DTO 上的 @Size 双保险：Service 也可能被非 Web 入口直接调用
        ThrowUtils.throwIf(message.length() > AiChatConstant.MESSAGE_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "提问内容不能超过 " + AiChatConstant.MESSAGE_MAX_LENGTH + " 字");

        // ① 会话：sessionId 为空则新建（标题取首问前 30 字）；非空则校验存在性与归属
        long sessionId = resolveSession(request.getSessionId(), userId, message).getId();

        // ② 配额预检：仅登录用户计数；游客的限流属于后续工程化任务（DESIGN 已登记）
        if (userId != null) {
            assertQuotaAvailable(userId);
        }

        // ③ 上下文窗口：取最近 10 条（内部已翻正为时间正序），交给 Manager 拼 prompt
        List<AiMessageVO> history = recentMessages(sessionId, AiChatConstant.CONTEXT_MESSAGE_COUNT);

        return new PreparedChat(userId, sessionId, message, history);
    }

    /**
     * 回答落库：assistant 消息 + 会话 updated_at + 配额计数，三处写在<b>同一事务</b>内
     *
     * <p>同步与流式共用：这样「流后落库与计数 SQL 与同步接口对齐」是结构上成立的，
     * 而不是靠两边各写一遍再比对。
     *
     * @param prepared 提问准备结果
     * @param answer   回答正文
     * @return 落库后的 assistant 消息 ID（流式接口要放进 done 事件）
     */
    private long persistAnswer(PreparedChat prepared, String answer) {
        // 事务 lambda 不能直接写局部变量，用一个一元素数组当容器（比 AtomicLong 轻）
        long[] messageIdHolder = new long[1];
        transactionTemplate.executeWithoutResult(status -> {
            messageIdHolder[0] = insertMessage(prepared.sessionId(), AiMessageRoleEnum.ASSISTANT, answer);
            touchSession(prepared.sessionId());
            // ⚠️ 配额计数**刻意不在这里**：
            // 计数已改为 Redis INCR，Redis 是外部系统——写它不属于数据库事务。
            // 若放在事务内，一旦事务回滚，Redis 里的 +1 却撤不回来，就变成了「没回答成功也扣了额度」
        });

        // 事务提交之后再计数：与「HTTP 调用不进事务」是同一条纪律——
        // 外部系统的写必须留在数据库事务边界之外
        if (prepared.userId() != null) {
            aiQueryCountManager.increment(prepared.userId());
        }
        return messageIdHolder[0];
    }

    // ==================== 游客 IP 限流 ====================

    /**
     * 游客限流：<b>只对未登录用户生效</b>
     *
     * <p>登录用户走「按用户计数」的 {@code studio.ai.query-limit}，
     * 不受 IP 限流约束——同一出口 IP 下可能有多个用户（公司、校园网），
     * 按 IP 限会让他们互相拖累，而且他们本来就有配额兜底。
     * 反过来，游客没有身份，配额无从谈起，只能按 IP 兜底。
     *
     * @param userId   当前登录用户 ID（null 表示游客）
     * @param clientIp 来源 IP（由请求线程取好后传入，见 {@link #currentIpOrNull}）
     */
    private void assertGuestRateLimit(Long userId, String clientIp) {
        if (userId != null) {
            return;
        }
        aiRateLimitManager.assertAllowed(clientIp);
    }

    /**
     * 取请求来源 IP（<b>必须在请求线程调用</b>）
     *
     * <p><b>⚠️ 这个 IP 不完全可信</b>：若请求经过反向代理，
     * {@code X-Forwarded-For} / {@code X-Real-IP} 是<b>客户端可以伪造的请求头</b>——
     * 攻击者换个头就能绕过限流。因此：
     * <ul>
     *     <li>生产环境必须在<b>网关 / Nginx 层</b>重写这些头（只信任来自可信代理的链路），
     *     并把「取 IP」的责任放在最外层；</li>
     *     <li>应用层的限流只是<b>兜底</b>，不能替代网关层的真实限流。</li>
     * </ul>
     * 拿不到 IP 时返回 null，限流会放行——宁可漏限，也不能因为取不到 IP 就拒绝所有人。
     *
     * @return 来源 IP；取不到时为 null
     */
    private String currentIpOrNull() {
        try {
            // Sa-Token 的 SaRequest 没有直接的 getRemoteAddr()，
            // getSource() 返回的是原始请求对象（Spring MVC 下即 HttpServletRequest）
            Object source = SaHolder.getRequest().getSource();
            if (source instanceof HttpServletRequest request) {
                // 取的是 TCP 对端地址（getRemoteAddr），**不读 X-Forwarded-For 头**——
                // 那个头是客户端可以伪造的（伪造就能绕过限流）；
                // 生产环境若走反向代理，必须在网关层完成「真实 IP 的还原与传递」
                return request.getRemoteAddr();
            }
            return null;
        } catch (Exception e) {
            log.warn("获取请求来源 IP 失败，本次跳过 IP 限流", e);
            return null;
        }
    }

    // ==================== RAG 检索 ====================

    /**
     * 检索资料，<b>失败即降级为「无资料」</b>
     *
     * <p>降级纪律（AC ⑥）：RAG 是<b>增强</b>不是依赖——向量服务不可用、或重启后
     * 内存向量库还没重建，都不该让用户问不出话。<b>不新增错误码</b>，
     * 只在日志里留痕，用户侧表现为「这次回答没有溯源」。
     *
     * <p>为什么抓 {@code Exception} 而不是具体异常：检索链路里有
     * 「HTTP 超时 / 业务异常 / 解析失败 / 配置缺失」多种可能，
     * 它们对提问链路的意义完全一致——都是「这次没有资料」。
     *
     * @param question 用户提问
     * @return 检索结果；任何失败都返回空结果
     */
    private KnowledgeDocService.RetrievalResult retrieveSafely(String question) {
        try {
            return knowledgeDocService.retrieve(question);
        } catch (Exception e) {
            log.warn("知识库检索失败，本次无资料继续回答（不阻断提问、不新增错误码）", e);
            return KnowledgeDocService.RetrievalResult.empty();
        }
    }

    /**
     * 把检索到的资料拼进本次提问
     *
     * <p><b>资料放 user 消息，不放 system 消息</b>（这是刻意的）：
     * <ul>
     *     <li>system 消息应当<b>稳定</b>——它是「你是谁、怎么答」的行为约束，
     *     每次提问内容都不同，塞进去会让 system 随问题漂移；</li>
     *     <li>把资料贴着问题放，模型更容易把它们当成<b>当前问题的上下文</b>，
     *     而不是通用指令（实测对引用准确率有帮助）；</li>
     *     <li>历史消息里存的仍是用户原话（见 {@code insertMessage}），
     *     资料只是临时拼给本次调用的，不会污染上下文窗口。</li>
     * </ul>
     *
     * @param message   用户原始提问
     * @param retrieval 检索结果
     * @return 送进模型的 user 消息（无命中时原样返回）
     */
    private String buildUserMessage(String message, KnowledgeDocService.RetrievalResult retrieval) {
        if (!retrieval.hit()) {
            return message;
        }
        return "参考资料：\n" + retrieval.contextText() + "\n\n用户问题：" + message;
    }

    // ==================== SSE 流式 ====================

    /**
     * 流式主流程（在虚拟线程里执行）
     *
     * <p>事件序列：{@code meta} → {@code sources} → {@code delta}* → {@code done} / {@code error}
     * （{@code sources} 恒发，无命中时是空数组）。
     *
     * @param request 提问请求
     * @param userId  当前登录用户 ID（由请求线程取好传进来，见 chatStream 的说明）
     * @param emitter SSE 发射器
     * @param state   中止标记（客户端断开时会被置位）
     */
    private void streamChat(AiChatRequest request, Long userId, String clientIp,
                            SseEmitter emitter, StreamState state) {
        try {
            // ① 游客 IP 限流：与同步接口同一位置、同一语义。
            // 超限会在 try 里抛 BusinessException，被下面的 catch 转成 error 事件（HTTP 恒 200）
            assertGuestRateLimit(userId, clientIp);

            PreparedChat prepared = prepareChat(request, userId);

            // ② meta：先告知会话 ID。新建会话时前端只能从这里拿到它
            sendEvent(emitter, EVENT_META,
                    Map.of("sessionId", String.valueOf(prepared.sessionId())), state);

            // ② 用户消息先落库：失败或中断时它必须留下（与同步接口同一约定）
            insertMessage(prepared.sessionId(), AiMessageRoleEnum.USER, prepared.message());

            // ③ 检索资料（RAG，）：与同步接口同一位置、同一降级策略
            KnowledgeDocService.RetrievalResult retrieval = retrieveSafely(prepared.message());
            // ④ sources 恒发（无命中也发空数组）：前端不必靠「等一会儿没收到」来判断有没有资料
            sendEvent(emitter, EVENT_SOURCES, Map.of("sources", retrieval.sources()), state);

            // ⑤ 流式调用：每段增量直接推给前端
            String answer = aiManager.chatStream(prepared.history(),
                    buildUserMessage(prepared.message(), retrieval),
                    delta -> sendEvent(emitter, EVENT_DELTA, Map.of("delta", delta), state));

            // ⑥ 客户端已断开：不落库、不计数、也不发 done（连接都没了）
            if (state.isAborted()) {
                log.warn("SSE 已中断，跳过 assistant 消息与配额计数 | sessionId={}", prepared.sessionId());
                return;
            }

            // ⑦ 正常结束：三处写同一事务（与同步接口共用 persistAnswer），事务提交后再发 done
            long messageId = persistAnswer(prepared, answer);
            sendEvent(emitter, EVENT_DONE, Map.of("messageId", String.valueOf(messageId)), state);
            emitter.complete();
        } catch (BusinessException e) {
            // 参数错误 / 会话不存在或无权 / 配额超限 / AI 失败：统一用 error 事件表达。
            // 注意本接口的 HTTP 状态码恒为 200 —— SSE 契约的必然结果（见接口注释）
            sendEvent(emitter, EVENT_ERROR, Map.of("code", e.getCode(), "message", e.getMessage()), state);
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE 流式提问失败", e);
            sendEvent(emitter, EVENT_ERROR, Map.of("code", ErrorCode.SYSTEM_ERROR.getCode(),
                    "message", ErrorCode.SYSTEM_ERROR.getMessage()), state);
            emitter.complete();
        }
    }

    /**
     * 发送一个 SSE 事件
     *
     * <p>负载必须序列化成 JSON 字符串：SSE 的 {@code data} 是纯文本，
     * 而 delta 里可能含换行、引号与中文——手拼字符串会把换行当成新的事件字段，
     * 直接破坏事件帧（前端收到的事件会被拆错）。
     *
     * @param emitter   SSE 发射器
     * @param eventName 事件名
     * @param payload   负载（会被序列化成 JSON）
     * @param state     中止标记
     */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload,
                           StreamState state) {
        if (state.isAborted()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName)
                    .data(jsonMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 客户端断开时这里会抛 IOException：标记中止，
            // 让后续不再尝试写事件、也不再落库计数（等价于「失败」语义）
            state.markAborted();
            log.warn("SSE 事件发送失败（客户端可能已断开）| event={}", eventName);
        }
    }

    // ==================== 会话定位与归属 ====================

    /**
     * 定位会话：sessionId 为空则新建，否则校验存在性与归属
     *
     * @param sessionIdText 请求里的会话 ID（可为空）
     * @param userId        当前登录用户 ID（null 表示游客）
     * @param message       本次提问（新建会话时用于生成标题）
     * @return 会话实体
     */
    private StudioAiSession resolveSession(String sessionIdText, Long userId, String message) {
        if (StrUtil.isBlank(sessionIdText)) {
            StudioAiSession session = new StudioAiSession();
            // userId 为 null 即匿名会话：游客凭不可枚举的雪花 ID 续聊
            session.setUserId(userId);
            session.setTitle(titleOf(message));
            ThrowUtils.throwIf(studioAiSessionMapper.insert(session) != 1,
                    ErrorCode.OPERATION_ERROR, "创建会话失败");
            return session;
        }

        long sessionId = parseSessionId(sessionIdText, "会话 id 不合法");
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        // selectById 自动过滤 deleted_at = 0：已删除的会话同样按「不存在」处理
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
        return session;
    }

    /**
     * 校验会话可访问（存在 + 归属），供历史接口使用
     *
     * @param sessionId 会话 ID
     * @param userId    当前登录用户 ID（null 表示游客）
     */
    private void assertSessionAccessible(long sessionId, Long userId) {
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
    }

    /**
     * 会话归属校验
     *
     * <p>规则：匿名会话（user_id 为 null）任何人都可凭 ID 访问；
     * 绑定用户的会话只有本人可访问，其他人（含游客）一律按「不存在」处理。
     *
     * @param session 会话实体
     * @param userId  当前登录用户 ID（null 表示游客）
     */
    private void assertSessionOwner(StudioAiSession session, Long userId) {
        if (session.getUserId() == null) {
            // 匿名会话：19 位雪花 ID 无法被枚举，「知道 ID」即等价于持有凭据
            return;
        }
        // 越权与不存在返回**完全相同**的 A0402 与提示：
        // 否则攻击者可以用「A0402 提示是否有差异」来判断会话是否存在
        ThrowUtils.throwIf(!Objects.equals(session.getUserId(), userId),
                ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
    }

    /**
     * 解析会话 ID 文本
     *
     * @param sessionIdText 会话 ID 文本
     * @param blankMessage  文本为空时的提示（提问与历史接口的提示不同，故作为参数传入）
     * @return 会话 ID
     */
    private long parseSessionId(String sessionIdText, String blankMessage) {
        ThrowUtils.throwIf(StrUtil.isBlank(sessionIdText), ErrorCode.PARAMS_ERROR, blankMessage);
        // 用 Convert 而不是 Long.valueOf：脏值（如 "abc"）会得到 null 走 A0401，
        // 而不是抛 NumberFormatException 变成 500
        Long sessionId = Convert.toLong(sessionIdText, null);
        ThrowUtils.throwIf(sessionId == null || sessionId <= 0, ErrorCode.PARAMS_ERROR, "会话 id 不合法");
        return sessionId;
    }

    /**
     * 生成会话标题：首问的前 30 个字
     *
     * @param message 首次提问内容（已 trim 且非空）
     * @return 会话标题
     */
    private String titleOf(String message) {
        return message.length() <= AiChatConstant.SESSION_TITLE_MAX_LENGTH
                ? message
                : message.substring(0, AiChatConstant.SESSION_TITLE_MAX_LENGTH);
    }

    // ==================== 配额 ====================

    /**
     * 配额预检：已达上限则返回 A0501
     *
     * <p>这里读的是「当前值」，并发下两个请求可能同时通过预检，
     * 最终略微超出上限——这是刻意接受的：配额是「防护性上限」而不是计费依据，
     * 为此加锁得不偿失。真正不能错的是自增本身必须原子（见 {@link #increaseQueryCount}）。
     *
     * @param userId 登录用户 ID
     */
    private void assertQuotaAvailable(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        int limit = aiProperties.getQueryLimit() == null ? Integer.MAX_VALUE : aiProperties.getQueryLimit();
        // 必须是「DB 基准 + Redis 未落库增量」的合并值：
        // 只看 DB 的话，回刷前的那些提问都不算数（表现为「问了好几次还没到上限」），
        // 配额就成了摆设。合并读取也让预检**不依赖回刷是否发生**
        int used = aiQueryCountManager.merge(userId, user.getAiQueryCount());
        ThrowUtils.throwIf(used >= limit, ErrorCode.TOO_MANY_REQUESTS_ERROR,
                "提问次数已达上限（" + limit + " 次），请稍后再试");
    }

    // 配额 +1 已移到 AiQueryCountManager（Redis INCR，事务外执行）：
    // 本类不再直接 UPDATE sys_user，理由见 persistAnswer 里的注释

    // ==================== 消息读写 ====================

    /**
     * 取会话的最近 N 条消息（返回时已翻正为时间正序）
     *
     * @param sessionId 会话 ID
     * @param limit     取多少条
     * @return 消息 VO 列表（旧 → 新）
     */
    private List<AiMessageVO> recentMessages(long sessionId, int limit) {
        LambdaQueryWrapper<StudioAiMessage> wrapper = new LambdaQueryWrapper<>();
        // 排序键用 id 而不是 created_at：created_at 秒级精度，
        // 同秒的 user / assistant 会并列，导致对话顺序偶发颠倒（见实体注释）。
        // 雪花 id 单调递增，等价于时间序且唯一
        wrapper.eq(StudioAiMessage::getSessionId, sessionId)
                .orderByDesc(StudioAiMessage::getId);

        // searchCount=false：这里只要数据不要总数，省掉一次 COUNT 查询
        Page<StudioAiMessage> page = studioAiMessageMapper.selectPage(new Page<>(1, limit, false), wrapper);

        // 查询是「倒序取最近 N 条」，返回前必须翻正：对话界面是旧 → 新自上而下
        List<StudioAiMessage> records = new ArrayList<>(page.getRecords());
        Collections.reverse(records);
        return records.stream().map(this::toMessageVO).toList();
    }

    /**
     * 落一条消息
     *
     * @param sessionId 会话 ID
     * @param role      消息角色
     * @param content   消息正文
     * @return 新消息 ID（雪花 ID 由 MyBatis-Plus 回填到实体上，流式接口要把它放进 done 事件）
     */
    private long insertMessage(long sessionId, AiMessageRoleEnum role, String content) {
        StudioAiMessage entity = new StudioAiMessage();
        entity.setSessionId(sessionId);
        entity.setRole(role.getValue());
        entity.setContent(content);
        ThrowUtils.throwIf(studioAiMessageMapper.insert(entity) != 1,
                ErrorCode.OPERATION_ERROR, "保存消息失败");
        return entity.getId();
    }

    /**
     * 刷新会话的 updated_at（用于「最近活跃会话」排序）
     *
     * <p>用「只带 id 的实体」调 updateById：MP 会跳过 null 字段，
     * 再加上 MetaObjectHandler 的 updateFill，等价于只更新 updated_at / updated_by。
     *
     * @param sessionId 会话 ID
     */
    private void touchSession(long sessionId) {
        StudioAiSession touch = new StudioAiSession();
        touch.setId(sessionId);
        studioAiSessionMapper.updateById(touch);
    }

    /**
     * 实体转消息 VO
     *
     * <p>字段名与类型一一对应（id / role / content / createdAt），可整体拷贝；
     * VO 里没有 sessionId 与审计字段，所以脱敏是「结构上不可能泄露」。
     *
     * @param message 消息实体
     * @return 消息 VO
     */
    private AiMessageVO toMessageVO(StudioAiMessage message) {
        AiMessageVO messageVO = new AiMessageVO();
        BeanUtils.copyProperties(message, messageVO);
        return messageVO;
    }

    // ==================== 会话列表 ====================

    /**
     * 会话实体列表转 VO 列表，并批量补齐 {@code messageCount}
     *
     * @param sessions 本页会话实体
     * @return 会话 VO 列表
     */
    private List<AiSessionVO> toSessionVOs(List<StudioAiSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            // 空页必须提前返回：拿空集合去 IN () 是语法错误
            return List.of();
        }
        // 一次批量统计拿走本页全部会话的消息数（绝不在循环里逐个 count）
        Map<Long, Integer> messageCountMap =
                countMessagesBySessionIds(sessions.stream().map(StudioAiSession::getId).toList());

        return sessions.stream().map(session -> {
            AiSessionVO sessionVO = new AiSessionVO();
            sessionVO.setId(session.getId());
            sessionVO.setTitle(session.getTitle());
            sessionVO.setUpdatedAt(session.getUpdatedAt());
            // 没有消息的会话补 0：GROUP BY 不会返回「0 条」的行
            sessionVO.setMessageCount(messageCountMap.getOrDefault(session.getId(), 0));
            return sessionVO;
        }).toList();
    }

    /**
     * 批量统计多个会话的消息条数（<b>一次查询解决整页</b>）
     *
     * <p>SQL 形如
     * {@code SELECT session_id, COUNT(*) AS cnt FROM studio_ai_message
     * WHERE session_id IN (...) AND deleted_at = 0 GROUP BY session_id}。
     *
     * <p><b>这是本任务最关键的一条</b>：绝不能写成
     * {@code for (会话 s : 本页) { count(s.id) }} —— 那是最典型的 N+1，
     * 一页 20 条会变成 21 次查询。验收时会数日志里的 SQL 条数，就是为了钉住这一点。
     * 
     *
     * <p>{@code deleted_at = 0} 由 MyBatis-Plus 依 {@code @TableLogic} 自动追加，
     * 因此「已被删除的消息」不会计入条数——与列表接口的可见性保持一致。
     *
     * @param sessionIds 本页会话 ID 列表（非空）
     * @return 会话 ID → 消息条数
     */
    private Map<Long, Integer> countMessagesBySessionIds(List<Long> sessionIds) {
        // LambdaQueryWrapper 表达不了 COUNT(*)：聚合与 GROUP BY 只能用字符串列名的 QueryWrapper。
        // 列名是硬编码常量、不来自用户输入，因此不存在注入问题
        QueryWrapper<StudioAiMessage> wrapper = new QueryWrapper<>();
        wrapper.select(SESSION_ID_COLUMN, "COUNT(*) AS " + COUNT_ALIAS)
                .in(SESSION_ID_COLUMN, sessionIds)
                .groupBy(SESSION_ID_COLUMN);
        List<Map<String, Object>> rows = studioAiMessageMapper.selectMaps(wrapper);

        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object sessionId = row.get(SESSION_ID_COLUMN);
            Object count = row.get(COUNT_ALIAS);
            if (sessionId == null) {
                continue;
            }
            // 绝不硬转 (Long)：COUNT(*) 在不同驱动 / 版本下可能是 Long 或 BigInteger，
            // 硬转属于「本机测试通过、换环境 ClassCastException」的经典坑
            result.put(Convert.toLong(sessionId), count instanceof Number number ? number.intValue() : 0);
        }
        return result;
    }

    /**
     * 取当前登录用户 ID
     *
     * @return 用户 ID；未登录（游客）或 loginId 非数字时返回 null
     */
    private Long currentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        // 本项目登录统一用 Long 型用户 id；万一将来出现非数字 loginId，
        // 这里按游客处理而不是抛异常——提问不应该因为一个 id 形态问题变成 500
        return Convert.toLong(StpUtil.getLoginId(), null);
    }

    // ==================== 内部类型 ====================

    /**
     * 提问准备结果（同步与流式共用）
     *
     * <p>用 {@code record} 而不是普通类：它是纯粹的「方法返回多个值」的载体，
     * 不可变、不需要 setter，也不需要进 Spring 容器。
     *
     * @param userId    当前登录用户 ID（null 表示游客，不计配额）
     * @param sessionId 会话 ID（可能是本次新建的）
     * @param message   已 trim 的提问内容
     * @param history   上下文窗口（时间正序）
     */
    private record PreparedChat(Long userId, long sessionId, String message, List<AiMessageVO> history) {
    }

    /**
     * 流式过程的中止标记（客户端断开 / 超时 / 写事件失败时置位）
     *
     * <p>用 {@code volatile} 而不是 {@code AtomicBoolean}：这里只有「写一个 boolean、读一个 boolean」，
     * 没有复合操作，volatile 的可见性保证已经足够。
     *
     * <p>之所以需要它：SSE 的断开是**异步**通知的（onCompletion/onError 回调），
     * 而我们的流式线程可能正跑在回调里的中间——标记位让两边能安全会合，
     * 避免把「用户已经关掉页面」的回答写进库、还算进配额。
     */
    private static final class StreamState {

        private volatile boolean aborted;

        void markAborted() {
            this.aborted = true;
        }

        boolean isAborted() {
            return aborted;
        }
    }
}
