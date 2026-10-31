package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.config.AiProperties;
import com.bhu.runshistudioweb.constant.AiChatConstant;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.AiCircuitBreaker;
import com.bhu.runshistudioweb.manager.AiManager;
import com.bhu.runshistudioweb.manager.AiQueryCountManager;
import com.bhu.runshistudioweb.mapper.StudioAiMessageMapper;
import com.bhu.runshistudioweb.mapper.StudioAiSessionMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum;
import com.bhu.runshistudioweb.model.vo.ai.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.ai.AiMessageVO;
import com.bhu.runshistudioweb.model.vo.ai.AiSessionVO;
import com.bhu.runshistudioweb.service.AiChatService;
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
 * AI 咨询服务实现：同步问答、SSE 流式问答、会话与历史管理
 *
 * 配额自增走 Redis INCR 保证原子；模型调用留在事务之外；排序必须带 id
 * （created_at 只有秒级精度，同秒的 user / assistant 会颠倒）。
 *
 * @author shaoshing
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

    /** 批量统计里的列名与别名（硬编码常量，不是用户输入） */
    private static final String SESSION_ID_COLUMN = "session_id";
    private static final String COUNT_ALIAS = "cnt";

    /** SSE 连接超时：模型自身超时 60s + 余量 */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    /** SSE 事件名（前端按 name 分派，改名等于契约变更） */
    private static final String EVENT_META = "meta";
    private static final String EVENT_SOURCES = "sources";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    /** 会话表 Mapper */
    private final StudioAiSessionMapper studioAiSessionMapper;

    /** 消息表 Mapper */
    private final StudioAiMessageMapper studioAiMessageMapper;

    /** 用户表 Mapper */
    private final SysUserMapper sysUserMapper;

    /**
     * 大模型调用入口（同步对话 / 流式对话 / 批量向量化）
     *
     * 超时、重试、熔断计数、错误码收敛都在它内部完成，本类只负责「什么时候调、传什么」。
     */
    private final AiManager aiManager;

    /** 提问配额计数（Redis INCR + 定时回刷） */
    private final AiQueryCountManager aiQueryCountManager;

    /** AI 相关配置（提问次数上限、模型参数、检索参数） */
    private final AiProperties aiProperties;

    /** 知识库检索：提问前先检索资料注入模型（RAG：先从自有知识库找相关片段，再连同问题一起交给模型） */
    private final KnowledgeDocService knowledgeDocService;

    /** AI 熔断器 */
    private final AiCircuitBreaker circuitBreaker;

    /** SSE 事件负载的 JSON 序列化：复用容器里被 JsonConfig 定制过的那个 JsonMapper */
    private final JsonMapper jsonMapper;

    /** 事务管理器 */
    private final PlatformTransactionManager transactionManager;

    /** 事务模板：由 transactionManager 在依赖注入完成后构造 */
    private TransactionTemplate transactionTemplate;

    /** SSE 专用线程池：虚拟线程（Java 21） */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 构造事务模板（容器启动时执行一次）
     *
     * @see #transactionTemplate
     */
    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 应用关闭时收掉流式线程池 */
    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    /** 同步问答（一次返回完整回答） */
    @Override
    public AiChatResponseVO chat(AiChatRequest request) {
        Long userId = currentUserIdOrNull();

        PreparedChat prepared = prepareChat(request, userId);

        insertMessage(prepared.sessionId(), AiMessageRoleEnum.USER, prepared.message());

        KnowledgeDocService.RetrievalResult retrieval = retrieveSafely(prepared.message());

        // 调 AI：注意这里没有 @Transactional，HTTP 请求不在数据库事务内
        String answer = aiManager.chat(prepared.history(), buildUserMessage(prepared.message(), retrieval));

        // 成功：消息与会话活跃时间必须同生共死，配额计数则刻意留到事务提交之后
        persistAnswer(prepared, answer);

        AiChatResponseVO responseVO = new AiChatResponseVO();
        // 每次回答都回传会话 ID：前端首次提问后存下它，即可无感续聊
        responseVO.setSessionId(prepared.sessionId());
        responseVO.setAnswer(answer);
        responseVO.setSources(retrieval.sources());
        return responseVO;
    }

    /** SSE 流式提问：把模型生成的每个片段即时推给前端 */
    @Override
    public SseEmitter chatStream(AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

        StreamState state = new StreamState();
        emitter.onCompletion(state::markAborted);
        emitter.onTimeout(state::markAborted);
        emitter.onError(throwable -> state.markAborted());

        Long userId = currentUserIdOrNull();

        streamExecutor.execute(() -> streamChat(request, userId, emitter, state));
        return emitter;
    }

    /** 会话历史（时间正序，最多最近若干条） */
    @Override
    public List<AiMessageVO> listHistory(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");
        // 历史接口的归属校验与提问完全一致：否则它会成为「探测某会话是否存在」的入口
        long userId = requireLoginUserId();
        assertSessionAccessible(parsedSessionId, userId);
        return recentMessages(parsedSessionId, AiChatConstant.HISTORY_MESSAGE_LIMIT);
    }

    /** 我的会话列表（按最近活跃排序） */
    @Override
    public Page<AiSessionVO> listSessions(AiSessionQueryRequest request) {
        Long userId = currentUserIdOrNull();
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");

        // 请求对象为 null 时兜一个空对象，省掉后面每处都判空
        AiSessionQueryRequest query = request == null ? new AiSessionQueryRequest() : request;
        long current = (query.getCurrent() == null || query.getCurrent() < 1) ? 1L : query.getCurrent();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? DEFAULT_PAGE_SIZE : query.getPageSize();
        // 上限收敛：不设上限时一个 pageSize=100000 就能把整表读进内存
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<StudioAiSession> wrapper = new LambdaQueryWrapper<>();
        // 按 user_id 等值 + updated_at 倒序，正好走 (user_id, updated_at) 上的联合索引
        wrapper.eq(StudioAiSession::getUserId, userId);
        wrapper.orderByDesc(StudioAiSession::getUpdatedAt).orderByDesc(StudioAiSession::getId);

        // searchCount 保持默认 true —— 分页组件需要 total
        Page<StudioAiSession> sessionPage = studioAiSessionMapper.selectPage(new Page<>(current, pageSize), wrapper);

        Page<AiSessionVO> voPage = new Page<>(sessionPage.getCurrent(), sessionPage.getSize(),
                sessionPage.getTotal());
        voPage.setRecords(toSessionVOs(sessionPage.getRecords()));
        return voPage;
    }

    /** 删除会话（逻辑删除父子两张表） */
    @Override
    public boolean deleteSession(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");

        long userId = requireLoginUserId();
        assertSessionAccessible(parsedSessionId, userId);

        transactionTemplate.executeWithoutResult(status -> {
            studioAiMessageMapper.delete(new LambdaQueryWrapper<StudioAiMessage>()
                    .eq(StudioAiMessage::getSessionId, parsedSessionId));
            ThrowUtils.throwIf(studioAiSessionMapper.deleteById(parsedSessionId) != 1,
                    ErrorCode.OPERATION_ERROR, "删除会话失败");
        });
        return true;
    }

    // 同步与流式共用的「准备」与「落库」

    /** 提问前的公共准备：参数校验 → 会话定位/新建 → 配额预检 → 上下文窗口 */
    private PreparedChat prepareChat(AiChatRequest request, Long userId) {
        // ⓪ AI 服务不向游客开放：必须登录。同步与流式共用本方法，门槛只此一处
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "请先登录后再使用 AI 助手");

        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getMessage() 抛 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 后再判空与计长：避免「 」这种纯空格绕过必填校验
        String message = StrUtil.trim(request.getMessage());
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提问内容不能为空");
        ThrowUtils.throwIf(message.length() > AiChatConstant.MESSAGE_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "提问内容不能超过 " + AiChatConstant.MESSAGE_MAX_LENGTH + " 字");

        // 会话：sessionId 为空则新建（标题取首问前 30 字）；非空则校验存在性与归属
        long sessionId = resolveSession(request.getSessionId(), userId, message).getId();

        // 配额预检：普通用户受次数上限约束；成员 / 管理员不限额（unlimited = true）
        boolean unlimited = assertQuotaAvailable(userId);

        List<AiMessageVO> history = recentMessages(sessionId, AiChatConstant.CONTEXT_MESSAGE_COUNT);

        return new PreparedChat(userId, sessionId, message, history, unlimited);
    }

    /** 回答落库：assistant 消息 + 会话活跃时间进事务，配额计数留在事务之后 */
    private long persistAnswer(PreparedChat prepared, String answer) {
        // 事务 lambda 不能直接写局部变量，用一个一元素数组当容器（比 AtomicLong 轻）
        long[] messageIdHolder = new long[1];
        transactionTemplate.executeWithoutResult(status -> {
            messageIdHolder[0] = insertMessage(prepared.sessionId(), AiMessageRoleEnum.ASSISTANT, answer);
            touchSession(prepared.sessionId());
        });

        if (prepared.userId() != null && !prepared.unlimited()) {
            aiQueryCountManager.increment(prepared.userId());
        }
        return messageIdHolder[0];
    }

    // RAG 检索

    /** 检索资料，失败即降级为「无资料」 */
    private KnowledgeDocService.RetrievalResult retrieveSafely(String question) {
        if (circuitBreaker.isOpen()) {
            log.warn("AI 服务熔断中，跳过知识库检索（embedding 调用必然失败，不再白等外部往返）");
            return KnowledgeDocService.RetrievalResult.empty();
        }
        try {
            return knowledgeDocService.retrieve(question);
        } catch (Exception e) {
            log.warn("知识库检索失败，本次无资料继续回答（不阻断提问、不新增错误码）", e);
            return KnowledgeDocService.RetrievalResult.empty();
        }
    }

    private String buildUserMessage(String message, KnowledgeDocService.RetrievalResult retrieval) {
        if (!retrieval.hit()) {
            return message;
        }
        return "参考资料：\n" + retrieval.contextText() + "\n\n用户问题：" + message;
    }

    // SSE 流式

    /** 流式主流程（在虚拟线程里执行） */
    private void streamChat(AiChatRequest request, Long userId,
                            SseEmitter emitter, StreamState state) {
        try {
            PreparedChat prepared = prepareChat(request, userId);

            // meta：先告知会话 ID。新建会话时前端只能从这里拿到它
            sendEvent(emitter, EVENT_META,
                    Map.of("sessionId", String.valueOf(prepared.sessionId())), state);

            // 用户消息先落库：失败或中断时它必须留下（与同步接口同一约定）
            insertMessage(prepared.sessionId(), AiMessageRoleEnum.USER, prepared.message());

            // 检索资料（RAG）：与同步接口同一位置、同一降级策略
            KnowledgeDocService.RetrievalResult retrieval = retrieveSafely(prepared.message());
            // sources 恒发（无命中也发空数组）：前端不必靠「等一会儿没收到」来判断有没有资料
            sendEvent(emitter, EVENT_SOURCES, Map.of("sources", retrieval.sources()), state);

            String answer = aiManager.chatStream(prepared.history(),
                    buildUserMessage(prepared.message(), retrieval),
                    delta -> sendEvent(emitter, EVENT_DELTA, Map.of("delta", delta), state));

            if (state.isAborted()) {
                log.warn("SSE 已中断，跳过 assistant 消息与配额计数 | sessionId={}", prepared.sessionId());
                return;
            }

            long messageId = persistAnswer(prepared, answer);
            sendEvent(emitter, EVENT_DONE, Map.of("messageId", String.valueOf(messageId)), state);
            emitter.complete();
        } catch (BusinessException e) {
            sendEvent(emitter, EVENT_ERROR, Map.of("code", e.getCode(), "message", e.getMessage()), state);
            emitter.complete();
        } catch (Exception e) {
            // 兜底分支只回笼统的系统错误码：堆栈只进日志，不向前端暴露内部实现细节
            log.error("SSE 流式提问失败", e);
            sendEvent(emitter, EVENT_ERROR, Map.of("code", ErrorCode.SYSTEM_ERROR.getCode(),
                    "message", ErrorCode.SYSTEM_ERROR.getMessage()), state);
            emitter.complete();
        }
    }

    /** 发送一个 SSE 事件 */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload,
                           StreamState state) {
        if (state.isAborted()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName)
                    .data(jsonMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            state.markAborted();
            log.warn("SSE 事件发送失败（客户端可能已断开）| event={}", eventName);
        }
    }

    // 会话定位与归属

    /** 定位会话：sessionId 为空则新建，否则校验存在性与归属 */
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
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
        return session;
    }

    /** 校验会话可访问（存在 + 归属），供历史与删除接口使用 */
    private void assertSessionAccessible(long sessionId, Long userId) {
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
    }

    /** 会话归属校验 */
    private void assertSessionOwner(StudioAiSession session, Long userId) {
        if (session.getUserId() == null) {
            // 匿名会话：19 位雪花 ID 无法被枚举，「知道 ID」即等价于持有凭据
            return;
        }
        ThrowUtils.throwIf(!Objects.equals(session.getUserId(), userId),
                ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
    }

    /** 解析会话 ID 文本 */
    private long parseSessionId(String sessionIdText, String blankMessage) {
        ThrowUtils.throwIf(StrUtil.isBlank(sessionIdText), ErrorCode.PARAMS_ERROR, blankMessage);
        Long sessionId = Convert.toLong(sessionIdText, null);
        ThrowUtils.throwIf(sessionId == null || sessionId <= 0, ErrorCode.PARAMS_ERROR, "会话 id 不合法");
        return sessionId;
    }

    /** 生成会话标题：首问的前 30 个字 */
    private String titleOf(String message) {
        return message.length() <= AiChatConstant.SESSION_TITLE_MAX_LENGTH
                ? message
                : message.substring(0, AiChatConstant.SESSION_TITLE_MAX_LENGTH);
    }

    /** 配额预检 */
    private boolean assertQuotaAvailable(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        // 成员 / 管理员：无限使用，且不计数
        if (isUnlimitedRole(user.getUserRole())) {
            return true;
        }

        // 配置为 null 时按不限制处理：一个漏写的配置项不该把全体普通用户挡在门外
        int limit = aiProperties.getQueryLimit() == null ? Integer.MAX_VALUE : aiProperties.getQueryLimit();
        int used = aiQueryCountManager.merge(userId, user.getAiQueryCount());
        ThrowUtils.throwIf(used >= limit, ErrorCode.TOO_MANY_REQUESTS_ERROR,
                "登录用户的 AI 提问次数已用完（共 " + limit + " 次），成员与管理员可不限次使用");
        return false;
    }

    /** 是否为「不限额」角色（成员 / 管理员） */
    private static boolean isUnlimitedRole(String role) {
        return UserRoleConstant.MEMBER.equals(role) || UserRoleConstant.ADMIN.equals(role);
    }

    // 消息读写

    /** 取会话的最近 N 条消息（返回时已翻正为时间正序） */
    private List<AiMessageVO> recentMessages(long sessionId, int limit) {
        LambdaQueryWrapper<StudioAiMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioAiMessage::getSessionId, sessionId)
                .orderByDesc(StudioAiMessage::getId);

        // searchCount=false：这里只要数据不要总数，省掉一次 COUNT 查询
        Page<StudioAiMessage> page = studioAiMessageMapper.selectPage(new Page<>(1, limit, false), wrapper);

        List<StudioAiMessage> records = new ArrayList<>(page.getRecords());
        Collections.reverse(records);
        return records.stream().map(this::toMessageVO).toList();
    }

    /** 落一条消息 */
    private long insertMessage(long sessionId, AiMessageRoleEnum role, String content) {
        StudioAiMessage entity = new StudioAiMessage();
        entity.setSessionId(sessionId);
        // 存枚举的字符串值而不是序号：序号随枚举顺序变化，字符串是自解释的
        entity.setRole(role.getValue());
        entity.setContent(content);
        ThrowUtils.throwIf(studioAiMessageMapper.insert(entity) != 1,
                ErrorCode.OPERATION_ERROR, "保存消息失败");
        return entity.getId();
    }

    /** 刷新会话的 updated_at（用于「最近活跃会话」排序） */
    private void touchSession(long sessionId) {
        StudioAiSession touch = new StudioAiSession();
        touch.setId(sessionId);
        studioAiSessionMapper.updateById(touch);
    }

    /** 实体转消息 VO */
    private AiMessageVO toMessageVO(StudioAiMessage message) {
        AiMessageVO messageVO = new AiMessageVO();
        BeanUtils.copyProperties(message, messageVO);
        return messageVO;
    }

    // 会话列表

    /** 会话实体列表转 VO 列表，并批量补齐 messageCount */
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

    /** 批量统计多个会话的消息条数（一次查询解决整页） */
    private Map<Long, Integer> countMessagesBySessionIds(List<Long> sessionIds) {
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
            result.put(Convert.toLong(sessionId), count instanceof Number number ? number.intValue() : 0);
        }
        return result;
    }

    /** 取当前登录用户 ID */
    private Long currentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        return Convert.toLong(StpUtil.getLoginId(), null);
    }

    /** 取当前登录用户 ID，未登录直接拒绝（A0201） */
    private long requireLoginUserId() {
        Long userId = currentUserIdOrNull();
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "请先登录后再使用 AI 助手");
        return userId;
    }

    // 内部类型

    /** 提问准备结果（同步与流式共用） */
    private record PreparedChat(Long userId, long sessionId, String message, List<AiMessageVO> history,
                                boolean unlimited) {
    }

    /** 流式过程的中止标记（客户端断开 / 超时 / 写事件失败时置位） */
    private static final class StreamState {

        private volatile boolean aborted;

        /** 置位中止标记 */
        void markAborted() {
            this.aborted = true;
        }

        /**
         * 是否已中止
         *
         * @return true 表示客户端已断开、超时或写事件失败
         */
        boolean isAborted() {
            return aborted;
        }
    }
}
