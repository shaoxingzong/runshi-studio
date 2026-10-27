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
 * 四条约束：会话归属（绑定用户的仅本人可续，匿名会话凭不可枚举雪花 ID 可续，
 * 不存在与无权统一 A0402）；配额自增必须原子（Redis INCR）；HTTP 调用留在事务之外；
 * 排序必须带 id（created_at 只有秒级精度，同秒的 user / assistant 会颠倒）。
 *
 * 顺序固定：校验登录 → 配额预检 → 会话解析 → 用户消息落库 → 检索资料 → 调模型
 * → 事务内落回答 → 事务外计数。
 *
 * 坑：{@code TransactionTemplate} 必须自己 new——当前 Spring Boot 版本只提供响应式
 * {@code TransactionalOperator}，容器里没有该 bean，直接注入会启动失败。
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

    /**
     * 批量统计里的列名与别名（硬编码常量，不是用户输入）
     *
     * 统一用常量：避免「这里写 session_id、那里写 sessionId」的拼写漂移，
     * 那会让取值恒为 null 再被兜底成 0，表现为「消息数永远是 0」且不报错。
     */
    private static final String SESSION_ID_COLUMN = "session_id";
    private static final String COUNT_ALIAS = "cnt";

    /**
     * SSE 连接超时：模型自身超时 60s + 余量
     *
     * 必须显式设置：{@code SseEmitter} 默认只有 30s，比模型的 60s 读超时还短，
     * 长回答会在服务端被提前掐断，前端看到「流突然没了」而日志里没有任何错误。
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    /**
     * SSE 事件名（前端按 name 分派，改名等于契约变更）
     *
     * 序列固定为：meta → sources → delta* → done / error。改了名字前端监听会静默失效，
     * 表现为「界面一直转圈」而不是报错。sources 恒发（无命中也发空数组），
     * 前端才能无分支处理；只在命中时发就得靠「等一段时间没收到」判断，那是竞态。
     */
    private static final String EVENT_META = "meta";
    private static final String EVENT_SOURCES = "sources";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    /**
     * 会话表 Mapper
     *
     * 只做四种单表操作：按主键查、按用户分页查、插入、逻辑删除，没有业务规则。
     */
    private final StudioAiSessionMapper studioAiSessionMapper;

    /**
     * 消息表 Mapper
     *
     * 每次提问插入 user 与 assistant 两条；取上下文与历史按会话倒序分页，
     * 列表页按会话批量统计条数。全是单表操作。
     */
    private final StudioAiMessageMapper studioAiMessageMapper;

    /**
     * 用户表 Mapper
     *
     * 只读两个字段：角色（判断是否不限额）与提问次数基准值。不注入用户 Service：
     * 那属于账号模块，这里只需一次按主键读取，也不会形成模块间循环。
     */
    private final SysUserMapper sysUserMapper;

    /**
     * 大模型调用入口（同步对话 / 流式对话 / 批量向量化）
     *
     * 超时、重试、熔断计数、错误码收敛都在它内部完成，本类只负责「什么时候调、传什么」。
     */
    private final AiManager aiManager;

    /**
     * 提问配额计数（Redis INCR + 定时回刷）
     *
     * 不用 {@code UPDATE ... SET ai_query_count = ai_query_count + 1}：每次提问都写热点行
     * 会在并发下排队。改成 Redis 自增 + 定时批量回刷后，数据库只承担周期性合并写；
     * 读取用「数据库基准 + Redis 增量」的合并值，预检不依赖回刷是否已发生。
     */
    private final AiQueryCountManager aiQueryCountManager;

    /**
     * AI 相关配置（提问次数上限、模型参数、检索参数）
     *
     * 上限做成配置而不是常量：它属于运营口径，调整时不该走一次发布。
     */
    private final AiProperties aiProperties;

    /**
     * 知识库检索：提问前先检索资料注入模型（RAG：先从自有知识库找相关片段，
     * 再连同问题一起交给模型）
     *
     * 依赖方向单向：{@code KnowledgeDocServiceImpl} 不依赖本类，因此不构成循环。
     * 这条方向必须保持，否则构造器注入会在启动时直接失败。
     */
    private final KnowledgeDocService knowledgeDocService;

    /**
     * AI 熔断器
     *
     * 只用于「提前绕开注定失败的检索」这类只读判断。真正的失败计数在 {@code AiManager}
     * 内部完成——那里才是外部调用的发生地。
     */
    private final AiCircuitBreaker circuitBreaker;

    /** SSE 事件负载的 JSON 序列化：复用容器里被 JsonConfig 定制过的那个 JsonMapper */
    private final JsonMapper jsonMapper;

    /**
     * 事务管理器
     *
     * 容器里没有 {@code TransactionTemplate} bean（自动配置只提供响应式的
     * {@code TransactionalOperator}），所以注入这个一定存在的事务管理器，再自己构造模板。
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * 事务模板：由 transactionManager 在依赖注入完成后构造
     *
     * 不能声明成 final：Lombok 生成的构造器只做「参数 → 字段」直接赋值，
     * 写成 final 会被当成构造器参数，而容器里没有这个 bean，启动即失败。
     * 放到 {@code @PostConstruct} 里赋值，此时构造器注入已完成。
     */
    private TransactionTemplate transactionTemplate;

    /**
     * SSE 专用线程池：虚拟线程（Java 21）
     *
     * SSE 要求控制器立刻返回 emitter 让 Tomcat 释放请求线程，而 LangChain4j 的流式调用
     * 是阻塞式回调，会把调用线程占到流结束（最长 60s）。平台线程池会被并发的流吃满并排队，
     * 虚拟线程正好匹配「大量线程都在等 I/O」的场景。
     *
     * 这里不做限流：登录用户受配额约束，游客的 IP 级限流由另一套组件负责，策略集中在一处。
     */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 构造事务模板（容器启动时执行一次）
     *
     * 容器里没有 {@code TransactionTemplate} bean，只能自己 new。放在
     * {@code @PostConstruct} 而不是字段初始化：字段初始化时构造器参数还没赋给
     * transactionManager，那时 new 出来的是个空壳。
     *
     * @see #transactionTemplate
     */
    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 应用关闭时收掉流式线程池
     *
     * 用 shutdownNow 而不是优雅关闭：未完成的流此刻多半卡在模型超时上，应用都要停了，
     * 等它们没有意义。中断引发的异常由 {@code sendEvent} 的 catch 兜住并标记中止，
     * 用户看到的是流中断，而不是服务端报错。
     */
    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    /**
     * 同步问答（一次返回完整回答）
     *
     * 方法不能标 {@code @Transactional}：模型生成一段回答可能要几十秒，事务一旦把它包住，
     * 数据库连接就被占着几十秒，并发稍高就会耗尽连接池。只有「回答落库」那一小段用
     * {@code TransactionTemplate} 手动开事务。用户消息必须在调用模型之前落库，
     * 否则模型一失败，用户的提问就丢了。
     *
     * 坑：上下文窗口必须在用户消息落库之前取好，否则本次提问会作为历史再送进模型一遍。
     *
     * @param request 提问请求：message 必填且不超过 500 字；sessionId 可空表示新建会话
     * @return 会话 ID、回答正文、引用到的资料列表（无命中时是空列表，不是 null）
     * @throws BusinessException 参数不合法（A0401）、会话无权访问（A0402）、
     * 提问次数超限（A0501）、AI 调用失败（C0200）
     */
    @Override
    public AiChatResponseVO chat(AiChatRequest request) {
        // 登录身份必须在这里（请求线程）取好：
        // Sa-Token 的登录态存在 ThreadLocal 里，换一个线程就读不到，
        // 所以身份要作为参数一路传下去，而不是在用到的地方再取一次
        Long userId = currentUserIdOrNull();

        // ① 校验 / 会话 / 配额 / 上下文：与流式接口走同一个私有方法，
        // 保证「鉴权与归属规则完全一致」是靠同一份代码，而不是靠两处人肉同步
        PreparedChat prepared = prepareChat(request, userId);

        // ④ 先落用户消息，再调 AI —— 顺序不能反。
        // 反过来的话，AI 失败时这条提问就丢了：用户明明问了，历史里却什么都没有，
        // 而且前端往往已经把它渲染在界面上了
        insertMessage(prepared.sessionId(), AiMessageRoleEnum.USER, prepared.message());

        // ⑤ 检索资料（RAG）：位置刻意放在「配额预检与会话解析之后、模型调用之前」——
        // 检索失败不扣额度（扣额度发生在 persistAnswer），也不影响用户消息已落库
        KnowledgeDocService.RetrievalResult retrieval = retrieveSafely(prepared.message());

        // ⑥ 调 AI：注意这里没有 @Transactional，HTTP 请求不在数据库事务内
        String answer = aiManager.chat(prepared.history(), buildUserMessage(prepared.message(), retrieval));

        // ⑦ 成功：消息与会话活跃时间必须同生共死，配额计数则刻意留到事务提交之后
        persistAnswer(prepared, answer);

        AiChatResponseVO responseVO = new AiChatResponseVO();
        // 每次回答都回传会话 ID：前端首次提问后存下它，即可无感续聊
        responseVO.setSessionId(prepared.sessionId());
        responseVO.setAnswer(answer);
        responseVO.setSources(retrieval.sources());
        return responseVO;
    }

    /**
     * SSE 流式提问：把模型生成的每个片段即时推给前端
     *
     * SSE 天生不适合套统一响应体：套上之后前端必须等整个 JSON 结束才能渲染，流式就没意义了。
     * 这是全项目唯一不返回统一响应体的接口，代价是所有失败也走事件流——参数错误、会话不存在、
     * 配额超限、AI 失败一律变成 error 事件，HTTP 状态码恒为 200。
     *
     * 坑：登录身份必须在请求线程取。Sa-Token 的登录态与 {@code RequestContextHolder} 都在
     * ThreadLocal 里，虚拟线程读不到，表现为 {@code StpUtil.isLogin()} 直接抛异常。
     *
     * @param request 提问请求：message 必填；sessionId 可空表示新建会话
     * @return SSE 发射器。方法立即返回让 Tomcat 释放请求线程，推流在虚拟线程中继续
     * @throws BusinessException 本方法自身不抛业务异常，鉴权、参数、配额、AI 失败
     * 都发生在推流阶段，一律转成 error 事件
     */
    @Override
    public SseEmitter chatStream(AiChatRequest request) {
        // 超时要给足「模型超时 + 余量」：Spring 的 SseEmitter 默认 30s，
        // 比模型的 60s 读超时还短，会让长回答在服务端被提前掐断（表现为流突然中断）
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

        StreamState state = new StreamState();
        // 客户端断开 / 超时 / 写入报错：标记中止。
        // 标记之后：不再推送 delta，不再落 assistant 消息、不再计数
        // （语义与「失败」一致：用户没拿到回答，就不该扣额度、也不该留下半截回答）
        emitter.onCompletion(state::markAborted);
        emitter.onTimeout(state::markAborted);
        emitter.onError(throwable -> state.markAborted());

        // 登录身份必须在这里（请求线程）取好再传进去。
        // 原因：Sa-Token 的登录态与 Spring 的 RequestContextHolder 都存在 ThreadLocal 里，
        // 虚拟线程拿不到（表现为 StpUtil.isLogin() 直接抛异常，
        // 于是流里只剩一个 error 事件、连 meta 都发不出来）。
        // 这一行是本类唯一依赖「请求线程」的地方，改动时务必保留。
        Long userId = currentUserIdOrNull();

        streamExecutor.execute(() -> streamChat(request, userId, emitter, state));
        return emitter;
    }

    /**
     * 会话历史（时间正序，最多最近若干条）
     *
     * 只取最近 50 条：会话会随轮次无限增长，全量返回会让前端一打开历史页就拉回整个会话。
     * 返回时翻正为时间正序，对话界面自上而下就是「旧 → 新」。归属校验与提问完全一致，
     * 否则这个接口会变成「探测某会话是否存在」的入口。
     *
     * 坑：排序键必须用雪花 ID 而不是 created_at，秒级精度会让同秒的两条消息偶发颠倒；
     * 且必须 desc + limit，正序取 50 条拿到的是最早的 50 条。
     *
     * @param sessionId 会话 ID（必填）；为空或非法时得到 A0401
     * @return 消息列表（旧 → 新）；没有消息时返回空列表，不会是 null
     * @throws BusinessException 会话 id 缺失或非法（A0401）、未登录（A0201）、
     * 会话不存在或无权访问（A0402）
     */
    @Override
    public List<AiMessageVO> listHistory(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");
        // 历史接口的归属校验与提问完全一致：否则它会成为「探测某会话是否存在」的入口
        long userId = requireLoginUserId();
        assertSessionAccessible(parsedSessionId, userId);
        return recentMessages(parsedSessionId, AiChatConstant.HISTORY_MESSAGE_LIMIT);
    }

    /**
     * 我的会话列表（按最近活跃排序）
     *
     * 用户维度必须由服务端从登录态取，请求里根本没有 userId 参数——一旦允许前端传，
     * 「看到别人的会话列表」就成了一个功能。按会话表自己的 last_active_at 排序而不是
     * join 消息表求 MAX：冗余列换掉一次 join，代价只是每次提问顺手刷新它（与写消息同一事务）。
     * messageCount 由一次批量统计得出，循环里逐个 count 就是标准 N+1。
     *
     * 坑：次级排序键必须是 id。updated_at 是秒级精度，同一秒活跃过的会话会并列，
     * 没有稳定键时翻页会出现「同一条会话在两页里都出现，或者两页都没有」。
     *
     * @param request 分页参数（current / pageSize），允许为 null，此时按第一页 10 条处理
     * @return 会话分页结果；没有数据时 records 为空列表、total 为 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）
     */
    @Override
    public Page<AiSessionVO> listSessions(AiSessionQueryRequest request) {
        Long userId = currentUserIdOrNull();
        // 会话列表必须登录：它天然是「我的会话」，游客没有这个维度。
        // 控制器上虽然也有登录校验注解，但那是入口处的第一道；服务层自己再判一次，
        // 是为了让「被非 Web 入口直接调用」时同样安全
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
        // 次级排序键必须是 id：updated_at 是秒级精度，同一秒活跃过的会话会并列，
        // 没有稳定键时翻页会出现「同一条会话在两页里都出现 / 都不出现」
        wrapper.orderByDesc(StudioAiSession::getUpdatedAt).orderByDesc(StudioAiSession::getId);

        // searchCount 保持默认 true —— 分页组件需要 total
        Page<StudioAiSession> sessionPage = studioAiSessionMapper.selectPage(new Page<>(current, pageSize), wrapper);

        // 新建一个只保留 total / current / size 的空分页再整体替换记录：
        // 直接 new Page 会丢掉 total，前端的分页组件就翻不了页
        Page<AiSessionVO> voPage = new Page<>(sessionPage.getCurrent(), sessionPage.getSize(),
                sessionPage.getTotal());
        voPage.setRecords(toSessionVOs(sessionPage.getRecords()));
        return voPage;
    }

    /**
     * 删除会话（逻辑删除父子两张表）
     *
     * 对话是用户资产，误删需要可追溯，因此父子两张表都写 deleted_at 而不是物理删除。
     * 两张表必须在同一事务内，且先删子表（消息）再删父表（会话）：反序一旦子表删除失败，
     * 就留下「会话没了、消息还在」的孤儿数据。
     *
     * 坑：不要用「先查消息条数再决定删不删」。删除语义是把会话和它的全部消息一起标记，
     * 多一次 count 只会更慢，还引入「查询与删除之间消息又变多」的竞态。
     *
     * @param sessionId 会话 ID（必填）；为空或非法时得到 A0401
     * @return 恒为 true；删除不成功一律抛异常，调用方不必判返回值
     * @throws BusinessException 会话 id 缺失或非法（A0401）、未登录（A0201）、
     * 会话不存在或无权访问（A0402）、影响行数不为 1（B0002）
     */
    @Override
    public boolean deleteSession(String sessionId) {
        long parsedSessionId = parseSessionId(sessionId, "会话 id 不能为空");

        // 归属校验与提问、历史完全一致：不存在与无权统一 A0402，不暴露存在性。
        // 这一步顺带实现了「重复删除 → A0402」：第一次删除后会话已被逻辑删除，
        // selectById 带 deleted_at = 0 条件查不到 → 与「不存在」走同一条路径
        long userId = requireLoginUserId();
        assertSessionAccessible(parsedSessionId, userId);

        // 双逻辑删：父表（会话）与子表（消息）各自 UPDATE deleted_at，必须在同一事务内。
        // 对话属于「用户资产」，误删需要可追溯，所以两张表都用逻辑删除；
        // 而点赞、成员关联这类「关系解除即无业务意义」的表用的是物理删除
        transactionTemplate.executeWithoutResult(status -> {
            // 先子后父：与「先清关联、再删主表」的既有约定一致。
            // 若反序，父表删成功而子表删除失败时，会留下「会话已删、消息却还在」的孤儿数据
            studioAiMessageMapper.delete(new LambdaQueryWrapper<StudioAiMessage>()
                    .eq(StudioAiMessage::getSessionId, parsedSessionId));
            // 按 ID 删会话：影响行数不为 1 说明它在这一瞬间被并发删掉了，
            // 属于业务上的「操作失败」（B0002），而不是系统异常
            ThrowUtils.throwIf(studioAiSessionMapper.deleteById(parsedSessionId) != 1,
                    ErrorCode.OPERATION_ERROR, "删除会话失败");
        });
        return true;
    }

    // ==================== 同步与流式共用的「准备」与「落库」 ====================

    /**
     * 提问前的公共准备：参数校验 → 会话定位/新建 → 配额预检 → 上下文窗口
     *
     * 同步与流式两个入口必须「鉴权与归属规则完全一致」，唯一可靠的做法是走同一份代码；
     * 复制一份早晚会漂移，改了一处忘了另一处就是越权漏洞。上下文窗口必须在用户消息落库
     * 之前取好，否则本次提问会作为历史被送进模型两遍。
     *
     * @param request 提问请求，可为 null（会被兜成 A0401，而不是空指针）
     * @param userId 当前登录用户 ID，由调用方在请求线程上取好传进来；为 null 表示游客，
     *               本方法直接拒绝（A0201）。不能在本方法内部取：流式路径跑在虚拟线程上，
     *               Sa-Token 的登录态存在 ThreadLocal 中，跨线程取不到
     * @return 准备结果（用户、会话、提问、上下文、是否不限额）
     * @throws BusinessException 未登录（A0201）、参数不合法（A0401）、会话无权访问（A0402）
     */
    private PreparedChat prepareChat(AiChatRequest request, Long userId) {
        // ⓪ AI 服务不向游客开放：必须登录。同步与流式共用本方法，门槛只此一处
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "请先登录后再使用 AI 助手");

        // 请求体整体为 null 时 @Valid 不会触发，兜一层避免后面 getMessage() 抛 NPE 变成 500
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 后再判空与计长：避免「   」这种纯空格绕过必填校验
        String message = StrUtil.trim(request.getMessage());
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提问内容不能为空");
        // 长度校验与 DTO 上的 @Size 双保险：Service 也可能被非 Web 入口直接调用，
        // 那时注解校验根本不执行
        ThrowUtils.throwIf(message.length() > AiChatConstant.MESSAGE_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "提问内容不能超过 " + AiChatConstant.MESSAGE_MAX_LENGTH + " 字");

        // ① 会话：sessionId 为空则新建（标题取首问前 30 字）；非空则校验存在性与归属
        long sessionId = resolveSession(request.getSessionId(), userId, message).getId();

        // ② 配额预检：普通用户受次数上限约束；成员 / 管理员不限额（unlimited = true）
        boolean unlimited = assertQuotaAvailable(userId);

        // ③ 上下文窗口：取最近 10 条（内部已翻正为时间正序），交给 Manager 拼 prompt。
        //    必须在用户消息落库之前执行，否则本次提问会作为「最近一条历史」被送进去两遍
        List<AiMessageVO> history = recentMessages(sessionId, AiChatConstant.CONTEXT_MESSAGE_COUNT);

        return new PreparedChat(userId, sessionId, message, history, unlimited);
    }

    /**
     * 回答落库：assistant 消息 + 会话活跃时间进事务，配额计数留在事务之后
     *
     * 同步与流式共用本方法，这样「流式落库的 SQL 与同步接口对齐」是结构上成立的。
     * 两件事进事务：消息与会话活跃时间都写数据库，必须同生共死。配额计数刻意排除在外：
     * 它走 Redis INCR，数据库事务回滚撤不回已发出的 INCR，放进去就会出现
     * 「回答没存成功、额度却被扣了」。
     *
     * 坑：不限额用户既不预检也不计数，两个判断要成对出现——只跳过预检不跳过计数，
     * 会让不限额角色的计数无限增长。
     *
     * @param prepared 提问准备结果，其中的 userId 与 sessionId 必须有效
     * @param answer 回答正文，非空（模型返回空内容时上游会抛 C0200）
     * @return 落库后的 assistant 消息 ID（流式接口要把它放进 done 事件）
     */
    private long persistAnswer(PreparedChat prepared, String answer) {
        // 事务 lambda 不能直接写局部变量，用一个一元素数组当容器（比 AtomicLong 轻）
        long[] messageIdHolder = new long[1];
        transactionTemplate.executeWithoutResult(status -> {
            messageIdHolder[0] = insertMessage(prepared.sessionId(), AiMessageRoleEnum.ASSISTANT, answer);
            touchSession(prepared.sessionId());
            // 配额计数刻意不在这里：
            // 计数走 Redis INCR，Redis 是外部系统——写它不属于数据库事务。
            // 若放在事务内，一旦事务回滚，Redis 里的 +1 却撤不回来，就变成了「没回答成功也扣了额度」
        });

        // 事务提交之后再计数：与「HTTP 调用不进事务」是同一条纪律——
        // 外部系统的写必须留在数据库事务边界之外。
        // 成员 / 管理员不限额（unlimited），既不预检也不计数
        if (prepared.userId() != null && !prepared.unlimited()) {
            aiQueryCountManager.increment(prepared.userId());
        }
        return messageIdHolder[0];
    }

    // ==================== RAG 检索 ====================

    /**
     * 检索资料，失败即降级为「无资料」
     *
     * RAG 是增强而不是依赖：向量服务不可用、或重启后内存向量库还没重建，都不该让用户
     * 问不出话。所以这里吞掉所有异常、返回空结果、只记一条 warn，不新增错误码，
     * 用户侧表现是「这次回答没有溯源」而不是「AI 用不了」。
     *
     * 坑：这里用只读的 {@code isOpen()} 而不是 {@code allowRequest()}。后者会消耗半开状态下
     * 唯一一次试探机会，而本方法无论成败都不向熔断器回报结果——用了它，熔断器会认为
     * 「试探的请求消失了」，永远无法自愈。
     *
     * @param question 用户提问（已 trim 且非空，由 {@code prepareChat} 保证）
     * @return 检索结果；熔断或异常时返回空结果（sources 为空列表，不会为 null）
     */
    private KnowledgeDocService.RetrievalResult retrieveSafely(String question) {
        if (circuitBreaker.isOpen()) {
            log.warn("AI 服务熔断中，跳过知识库检索（embedding 调用必然失败，不再白等外部往返）");
            return KnowledgeDocService.RetrievalResult.empty();
        }
        try {
            return knowledgeDocService.retrieve(question);
        } catch (Exception e) {
            // 捕获 Exception 而不是只捕业务异常：检索链路会碰外部服务与内存向量库，
            // 任何运行时异常都不该让用户问不出话
            log.warn("知识库检索失败，本次无资料继续回答（不阻断提问、不新增错误码）", e);
            return KnowledgeDocService.RetrievalResult.empty();
        }
    }

    /**
     * 把检索到的资料拼进本次提问
     *
     * 资料放 user 消息而不是 system 消息：system 是「你是谁、怎么答」的稳定行为约束，
     * 每次提问内容都不同，塞进去会让模型的行为基线跟着漂；贴着问题放，模型更容易
     * 把它当成当前问题的上下文。历史里存的仍是用户原话（落库只写原始提问），
     * 拼了资料的版本只用于本次调用，不会污染上下文窗口。
     *
     * 坑：分隔符不能省。资料与问题没有明确分界时，模型可能把问题当成资料的一部分。
     *
     * @param message 用户原始提问（已 trim 且非空）
     * @param retrieval 检索结果，不能为 null（降级路径也返回空结果对象）
     * @return 送进模型的 user 消息；无命中时原样返回入参
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
     * 事件序列固定为 meta → sources → delta* → done / error，顺序本身就是契约。
     * 失败也走事件流而不是抛异常：方法已经返回了发射器，此时抛异常调用方也接不到。
     *
     * 坑：done 必须在落库之后发，且要在事务提交之后。若先发 done 再落库，前端会立刻显示
     * 「已完成」而库里可能什么都没写，用户刷新后发现回答不见了。
     *
     * @param request 提问请求，可为 null（会在 {@code prepareChat} 里得到 A0401，以 error 事件返回）
     * @param userId 当前登录用户 ID，由请求线程取好传进来（虚拟线程读不到 ThreadLocal 里的登录态）
     * @param emitter SSE 发射器，不能为 null
     * @param state 中止标记，客户端断开时会被置位；每个推流点都会检查它
     */
    private void streamChat(AiChatRequest request, Long userId,
                            SseEmitter emitter, StreamState state) {
        try {
            PreparedChat prepared = prepareChat(request, userId);

            // ② meta：先告知会话 ID。新建会话时前端只能从这里拿到它
            sendEvent(emitter, EVENT_META,
                    Map.of("sessionId", String.valueOf(prepared.sessionId())), state);

            // ② 用户消息先落库：失败或中断时它必须留下（与同步接口同一约定）
            insertMessage(prepared.sessionId(), AiMessageRoleEnum.USER, prepared.message());

            // ③ 检索资料（RAG）：与同步接口同一位置、同一降级策略
            KnowledgeDocService.RetrievalResult retrieval = retrieveSafely(prepared.message());
            // ④ sources 恒发（无命中也发空数组）：前端不必靠「等一会儿没收到」来判断有没有资料
            sendEvent(emitter, EVENT_SOURCES, Map.of("sources", retrieval.sources()), state);

            // ⑤ 流式调用：每段增量直接推给前端。
            //    chatStream 是阻塞式回调，本线程会一直占用到流结束——所以它跑在虚拟线程上
            String answer = aiManager.chatStream(prepared.history(),
                    buildUserMessage(prepared.message(), retrieval),
                    delta -> sendEvent(emitter, EVENT_DELTA, Map.of("delta", delta), state));

            // ⑥ 客户端已断开：不落库、不计数、也不发 done（连接都没了）。
            //    语义与「失败」一致：用户没拿到回答，就不该扣额度
            if (state.isAborted()) {
                log.warn("SSE 已中断，跳过 assistant 消息与配额计数 | sessionId={}", prepared.sessionId());
                return;
            }

            // ⑦ 正常结束：落库与计数（与同步接口共用 persistAnswer），完成后才发 done。
            //    done 里带 assistant 消息 ID：前端可以用它做「重新生成」「引用定位」之类的后续操作
            long messageId = persistAnswer(prepared, answer);
            sendEvent(emitter, EVENT_DONE, Map.of("messageId", String.valueOf(messageId)), state);
            emitter.complete();
        } catch (BusinessException e) {
            // 参数错误 / 会话不存在或无权 / 配额超限 / AI 失败：统一用 error 事件表达。
            // 本接口的 HTTP 状态码恒为 200 —— SSE 契约的必然结果：响应头早已发出，
            // 此时再改状态码已经来不及了
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

    /**
     * 发送一个 SSE 事件
     *
     * 负载必须序列化成 JSON 字符串。SSE 的 data 是纯文本，而 delta 里可能含换行、引号与中文，
     * 手拼会把换行当成新的事件字段，直接破坏事件帧。交给 {@code JsonMapper} 序列化后
     * 换行被转义成 \n，整个负载始终是单行；复用容器里被定制过的那个实例，全局配置才能同样生效。
     *
     * 坑：标记中止后不要试图「再发一个 error 事件告诉前端出错了」——连接已不可写，
     * 这次发送同样会失败。
     *
     * @param emitter SSE 发射器，不能为 null
     * @param eventName 事件名（meta / sources / delta / done / error），改名等于契约变更
     * @param payload 负载，会被序列化成 JSON；Map 的 value 不能含循环引用
     * @param state 中止标记，已中止时直接返回、不发任何数据
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
            // 让后续停止写事件、也不落库计数（等价于「失败」语义）
            state.markAborted();
            log.warn("SSE 事件发送失败（客户端可能已断开）| event={}", eventName);
        }
    }

    // ==================== 会话定位与归属 ====================

    /**
     * 定位会话：sessionId 为空则新建，否则校验存在性与归属
     *
     * 新建与续聊写在一起，调用方不必关心「这次是不是第一句」。匿名会话（user_id 为空）
     * 刻意允许创建：官网问答对游客开放，游客没有账号可绑。安全性由 ID 本身承担——
     * 雪花 ID 是 19 位长整型，无法逐个枚举，「知道这个 ID」等价于持有访问凭据。
     *
     * 坑：新建会话是写库，且发生在配额预检之前，所以超限用户的一次提问也可能建出空会话。
     *
     * @param sessionIdText 请求里的会话 ID 文本，可为 null 或空（表示新建）
     * @param userId 当前登录用户 ID，可为 null（表示游客，建出匿名会话）
     * @param message 本次提问（已 trim 且非空），新建会话时用于生成标题
     * @return 会话实体；新建时它的 id 已由 MyBatis-Plus 回填
     * @throws BusinessException 会话 id 非法（A0401）、无权访问（A0402）、新建失败（B0002）
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
     * 校验会话可访问（存在 + 归属），供历史与删除接口使用
     *
     * 历史、删除都必须与提问用同一套规则，任何一处写成「只查存在」都会变成越权入口。
     * 两次查询（这里一次、调用方随后再取数据）是刻意的：先确认权限再取数据，
     * 比「先取数据再判权限」少泄露一次数据。
     *
     * 坑：本方法只校验「能不能访问」而不返回实体。若为了省一次查询改成返回实体，
     * 调用方就可能顺手拿它在归属校验之前判断业务状态。
     *
     * @param sessionId 会话 ID，调用方已保证为正数（{@code parseSessionId} 校验过）
     * @param userId 当前登录用户 ID，可为 null（游客只可能访问匿名会话）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话不存在、已删除，
     * 或当前用户无权访问（A0402，两种情况同一句提示）
     */
    private void assertSessionAccessible(long sessionId, Long userId) {
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
    }

    /**
     * 会话归属校验
     *
     * 匿名会话（user_id 为 null）任何人都可凭 ID 访问——它是游客自己创建的会话，
     * 安全性由 19 位雪花 ID 的不可枚举性承担。绑定用户的会话只有本人可访问，
     * 越权与不存在必须返回完全相同的错误码与提示，否则接口就成了低成本的探测工具。
     *
     * 坑：比较必须用 {@code Objects.equals}，不能直接 {@code session.getUserId().equals(userId)}
     * ——后者依赖「左边非空」这个前提，一旦 user_id 为 null 就变成空指针。
     *
     * @param session 会话实体，不能为 null（调用方已确认查询有结果）
     * @param userId 当前登录用户 ID，可为 null（游客）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话属于他人（A0402）
     */
    private void assertSessionOwner(StudioAiSession session, Long userId) {
        if (session.getUserId() == null) {
            // 匿名会话：19 位雪花 ID 无法被枚举，「知道 ID」即等价于持有凭据
            return;
        }
        // 越权与不存在返回完全相同的 A0402 与提示：
        // 否则攻击者可以用「A0402 提示是否有差异」来判断会话是否存在
        ThrowUtils.throwIf(!Objects.equals(session.getUserId(), userId),
                ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
    }

    /**
     * 解析会话 ID 文本
     *
     * 会话 ID 在 HTTP 层是字符串，内部一律用 long，转换收在一个方法里，否则每个入口
     * 都要重复「判空 + 转数字 + 判正数」三步。空值提示做成参数：提问时没带 ID 是正常的
     * （表示新建会话），历史时没带则是缺参数。
     *
     * 坑：用 {@code Convert} 而不是 {@code Long.valueOf}。后者遇到 "abc" 会抛
     * {@code NumberFormatException} 被兜成 500，而这明明是用户传错了参数（应为 A0401）。
     *
     * @param sessionIdText 会话 ID 文本，可为 null
     * @param blankMessage 文本为空时的提示文案，不能为 null
     * @return 解析后的会话 ID，保证大于 0
     * @throws BusinessException 文本为空（用传入的提示）、非数字或非正数（A0401）
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
     * 用首问开头而不是「新会话」这类固定文案：用户在一堆会话里是靠内容找回忆的。
     * 30 是产品口径（{@code AiChatConstant.SESSION_TITLE_MAX_LENGTH}），列宽 64 留了一倍余量。
     *
     * 坑：本方法假设入参已 trim 且非空（由 {@code prepareChat} 保证）。
     * 传入纯空白不会报错，但标题会变成一串空格，列表里显示为一个空行。
     *
     * @param message 首次提问内容，调用方必须已 trim 并确认非空
     * @return 会话标题，长度不超过 30
     */
    private String titleOf(String message) {
        return message.length() <= AiChatConstant.SESSION_TITLE_MAX_LENGTH
                ? message
                : message.substring(0, AiChatConstant.SESSION_TITLE_MAX_LENGTH);
    }

    /**
     * 配额预检
     *
     * 成员与管理员不限额（返回 true），也不计数；普通用户受 {@code studio.ai.query-limit}
     * 约束，超限抛 A0501。并发下两个请求可能同时通过预检、略微超出上限，这是刻意接受的：
     * 配额是防护性上限而不是计费依据。角色取自 {@code sys_user.user_role} 而不是
     * {@code StpUtil.hasRole}：流式提问的预检跑在虚拟线程上，Sa-Token 的登录态取不到。
     *
     * 坑：必须用「数据库基准 + Redis 未回刷增量」的合并值，只看数据库的话，
     * 回刷前的那几次提问都不算数，配额就成了摆设。
     *
     * @param userId 登录用户 ID，调用方已保证非 null（{@code prepareChat} 已挡掉游客）
     * @return true 表示不限额（成员 / 管理员）；false 表示按次数上限计
     * @throws BusinessException 账号不存在（A0201）、提问次数已达上限（A0501）
     */
    private boolean assertQuotaAvailable(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        // 查不到按「未登录」处理而不是「系统错误」：登录态指向一个已被删除的账号时，
        // 对用户而言就是「你得重新登录」
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "登录用户不存在");

        // 成员 / 管理员：无限使用，且不计数
        if (isUnlimitedRole(user.getUserRole())) {
            return true;
        }

        // 配置为 null 时按不限制处理：一个漏写的配置项不该把全体普通用户挡在门外
        int limit = aiProperties.getQueryLimit() == null ? Integer.MAX_VALUE : aiProperties.getQueryLimit();
        // 必须是「DB 基准 + Redis 未落库增量」的合并值：
        // 只看 DB 的话，回刷前的那些提问都不算数（表现为「问了好几次还没到上限」），
        // 配额就成了摆设。合并读取也让预检不依赖回刷是否发生
        int used = aiQueryCountManager.merge(userId, user.getAiQueryCount());
        ThrowUtils.throwIf(used >= limit, ErrorCode.TOO_MANY_REQUESTS_ERROR,
                "登录用户的 AI 提问次数已用完（共 " + limit + " 次），成员与管理员可不限次使用");
        return false;
    }

    /**
     * 是否为「不限额」角色（成员 / 管理员）
     *
     * 显式列出两个不限额角色，而不是「不等于 user 就不限额」：后者在将来新增角色
     * （例如只读访客）时会默认放行，而新角色该不该受配额约束是需要产品决策的。
     *
     * 坑：比较必须用常量（{@code UserRoleConstant}）而不是手写字面量，否则
     * 「改了一处忘改另一处」会让成员突然开始受配额限制且不报错。入参允许为 null：
     * equals 的调用方是常量，null 只会得到 false。
     *
     * @param role 用户角色原始值（取自 {@code sys_user.user_role}），可为 null
     * @return true 表示不受提问次数限制；false 表示按配置的次数上限计
     */
    private static boolean isUnlimitedRole(String role) {
        return UserRoleConstant.MEMBER.equals(role) || UserRoleConstant.ADMIN.equals(role);
    }

    // 配额 +1 统一由 AiQueryCountManager 负责（Redis INCR，事务外执行）。
    // 本类不直接 UPDATE sys_user：每次提问都写一次热点行会在并发下排队，
    // 而且那是一次数据库写，会落进事务边界里，回滚时撤不回已经扣掉的额度

    // ==================== 消息读写 ====================

    /**
     * 取会话的最近 N 条消息（返回时已翻正为时间正序）
     *
     * 查询必须「倒序 + 限量」：正序取前 N 条拿到的是会话最早的消息，长会话里看不到最新的几句。
     * 返回必须正序，对话界面自上而下就是「旧 → 新」，翻正只写一处才不会漂移。
     * searchCount 关掉是刻意的：这里只要数据不要总数，开着会白多一次 COUNT。
     *
     * 坑：排序键必须用雪花 ID 而不是 created_at，秒级精度会让同秒的 user / assistant 颠倒；
     * 另外必须 new ArrayList 再 reverse，直接对查询结果 reverse 可能抛
     * {@code UnsupportedOperationException}。
     *
     * @param sessionId 会话 ID，调用方已确认当前用户有权访问
     * @param limit 取多少条，必须大于 0；实际返回可能少于它
     * @return 消息 VO 列表，旧 → 新；没有消息时返回空列表，不会返回 null
     */
    private List<AiMessageVO> recentMessages(long sessionId, int limit) {
        LambdaQueryWrapper<StudioAiMessage> wrapper = new LambdaQueryWrapper<>();
        // 排序键用 id 而不是 created_at：created_at 秒级精度，
        // 同秒的 user / assistant 会并列，导致对话顺序偶发颠倒。
        // 雪花 id 单调递增，等价于时间序且唯一
        wrapper.eq(StudioAiMessage::getSessionId, sessionId)
                .orderByDesc(StudioAiMessage::getId);

        // searchCount=false：这里只要数据不要总数，省掉一次 COUNT 查询
        Page<StudioAiMessage> page = studioAiMessageMapper.selectPage(new Page<>(1, limit, false), wrapper);

        // 查询是「倒序取最近 N 条」，返回前必须翻正：对话界面是旧 → 新自上而下。
        // 先复制成 ArrayList 再 reverse：查询结果不保证是可变的
        List<StudioAiMessage> records = new ArrayList<>(page.getRecords());
        Collections.reverse(records);
        return records.stream().map(this::toMessageVO).toList();
    }

    /**
     * 落一条消息
     *
     * role 存枚举的字符串值而不是序号：序号一旦调整枚举顺序就会把历史数据全部解释错，
     * 字符串值在数据库里是自解释的。插入后必须校验影响行数，为 0 却继续往下走，
     * 会得到一个「消息 ID 是 0」的假结果。
     *
     * 坑：ID 是插入之后才有的，MyBatis-Plus 会把雪花 ID 回填到实体上。
     *
     * @param sessionId 会话 ID，调用方已确认存在且有权访问
     * @param role 消息角色（user / assistant），不能为 null
     * @param content 消息正文，调用方保证非空
     * @return 新消息 ID（流式接口要把它放进 done 事件）
     * @throws BusinessException 插入影响行数不为 1（B0002）
     */
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

    /**
     * 刷新会话的 updated_at（用于「最近活跃会话」排序）
     *
     * 会话表自己维护这个冗余列，列表排序因此是一次单表查询，不必 join 消息表求 MAX；
     * 刷新成本很低，每次提问本来就要写消息。
     *
     * 坑：不要改成「先查出来、改 updatedAt、再 updateById」。那样多一次查询，
     * 还会把整个实体写回去，并发下可能用旧值覆盖别人刚改的 title。
     * 这里刻意用「只带 id 的空实体」，让 UPDATE 只触碰审计字段。
     *
     * @param sessionId 会话 ID，调用方已确认存在
     */
    private void touchSession(long sessionId) {
        StudioAiSession touch = new StudioAiSession();
        touch.setId(sessionId);
        studioAiSessionMapper.updateById(touch);
    }

    /**
     * 实体转消息 VO
     *
     * 实体与 VO 的字段一一对应，可以整体拷贝。VO 里没有 sessionId 与审计字段，
     * 「不泄露内部标识」因此是结构上成立的，而不是靠记得别赋值。
     *
     * 坑：{@code copyProperties} 只拷两边都存在且类型兼容的属性，名字不同会静默跳过。
     * 给 VO 加字段时若字段名与实体不一致（实体叫 id、VO 叫 messageId），必须手动赋值。
     *
     * @param message 消息实体，不能为 null
     * @return 消息 VO，不含会话 ID 与审计字段
     */
    private AiMessageVO toMessageVO(StudioAiMessage message) {
        AiMessageVO messageVO = new AiMessageVO();
        BeanUtils.copyProperties(message, messageVO);
        return messageVO;
    }

    // ==================== 会话列表 ====================

    /**
     * 会话实体列表转 VO 列表，并批量补齐 messageCount
     *
     * 消息条数不在会话表上，循环里逐个 count 就是标准 N+1；这里先收集本页全部会话 ID，
     * 用一条 IN + GROUP BY 一次算完。空页必须提前返回：拿空集合拼 IN () 是 SQL 语法错误。
     *
     * 坑：messageCount 必须补 0 而不是留 null。前端若用它做判断（例如「有消息才显示删除按钮」），
     * null 会让它走进意料之外的分支。
     *
     * @param sessions 本页会话实体，可为 null 或空
     * @return 会话 VO 列表；入参为空时返回空列表，不会返回 null
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
     * 批量统计多个会话的消息条数（一次查询解决整页）
     *
     * 绝不能写成 {@code for (会话 s : 本页) count(s.id)}，那是标准 N+1；改成一条
     * IN + GROUP BY 后查询次数与页大小无关。deleted_at = 0 由 {@code @TableLogic} 自动追加，
     * 已删除的消息不会计入，与列表接口的可见性一致。
     *
     * 坑：绝不硬转 {@code (Long)}。COUNT(*) 在不同驱动 / 版本下可能是 Long 或 BigInteger，
     * 硬转属于「本机测试通过、换环境 ClassCastException」的经典坑。
     *
     * @param sessionIds 本页会话 ID 列表，调用方保证非空
     * @return 会话 ID → 消息条数；没有消息的会话不会出现在 Map 里，由调用方补 0
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
                // 分组列为 null 说明数据已脏（该列在业务上必填），
                // 放进去只会得到一个永远匹配不上的 key
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
     * AI 问答允许游客使用（匿名会话），「没有用户」是正常状态，不能用抛异常表达。
     * 返回 null 让调用方决定：提问路径把 null 当游客，列表与历史路径用
     * {@code requireLoginUserId} 把 null 转成 A0201。
     *
     * 坑：本方法依赖 ThreadLocal 里的登录态，必须在请求线程调用；在虚拟线程里调会读到
     * 空登录态，把游客与登录用户混为一谈。
     *
     * @return 用户 ID；未登录（游客）或 loginId 非数字时返回 null，调用方必须判空
     */
    private Long currentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        // 本项目登录统一用 Long 型用户 id；万一将来出现非数字 loginId，
        // 这里按游客处理而不是抛异常——提问不应该因为一个 id 形态问题变成 500
        return Convert.toLong(StpUtil.getLoginId(), null);
    }

    /**
     * 取当前登录用户 ID，未登录直接拒绝（A0201）
     *
     * 会话列表、历史、删除会话都需要一个明确的「我」，统一把「取 id + 判空 + 抛 A0201」
     * 收在一处，避免每个入口各写一遍（漏一处就是越权）。返回值是基本类型 long，
     * 调用方不必判空——这正是它和 {@code currentUserIdOrNull} 分成两个方法的意义。
     *
     * @return 登录用户 ID（保证非 null，已拆箱）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）
     */
    private long requireLoginUserId() {
        Long userId = currentUserIdOrNull();
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "请先登录后再使用 AI 助手");
        return userId;
    }

    // ==================== 内部类型 ====================

    /**
     * 提问准备结果（同步与流式共用）
     *
     * 用 record 而不是 Map 或 Object[]：访问器是编译期检查的，拼错 key 就得到 null 的问题不存在。
     * unlimited 一并带出来，是为了让 {@code persistAnswer} 不必再查一次数据库判断角色。
     *
     * 坑：userId 是包装类型 Long，其余是基本类型。这不是随手写的：userId 需要允许 null
     * 表达「游客」，而 sessionId 与 unlimited 在准备完成后一定有值。
     *
     * @param userId 当前登录用户 ID，游客为 null（匿名会话的 user_id 就存 null）
     * @param sessionId 会话 ID，可能是本次新建的
     * @param message 已 trim 且校验过非空的提问内容
     * @param history 上下文窗口（时间正序，最多 10 条）
     * @param unlimited 是否不限额（成员 / 管理员为 true，落库时不计数）
     */
    private record PreparedChat(Long userId, long sessionId, String message, List<AiMessageVO> history,
                                boolean unlimited) {
    }

    /**
     * 流式过程的中止标记（客户端断开 / 超时 / 写事件失败时置位）
     *
     * SSE 的断开是异步通知的，推流线程可能正跑在回调到来之前的某一步。没有这个标记，
     * 就会出现「用户已经关掉页面，服务端还在把回答写进库、还算进配额」。
     * 用 volatile 而不是 {@code AtomicBoolean}：这里只有单个 boolean 的读写，
     * 没有复合操作，CAS 能力用不上。
     *
     * 坑：标记是单向的，没有「恢复」操作——一次连接断开之后不可能再恢复。
     * 它只是「尽力而为」的会合点，极端情况下仍会写进一条无用记录，这是可接受的。
     */
    private static final class StreamState {

        private volatile boolean aborted;

        /**
         * 置位中止标记
         *
         * 会被三个回调与推流线程自己共同调用，因此必须幂等——重复置位不产生副作用。
         * 做成单向的「只进不退」是刻意的：连接断开后不可能恢复，提供 reset 只会让人误用。
         */
        void markAborted() {
            this.aborted = true;
        }

        /**
         * 是否已中止
         *
         * 收成一个方法，是为了让「哪些地方需要检查」在代码里一眼可数；
         * 各处直接读字段很容易在新增分支时漏掉一处。
         *
         * @return true 表示客户端已断开、超时或写事件失败
         */
        boolean isAborted() {
            return aborted;
        }
    }
}
