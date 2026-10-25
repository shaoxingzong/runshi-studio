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
 * AI 咨询服务实现
 *
 * 本类的四道关键约束（改代码前先读）：
 * - 会话归属：绑定用户的会话仅本人可续；匿名会话凭不可枚举雪花 ID 可续。
 * 不存在与无权统一返回 A0402 同一句提示，不暴露存在性；
 * - 配额自增必须原子：Redis 的 INCR 一条命令完成「读 + 加 + 写」，
 * 绝不「读出来 +1 再写回去」（并发下互相覆盖，计数偏小等于白送额度）；
 * - HTTP 在事务之外：AI 调用可能几十秒，包进事务会长时间占用数据库连接。
 * 失败时保留用户消息，返回 C0200；成功后才用 TransactionTemplate
 * 把「assistant 消息 + 会话 updated_at」包成一步写，配额计数留在事务提交之后；
 * - 排序必须带 id：created_at 只有秒级精度，同一秒的
 * user / assistant 两条消息时间相同，只按时间排序会出现对话记录偶发颠倒。
 *
 * 为什么 TransactionTemplate 是「自己 new 的」：当前 Spring Boot 版本的
 * TransactionAutoConfiguration 只提供 TransactionalOperator（响应式事务），
 * 容器里没有 TransactionTemplate bean（Boot 3 时代有）。直接
 * @Resource TransactionTemplate 会启动失败——这里注入
 * PlatformTransactionManager（DataSource 事务管理器一定存在）再自行构造。
 *
 * 为什么用 TransactionTemplate 而不是 @Transactional：需要包进事务的
 * 只有「调用 AI 成功之后」那一小段。若把整个 chat 方法标上 @Transactional，
 * 那条外部 HTTP 请求就落进了事务里——这正是本类要避免的事。
 *
 * 一句话记住本类的顺序：校验登录 → 配额预检 → 会话解析 → 用户消息落库
 * → 检索资料 → 调模型 → 事务内落回答 → 事务外计数。任何一步的换位都有代价，
 * 各方法注释里会逐个说明为什么不能换。
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
     * 统一用常量：避免「这里写 session_id、那里写 sessionId」这种拼写漂移导致
     * 取值恒为 null、再被兜底成 0——表现为「消息数永远是 0」且不报任何错。
     */
    private static final String SESSION_ID_COLUMN = "session_id";
    private static final String COUNT_ALIAS = "cnt";

    /**
     * SSE 连接超时：模型自身超时 60s + 余量
     *
     * 为什么必须显式设置：Spring 的 SseEmitter 默认只有 30s，比模型的 60s 读超时还短。
     * 用默认值会让长回答在服务端被提前掐断，前端看到的是「流突然没了」，
     * 而日志里没有任何错误——这类故障最难定位，因为它看起来像网络问题。
     * 取 120 秒而不是刚好 60 秒：要留出网络往返、排队与最后几次写事件的时间，
     * 否则会出现「模型答完了、结果在推最后几个 delta 时连接被关掉」。
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    /**
     * SSE 事件名（前端按 name 分派，改名等于契约变更）
     *
     * 序列固定为：meta → sources → delta* → done / error。
     * 这五个名字是前后端之间的契约：后端改了名字，前端的事件监听会静默失效
     * （收到未知事件名时通常什么都不做），表现为「界面一直转圈」而不是报错。
     *
     * sources 恒发：即使没有命中资料也发一个空数组。
     * 前端因此可以无分支地写 on('sources', s => setSources(s))；
     * 若只在命中时发，前端就得靠「等一段时间没收到」来判断没有资料——那是竞态，
     * 慢网络下必然误判。
     */
    private static final String EVENT_META = "meta";
    private static final String EVENT_SOURCES = "sources";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    /**
     * 会话表 Mapper
     *
     * 本类对会话只做四种操作：按主键查、按用户分页查、插入、逻辑删除。
     * 全是单表操作，没有业务规则，因此直接用 Mapper 而不包一层 Service。
     */
    private final StudioAiSessionMapper studioAiSessionMapper;

    /**
     * 消息表 Mapper
     *
     * 消息是本类读写的核心：每次提问要插入 user 与 assistant 两条，
     * 取上下文窗口与历史时要按会话倒序分页，列表页还要按会话批量统计条数。
     * 这些全是单表操作，没有业务规则，因此直接用 Mapper。
     */
    private final StudioAiMessageMapper studioAiMessageMapper;

    /**
     * 用户表 Mapper
     *
     * 只用来读两个字段：角色（判断是否不限额）与 AI 提问次数基准值。
     * 不注入用户 Service 是为了避开依赖方向：用户服务属于账号模块，
     * 而这里只需要一次按主键读取，走 Mapper 更直接，也不会形成模块间循环。
     */
    private final SysUserMapper sysUserMapper;

    /**
     * 大模型调用入口（同步对话 / 流式对话 / 批量向量化）
     *
     * 超时、重试、熔断计数、错误码收敛都在它内部完成，本类只负责「什么时候调、传什么」。
     * 这样划分的好处是：本类里看不到任何 HTTP 细节，读业务顺序时不会被重试逻辑打断。
     */
    private final AiManager aiManager;

    /**
     * 提问配额计数（Redis INCR + 定时回刷）
     *
     * 为什么不用 UPDATE sys_user SET ai_query_count = ai_query_count + 1：
     * 那样每次提问都要写一次热点行，并发提问会在这行上排队；
     * 改成「Redis 内存自增 + 定时批量回刷」后，数据库只承担周期性的合并写。
     * 读取时用「数据库基准 + Redis 增量」的合并值，因此预检不依赖回刷是否已发生。
     */
    private final AiQueryCountManager aiQueryCountManager;

    /**
     * AI 相关配置（提问次数上限、模型参数、检索参数）
     *
     * 上限做成配置而不是常量：它属于运营口径，调整时不该走一次发布。
     */
    private final AiProperties aiProperties;

    /**
     * 知识库检索：提问前先检索资料注入模型（RAG，即「先检索、再生成」：
     * 先从自有知识库找出相关片段，再把片段和问题一起交给大模型）
     *
     * 依赖方向是单向的：KnowledgeDocServiceImpl 只注入 Mapper 与配置，
     * 不依赖本类，因此不构成循环。这条方向必须保持——本类依赖检索，
     * 检索不能反过来依赖问答，否则构造器注入会在启动时直接失败。
     */
    private final KnowledgeDocService knowledgeDocService;

    /**
     * AI 熔断器
     *
     * 只用于「提前绕开注定失败的检索」这类只读判断：检索之前先问一句
     * 「AI 现在是不是整体不可用」，是就干脆不发起这次外部调用。
     * 真正的失败计数在 AiManager 内部完成——那里才是外部调用的发生地，
     * 也只有那里知道「这次调用到底是成功还是失败」。
     */
    private final AiCircuitBreaker circuitBreaker;

    /** SSE 事件负载的 JSON 序列化：复用容器里被 JsonConfig 定制过的那个 JsonMapper */
    private final JsonMapper jsonMapper;

    /**
     * 事务管理器
     *
     * 容器里没有 TransactionTemplate bean（当前 Spring Boot 版本的自动配置
     * 只提供响应式的 TransactionalOperator），所以注入这个一定存在的
     * DataSource 事务管理器，再自己构造模板。
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * 事务模板：由 transactionManager 在依赖注入完成后构造
     *
     * 它不能声明成 final：Lombok 生成的构造器只做「参数 → 字段」的直接赋值，
     * 表达不了「由另一个参数二次构造」；写成 final 会让它被当成构造器参数，
     * 而容器里并不存在 TransactionTemplate 这个 bean，启动即失败。
     * 放到 @PostConstruct 里赋值，此时构造器注入已完成，取值安全。
     */
    private TransactionTemplate transactionTemplate;

    /**
     * SSE 专用线程池：虚拟线程（Java 21）
     *
     * 为什么必须换线程：SSE 要求控制器立刻返回 emitter 让 Tomcat 释放请求线程，
     * 而 LangChain4j 的流式调用是阻塞式回调——调用线程会被占用到流结束（最长 60s）。
     * 留在请求线程上会钉死工作线程；换成平台线程池的话，并发的流会把池吃满并排队；
     * 虚拟线程正好匹配「大量线程都在等 I/O」的场景，代价也最低。
     *
     * 没有额外限流：登录用户受 studio.ai.query-limit 约束；
     * 游客的 IP 级限流由埋点之外的另一套限流组件负责，不在这里重复实现——
     * 限流策略集中在一处，才不会出现「这里放行、那里拦截」的矛盾。
     */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 构造事务模板（容器启动时执行一次）
     *
     * <p>为什么这样设计：容器里没有 TransactionTemplate 这个 bean
     * （当前 Spring Boot 版本的自动配置只提供响应式的 TransactionalOperator），
     * 所以只能自己 new。放在 @PostConstruct 而不是字段初始化，是因为字段初始化时
     * 构造器参数还没赋值给 transactionManager，那时 new 出来的是个空壳。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用已注入的事务管理器构造模板；</li>
     *     <li>赋值给字段，此后所有需要事务的地方共用这一个实例。</li>
     * </ol>
     *
     * <p>容易踩的坑：TransactionTemplate 是线程安全的（它不持有事务状态，
     * 状态存在 ThreadLocal 里），所以可以做成单例共用，不必每次调用都 new 一个。
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
     * <p>为什么这样设计：虚拟线程池虽然轻量，但它仍然是应用持有的资源，
     * 不显式关闭会让容器在关闭阶段等待这些线程。用 shutdownNow 而不是优雅关闭：
     * 未完成的流此刻多半卡在模型超时上，等它们没有意义（应用都要停了），
     * 直接中断更快。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>调用 shutdownNow 中断所有在跑的流式任务，并拒绝新任务。</li>
     * </ol>
     *
     * <p>容易踩的坑：中断会让正在写 SSE 的线程抛异常，那些异常由 sendEvent 里的
     * catch 兜住并标记中止——所以关闭期间用户看到的是流中断，而不是服务端报错。
     * 这是可接受的：应用正在停止，本来就不该指望流能完整结束。
     */
    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    /**
     * 同步问答（一次返回完整回答）
     *
     * <p>为什么这样设计：本方法最容易写错的地方是「事务边界」。三条取舍如下。
     * 第一，整个方法不能标 @Transactional——模型生成一段回答可能要几十秒，
     * 事务一旦把它包住，数据库连接就被占着几十秒，并发稍高就会把连接池耗尽。
     * 所以只有「回答落库」这一小段用 TransactionTemplate 手动开事务。
     * 第二，用户消息必须在调用模型之前落库：反过来一旦模型失败，用户的提问就丢了——
     * 用户明明问了，历史里却什么都没有，而前端往往已经把它渲染在界面上了。
     * 第三，配额预检排在检索之前：检索要先把问题送去 Embedding
     * （把文本变成一串数字向量，供相似度计算使用，是按文本量计费的外部调用），
     * 超限的请求不该产生这笔开销。
     *
     * <p>执行步骤（与代码里的 ①④⑤⑥⑦ 标注一一对应，其余为准备与返回）：
     * <ol>
     *     <li>在请求线程上取登录身份——Sa-Token 的登录态存在 ThreadLocal
     *     （线程本地变量，每个线程一份副本）里，后面的流式分支跑在虚拟线程上读不到，
     *     所以身份必须在这里取好、以参数形式传下去；</li>
     *     <li>准备：参数校验 → 会话定位/新建 → 配额预检 → 取上下文窗口。
     *     与流式接口共用同一个私有方法，保证鉴权与归属规则完全一致；</li>
     *     <li>用户消息先落库：反过来的话模型一失败，用户刚才的提问就不在历史里了；</li>
     *     <li>检索资料（RAG，即「先检索、再生成」：从自有知识库找出相关片段，
     *     连同问题一起交给模型）。检索失败不扣额度，也不影响已落库的用户消息；</li>
     *     <li>调用模型：一次同步阻塞的外部 HTTP 调用，刻意留在数据库事务之外；</li>
     *     <li>成功后才落库回答：assistant 消息与会话活跃时间在同一事务内，
     *     配额计数在事务提交之后；</li>
     *     <li>组装响应（会话 ID 每次都要回传，前端首次提问后存下它即可无感续聊）。</li>
     * </ol>
     *
     * <p>容易踩的坑：上下文窗口必须在用户消息落库之前取好（由 prepareChat 完成）。
     * 若先落库再取历史，本次提问就会作为「最近一条历史」再被送进模型一遍，
     * 模型会看到两个一模一样的问题。另外，① 一旦挪到检索之后，
     * 超限用户每次提问都会白烧一次 Embedding。
     *
     * @param request 提问请求：message 必填且不超过 500 字；sessionId 可空，
     *                为空表示新建会话。整个请求体为 null 时得到 A0401，不会抛空指针
     * @return 会话 ID（新建的或原会话的）、回答正文、本次引用到的资料列表；
     * 没有命中任何资料时 sources 是空列表而不是 null，调用方无需判空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     * 会话不存在或无权访问（A0402）、提问次数超限（A0501）、AI 调用失败（C0200）时抛出
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
     * <p>为什么这样设计：SSE（Server-Sent Events，服务端保持一条 HTTP 连接、
     * 持续往里写事件帧的传输方式）天生不适合套统一响应体——
     * {code, data, message} 是一次性 JSON，套上之后前端必须等整个 JSON 结束才能渲染，
     * 流式就失去了意义。因此这是全项目唯一不返回统一响应体的接口，
     * 代价是所有失败也走事件流：参数错误、会话不存在、配额超限、AI 失败
     * 一律变成 error 事件，HTTP 状态码恒为 200，业务码放在事件负载里。
     * 前端必须监听 error 事件，不能再依赖「非 0 code 就报错」的统一拦截逻辑。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>创建发射器并设置超时。超时必须给足「模型超时 + 余量」，
     *     Spring 的 SseEmitter 默认只有 30 秒，比模型的 60 秒读超时还短，
     *     会让长回答在服务端被提前掐断，表现为「流突然没了」而日志里没有错误；</li>
     *     <li>注册三个回调把中止标记置位。客户端断开、超时、写入报错都会触发，
     *     标记之后不再推 delta、不落 assistant 消息、不计数——用户没拿到回答，
     *     就不该扣额度，也不该留下半截回答；</li>
     *     <li>在请求线程上取好登录身份再传进去（原因见行内注释）；</li>
     *     <li>把真正的取数与推流交给虚拟线程，方法立刻返回发射器。</li>
     * </ol>
     *
     * <p>容易踩的坑：登录身份必须在请求线程取。Sa-Token 的登录态与 Spring 的
     * RequestContextHolder 都存在 ThreadLocal（线程本地变量，每个线程一份副本）里，
     * 虚拟线程读不到，表现为 StpUtil.isLogin() 直接抛异常，
     * 于是流里只剩一个 error 事件、连 meta 都发不出来。
     * 这一行是本类唯一依赖「请求线程」的地方，改动时务必保留。
     *
     * @param request 提问请求：message 必填；sessionId 可空表示新建会话
     * @return SSE 发射器。方法立即返回（让 Tomcat 释放请求线程），
     * 推流在虚拟线程中继续；调用方不要在这个对象上做业务判断，
     * 业务结果只能从事件里读
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法自身不抛业务异常：
     * 鉴权、参数、配额、AI 失败都发生在推流阶段，一律转成 error 事件返回
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
     * <p>为什么这样设计：只取最近 50 条而不是全部，是因为会话会随对话轮次无限增长，
     * 一次全量返回会让前端一打开历史页就拉回整个会话。
     * 返回时翻正为时间正序，是因为对话界面自上而下就是「旧 → 新」，
     * 在服务端翻一次比让前端每次再 reverse 一次更可靠。
     * 归属校验与提问完全一致——否则这个接口会变成「探测某会话是否存在」的入口：
     * 攻击者可以拿一批 ID 逐个试，从错误码的差异里推断出哪些会话真实存在。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>解析会话 ID：空、非数字、小于等于 0 都按 A0401 处理。
     *     这一步必须最先做，脏 ID 若留到后面会变成一次无意义的数据库查询；</li>
     *     <li>取登录用户：未登录直接 A0201。AI 咨询不向游客开放，
     *     因此这里不接受「游客也能查」的分支；</li>
     *     <li>校验会话可访问（存在 + 归属），不存在与无权统一 A0402。
     *     必须排在查消息之前，否则会先把数据查出来再发现没权限，白查一次；</li>
     *     <li>倒序取最近 N 条再翻正。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序键必须用雪花 ID 而不是 created_at。
     * created_at 是秒级精度，同一秒内写入的 user / assistant 两条消息时间完全相同，
     * 只按时间排序时数据库返回的先后顺序不稳定，表现为对话记录偶发颠倒。
     * 另一个坑是 SQL 里必须 desc + limit：正序取 50 条取到的是最早的 50 条，
     * 长会话里反而看不到最新的几条。
     *
     * @param sessionId 会话 ID（必填，来自查询参数）；为空或非法时得到 A0401
     * @return 消息列表（旧 → 新）；会话存在但一条消息都没有时返回空列表，不会是 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（A0401）、
     * 会话不存在或无权访问（A0402）、未登录（A0201）时抛出
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
     * <p>为什么这样设计：用户维度必须由服务端从登录态取，请求里根本没有 userId 参数——
     * 一旦允许前端传 userId，「看到别人的会话列表」就成了一个功能。
     * 按 last_active_at（会话表的 updated_at）排序，而不是 join 消息表求 MAX：
     * 会话表自己维护这个冗余列，「最近聊过什么」因此是一次单表查询。
     * 用冗余列换掉一次 join 是有代价的（每次提问都要顺手刷新它），
     * 但刷新发生在 persistAnswer 里、与写消息同一个事务，代价可控。
     * 分页里的 messageCount 由一次批量统计得出，而不是在循环里逐个会话 count——
     * 后者是最典型的 N+1（先查 1 次列表、再为每行各查 1 次，一页 20 条就变成 21 次查询）。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>取登录用户，未登录 A0201。会话列表天然是「我的」，游客没有这个维度；</li>
     *     <li>归一化分页参数：null 或小于 1 一律按第一页 10 条处理，
     *     并把每页条数收敛到上限 50；</li>
     *     <li>按 user_id 等值 + updated_at 倒序 + id 倒序查询分页；</li>
     *     <li>批量补齐 messageCount 后转 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：user_id 条件不能省，少了它就是把所有人的会话都列出来。
     * 次级排序键必须是 id：updated_at 是秒级精度，同一秒内活跃过的多个会话会并列，
     * 没有稳定键时翻页会出现「同一条会话在两页里都出现，或者两页都没有」。
     * 另外 pageSize 不收敛的话，一个 pageSize=100000 就能把整张表读进内存。
     *
     * @param request 分页参数（current / pageSize），允许为 null，此时按第一页 10 条处理
     * @return 会话分页结果；一页都没有数据时 records 是空列表、total 为 0，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
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
     * <p>为什么这样设计：对话是用户资产，误删需要可追溯，因此父子两张表都用
     * 逻辑删除（不执行 DELETE，而是把 deleted_at 写成毫秒时间戳，查询时自动过滤掉非 0 的行），
     * 而不是物理删除。两张表的写必须在同一事务内：先删子表（消息）再删父表（会话），
     * 顺序反过来一旦子表删除失败，就会留下「会话没了、消息还在」的孤儿数据。
     * 归属校验与提问共用同一套规则，因此重复删除第二次会得到 A0402——
     * 逻辑删除后按 ID 查不到，与「不存在」走同一条路径，这是刻意的。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>解析会话 ID（空 / 非数字 / 非正数 → A0401）；</li>
     *     <li>取登录用户并校验会话可访问：不存在与无权统一 A0402。
     *     必须排在删除之前，否则「删别人的会话」会先删成功再报错；</li>
     *     <li>事务内先删消息、再删会话：两条 UPDATE 要么都成功、要么都回滚，
     *     这样才不存在「会话已删、消息还在」的中间状态。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要用「先查消息条数、再决定删不删」的写法。
     * 这里的删除语义是「把会话和它的全部消息一起标记为已删除」，
     * 不需要逐条确认消息存在；多一次 count 查询只会让删除变慢，
     * 还会引入「查询与删除之间消息又变多」的竞态。
     * 另外 messages 的删除条件必须是 session_id 等值，不能按 id 列表——
     * 消息会在对话过程中不断新增，按列表删会漏掉查询之后新写入的那几条。
     *
     * @param sessionId 会话 ID（必填）；为空或非法时得到 A0401
     * @return 恒为 true。方法没有「删除失败返回 false」的分支——
     * 删除不成功一律抛异常，调用方不必判返回值
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（A0401）、
     * 会话不存在或无权访问（A0402）、未登录（A0201）、数据库影响行数不为 1（B0002）时抛出
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
     * <p>为什么这样设计：同步（/ai/chat）与流式（/ai/chat/stream）
     * 的鉴权与归属规则必须「完全一致」——唯一可靠的做法是走同一份代码。
     * 复制一份出来早晚会漂移：改了一处忘了另一处，就是一个越权漏洞，
     * 而且这种漏洞不会在功能测试里暴露，只有人刻意构造请求才会发现。
     *
     * <p>执行步骤（与代码里的 ⓪①②③ 标注一一对应）：
     * <ol>
     *     <li>⓪ 校验登录：AI 服务不向游客开放，门槛只此一处，
     *     因此后面的会话解析、配额查询、Embedding 都不必再为「没有身份」写分支；</li>
     *     <li>参数校验：请求体判空、提问 trim 后判空、长度上限。
     *     全部排在会话解析之前——一个空提问不该产生一次建会话的写库；</li>
     *     <li>① 会话：sessionId 为空则新建（标题取首问前 30 字），非空则校验存在性与归属。
     *     必须排在配额预检之前：配额是「按用户」算的，而新建会话会把当前用户写进去，
     *     后面的计数也要用同一个用户 ID；</li>
     *     <li>② 配额预检：普通用户受次数上限约束，成员 / 管理员不限额（unlimited = true）；</li>
     *     <li>③ 上下文窗口：取最近 10 条（内部已翻正为时间正序），交给 Manager 拼 prompt。
     *     必须排在「用户消息落库」之前：落库之后本次提问就会作为最近一条历史被取到，
     *     于是同一句话被送进模型两遍。</li>
     * </ol>
     *
     * <p>容易踩的坑：上下文窗口若在用户消息落库之后才取，本次提问就会作为
     * 「最近一条历史」再被送进模型一遍，模型会看到两个一模一样的问题，
     * 表现为回答开始复读或答非所问。另外 trim 必须在判空之前：
     * 「   」这种纯空格能通过「非 null」检查，却是一个没有内容的提问。
     *
     * @param request 提问请求，可为 null（会被兜成 A0401，而不是空指针）
     * @param userId 当前登录用户 ID，由调用方在请求线程上取好传进来；
     *               为 null 表示游客，本方法直接拒绝（A0201）。
     *               不能在本方法内部去取：流式路径的调用方跑在虚拟线程上，
     *               而 Sa-Token 的登录态存在 ThreadLocal 中，跨线程取不到
     * @return 准备结果（用户、会话、提问、上下文、是否不限额）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 参数不合法（A0401）、会话不存在或无权访问（A0402）、提问次数超限（A0501）时抛出
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
     * <p>为什么这样设计：同步与流式共用本方法，这样「流式落库的 SQL 与同步接口对齐」
     * 是结构上成立的，而不是靠两边各写一遍再人工比对。
     * 三件事里只有两件进事务：消息与会话活跃时间都写数据库，必须同生共死——
     * 否则会出现「有回答但会话列表不更新」或「会话更新了但回答没存」。
     * 配额计数被刻意排除在事务之外：它走 Redis 的 INCR，Redis 是外部系统，
     * 数据库事务回滚撤不回已经发出的 INCR。放进事务里就会出现
     * 「回答没存成功、额度却被扣了」——用户最不能接受的一类错误。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>在事务内插入 assistant 消息，并刷新会话的 updated_at；</li>
     *     <li>事务提交之后再自增配额。顺序不能反：先计数再提交，
     *     一旦提交失败，额度就白扣了；</li>
     *     <li>返回消息 ID（流式接口要把它放进 done 事件）。</li>
     * </ol>
     *
     * <p>容易踩的坑：事务回调是个 lambda，不能直接给外部局部变量赋值，
     * 所以这里用一个一元素数组当容器。用 AtomicLong 也能实现，但那是给并发场景准备的，
     * 这里只是「绕开 lambda 的 effectively final 限制」，数组更轻也更直白。
     * 另外不限额用户（成员 / 管理员）既不预检也不计数，两个判断要成对出现——
     * 只跳过预检不跳过计数，会让不限额角色的计数无限增长。
     *
     * @param prepared 提问准备结果，其中的 userId 与 sessionId 必须有效
     * @param answer 回答正文，非空（模型返回空内容时上游会抛 C0200，走不到这里）
     * @return 落库后的 assistant 消息 ID（雪花 ID，由 MyBatis-Plus 回填到实体上）
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
     * <p>为什么这样设计：RAG（先检索、再生成）是增强而不是依赖——
     * 向量服务不可用、或应用重启后内存向量库还没重建，都不该让用户问不出话。
     * 所以这里吞掉所有异常、返回空结果、只记一条 warn 日志，不新增错误码；
     * 用户侧的表现为「这次回答没有溯源」，而不是「AI 用不了」。
     * 这条纪律与埋点接口「统计失败不影响浏览」是同一条：旁路能力可以降级，
     * 主流程不能因为旁路故障而中断。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先看熔断器是否打开。AI 整体不可用（token 被冻结等）时，
     *     Embedding 调用必然同样失败，还要白等一次外部往返甚至超时；</li>
     *     <li>熔断时直接返回空结果，把等待留给随后的模型调用——
     *     而它会被熔断挡在毫秒级；</li>
     *     <li>未熔断则正常检索，任何异常都降级为空结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用只读的 isOpen() 而不是 allowRequest()。
     * 后者会消耗半开状态（熔断到期后放一个请求试探是否恢复）下唯一一次试探机会，
     * 而本方法无论检索成功失败都不会向熔断器回报结果——
     * 用了它，熔断器会认为「试探的请求消失了」，于是永远无法自愈。
     *
     * @param question 用户提问（已 trim 且非空，由 prepareChat 保证）
     * @return 检索结果；熔断或任何异常时返回空结果（sources 为空列表，不会为 null），
     * 调用方无需判空，用 hit() 判断有没有命中即可
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
     * <p>为什么这样设计：资料放 user 消息，不放 system 消息，这是刻意的。
     * 第一，system 消息应当稳定——它是「你是谁、怎么答」的行为约束，
     * 每次提问内容都不同，塞进去会让 system 随问题漂移，
     * 模型的行为基线也就跟着漂了。第二，把资料贴着问题放，
     * 模型更容易把它们当成「当前问题的上下文」，而不是通用指令，
     * 这对引用准确率有明显帮助。第三，历史消息里存的仍是用户原话
     * （落库时只写原始提问，不写拼了资料的版本），资料只是临时拼给本次调用的，
     * 不会污染上下文窗口——否则下一轮提问时，上一轮的资料会作为历史被再读一遍，
     * 越聊越偏。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>没命中就直接返回原问题，不加任何前缀——加一个空的「参考资料：」段落
     *     只会让模型以为「有资料但内容是空的」；</li>
     *     <li>命中则按「参考资料 → 空行 → 用户问题」的顺序拼装。</li>
     * </ol>
     *
     * <p>容易踩的坑：分隔符不能省。资料文本与问题之间没有明确分界时，
     * 模型可能把用户问题当成资料的一部分，也可能把资料当成用户在说话。
     * 另外不要在这里做长度截断：检索条数与相似度下限已经限制了总量，
     * 再截一次会把最后一条资料切成半句，比不截更糟。
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
     * <p>为什么这样设计：事件序列固定为 meta → sources → delta* → done / error，
     * 顺序本身就是契约，前端按事件名分派、按顺序渲染。
     * sources 恒发（即使没有命中资料也发一个空数组）：前端因此可以无分支地写
     * on('sources', s => setSources(s))；若只在命中时发，前端就得靠
     * 「等一段时间没收到」来判断没有资料——那是竞态，慢网络下必然误判。
     * 失败也走事件流而不是抛异常：方法已经返回了发射器，此时抛异常调用方也接不到。
     *
     * <p>执行步骤（与代码里的 ②③④⑤⑥⑦ 标注一一对应，meta 为第 ② 步的一部分）：
     * <ol>
     *     <li>准备：参数校验 → 会话定位/新建 → 配额预检 → 取上下文窗口（与同步接口同一份代码）；</li>
     *     <li>② 先发 meta 告知会话 ID。新建会话时前端只能从这里拿到它，所以它必须是第一个事件；</li>
     *     <li>用户消息先落库：失败或中断时它必须留下（与同步接口同一约定）；</li>
     *     <li>③ 检索资料：位置与降级策略都与同步接口一致；</li>
     *     <li>④ sources 恒发，无命中就是空数组；</li>
     *     <li>⑤ 流式调用，每段增量直接推给前端；</li>
     *     <li>⑥ 客户端已断开则直接返回：不落库、不计数、也不发 done（连接都没了）；</li>
     *     <li>⑦ 正常结束：先落库（共用 persistAnswer），事务提交后再发 done。</li>
     * </ol>
     *
     * <p>容易踩的坑：done 事件必须在落库之后发，且要在事务提交之后。
     * 若先发 done 再落库，前端会立刻显示「已完成」，而库里可能什么都没写——
     * 用户刷新页面后发现回答不见了。另外 catch 分成两支：
     * 业务异常（参数、配额、AI 失败）直接把业务码放进 error 事件；
     * 其它异常只回一个笼统的系统错误码，细节留在日志里，不向前端暴露堆栈信息。
     *
     * @param request 提问请求，可为 null（会在 prepareChat 里得到 A0401，并以 error 事件返回）
     * @param userId 当前登录用户 ID，由请求线程取好传进来（虚拟线程读不到
     *               ThreadLocal 里的登录态）；为 null 时以 error 事件返回 A0201
     * @param emitter SSE 发射器，不能为 null
     * @param state 中止标记，客户端断开时会被置位；本方法在每个推流点都会检查它
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
     * <p>为什么这样设计：负载必须序列化成 JSON 字符串。SSE 的 data 是纯文本，
     * 而 delta 里可能含换行、引号与中文——手拼字符串会把换行当成新的事件字段，
     * 直接破坏事件帧（前端收到的事件会被拆错，表现为回答里突然多出一行奇怪的内容）。
     * 交给 JsonMapper 序列化之后，换行会被转义成 \n，整个负载始终是单行。
     * 复用容器里被 JsonConfig 定制过的那个 JsonMapper，而不是自己 new 一个：
     * 全局配置（如 Long 转字符串）才能在这里同样生效。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先看中止标记。已中止就什么都不发——往一个已断开的连接写数据只会再抛一次异常；</li>
     *     <li>序列化负载并发送，事件名由参数决定；</li>
     *     <li>发送失败（客户端断开时通常是 IOException）只记 warn 并标记中止，
     *     不向上抛：本方法的调用点都在推流循环里，抛出去只会让整条流异常结束。</li>
     * </ol>
     *
     * <p>容易踩的坑：标记中止之后不要试图「再发一个 error 事件告诉前端出错了」——
     * 连接已经不可写，这次发送同样会失败。另外异常类型统一按 Exception 捕获，
     * 因为序列化失败与网络写入失败都可能发生，两者的处理方式在这里是一样的。
     *
     * @param emitter SSE 发射器，不能为 null
     * @param eventName 事件名（meta / sources / delta / done / error），改名等于契约变更
     * @param payload 负载，会被序列化成 JSON；Map 的 value 不能含循环引用
     * @param state 中止标记，已中止时本方法直接返回、不发任何数据
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
     * <p>为什么这样设计：新建与续聊写在一个方法里，是为了让调用方（prepareChat）
     * 不必关心「这次是不是第一句」——两种情况下它拿到的都是一个可用的会话实体。
     * 匿名会话（user_id 为空）刻意允许创建：官网的问答对游客开放，
     * 而游客没有账号可以绑定。安全性由 ID 本身承担——雪花 ID 是
     * 「时间戳 + 机器位 + 序列号」拼出的 19 位长整型，无法被逐个枚举，
     * 因此「知道这个 ID」就等价于「持有访问它的凭据」。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>sessionId 为空：新建会话，把 userId 原样写进去（可能为 null），
     *     标题取本次提问的前 30 字，插入失败抛 B0002；</li>
     *     <li>sessionId 非空：先解析（非法值 A0401），再按主键查；</li>
     *     <li>查不到按 A0402 处理。查询自带逻辑删除过滤，所以「已删除」与
     *     「不存在」走同一条路径，不暴露「这个会话曾经存在」；</li>
     *     <li>查到了再做归属校验。</li>
     * </ol>
     *
     * <p>容易踩的坑：新建会话这一步是写库，而它发生在配额预检之前
     * （配额是「按用户」算的，必须先有会话才谈得上后面的计数）。
     * 这意味着超限用户的一次提问也可能建出一个空会话——
     * 这是可接受的：空会话在列表里显示为「0 条消息」，用户删掉即可，
     * 而把预检提前会让「按用户算配额」失去 userId 的来源。
     *
     * @param sessionIdText 请求里的会话 ID 文本，可为 null 或空（表示新建）
     * @param userId 当前登录用户 ID，可为 null（表示游客，建出匿名会话）
     * @param message 本次提问（已 trim 且非空），新建会话时用于生成标题
     * @return 会话实体；新建时它的 id 已由 MyBatis-Plus 回填
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 非法（A0401）、
     * 会话不存在或无权访问（A0402）、新建会话失败（B0002）时抛出
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
     * <p>为什么这样设计：它把「存在性判断 + 归属判断」包成一步，
     * 调用方只需要一行就能表达「这个会话我可以访问吗」。
     * 单独抽出来还有一个原因：历史、删除两个接口都必须与提问用同一套规则，
     * 任何一处写成「只查存在」都会变成越权入口。
     * 两次查询（这里一次、调用方随后再查一次消息）是刻意的：
     * 先确认权限再取数据，比「先取数据再判断权限」少泄露一次数据。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按主键查会话（自动带逻辑删除过滤）；</li>
     *     <li>查不到抛 A0402；</li>
     *     <li>查到了交给 assertSessionOwner 做归属校验。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只校验「能不能访问」，不返回会话实体。
     * 若为了省一次查询把它改成返回实体，调用方就可能顺手拿它去判断业务状态，
     * 而这里查出来的实体在归属校验之前是不该被使用的——把顺序藏在方法内部，
     * 才能保证没有人能跳过着色校验直接读数据。
     *
     * @param sessionId 会话 ID，调用方已保证为正数（parseSessionId 校验过）
     * @param userId 当前登录用户 ID，可为 null（游客只可能访问匿名会话）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话不存在、已删除，
     * 或当前用户无权访问时抛出（A0402，两种情况同一句提示）
     */
    private void assertSessionAccessible(long sessionId, Long userId) {
        StudioAiSession session = studioAiSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, SESSION_NOT_FOUND_MESSAGE);
        assertSessionOwner(session, userId);
    }

    /**
     * 会话归属校验
     *
     * <p>为什么这样设计：规则只有两条，但每条都有明确的取舍。
     * 匿名会话（user_id 为 null）任何人都可凭 ID 访问——它不是「无主的公共资产」，
     * 而是「游客自己创建的会话」，安全性由 19 位雪花 ID 的不可枚举性承担。
     * 绑定用户的会话只有本人可访问，其他人（含游客）一律按「不存在」处理：
     * 越权与不存在必须返回完全相同的错误码与提示，否则攻击者可以用
     * 「A0402 的提示有没有差异」来判断某个会话是否真实存在，
     * 那这个接口就成了一次低成本的探测工具。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>user_id 为空：直接放行，不做任何比较；</li>
     *     <li>user_id 非空：用 Objects.equals 比较会话所有者与当前用户，
     *     不等就抛 A0402。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较必须用 Objects.equals 而不是 ==，也不能直接
     * session.getUserId().equals(userId)——后者在 userId 为 null（游客）时
     * 会得到 false，看似正确，但语义上依赖了「左边非空」这个前提，
     * 一旦会话的 user_id 变成 null 就变成空指针。Objects.equals 对 null 是安全的，
     * 而且游客访问他人会话时会正常返回 false，走同一条拒绝路径。
     *
     * @param session 会话实体，不能为 null（调用方已确认查询有结果）
     * @param userId 当前登录用户 ID，可为 null（游客）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话属于他人时抛出（A0402）
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
     * <p>为什么这样设计：会话 ID 在 HTTP 层是字符串（可能来自 query 参数、
     * 也可能来自请求体），而内部一律用 long。转换这件事必须收在一个方法里，
     * 否则每个入口都要重复「判空 + 转数字 + 判正数」三步，漏一步就是 500。
     * 空值提示做成参数，是因为提问与历史两个接口的用户预期不同：
     * 提问时没带 ID 是正常的（表示新建会话），历史时没带 ID 则是缺参数。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>空值直接抛 A0401，提示由调用方给；</li>
     *     <li>用 Convert 转 Long，转换失败得到 null；</li>
     *     <li>null 或非正数一律 A0401。</li>
     * </ol>
     *
     * <p>容易踩的坑：用 Convert 而不是 Long.valueOf。后者遇到 "abc" 会抛
     * NumberFormatException，被全局异常处理器兜成 500——而这明明是用户传错了参数，
     * 应该是 400 语义（本项目的 A0401）。另外必须校验正数：
     * 雪花 ID 永远为正，负数只可能来自伪造或程序错误。
     *
     * @param sessionIdText 会话 ID 文本，可为 null
     * @param blankMessage 文本为空时的提示文案，不能为 null
     * @return 解析后的会话 ID，保证大于 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 文本为空（用传入的提示）、
     * 非数字或非正数（A0401）时抛出
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
     * <p>为什么这样设计：标题是给会话列表展示用的，太长反而要前端再截一次。
     * 用首问的开头而不是「新会话」这类固定文案，是因为用户在一堆会话里
     * 是靠内容找回忆的，而不是靠时间。30 这个数字来自产品口径，
     * 放在常量里（AiChatConstant.SESSION_TITLE_MAX_LENGTH）而不是写死，
     * 与 DDL 里 title 列宽 64 的关系是「业务截断 30，列宽留一倍余量」。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>不超过上限就原样返回，不做任何拼接；</li>
     *     <li>超长则截取前 30 个字符，不加省略号——列宽是硬约束，修饰字符也占位。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法假设入参已 trim 且非空（由 prepareChat 保证）。
     * 若在别处直接调用并传入纯空白，标题就会变成一串空格，
     * 会话列表里显示为一个空行——不会报错，但也没法看。
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
     * <p>为什么这样设计：成员与管理员不限额（返回 true），直接放行、也不计数；
     * 普通注册用户受 studio.ai.query-limit（默认 5 次）约束，超限抛 A0501。
     * 这里读的是「当前值」，并发下两个请求可能同时通过预检，最终略微超出上限——
     * 这是刻意接受的：配额是「防护性上限」而不是计费依据，
     * 为了它加锁或加分布式信号量的代价远大于收益。
     * 真正不能错的是自增本身必须原子：计数由 AiQueryCountManager 用 Redis 的
     * INCR 完成，一条命令里完成「读 + 加 + 写」，并发下不会互相覆盖，
     * 否则计数偏小就等于白送额度。
     * 角色取自 sys_user.user_role 而不是 StpUtil.hasRole：
     * 流式提问的预检跑在虚拟线程上，而 Sa-Token 的登录态存在 ThreadLocal 里，那里取不到。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按主键查账号，查不到说明登录态指向了一个已被删除的用户，按未登录处理；</li>
     *     <li>成员 / 管理员直接返回 true（不限额），不做任何计数查询；</li>
     *     <li>读配置的次数上限。配置为 null 时用 Integer.MAX_VALUE，
     *     即「配置没写就当作不限制」，而不是当成 0 把所有普通用户挡在门外；</li>
     *     <li>取「数据库基准 + Redis 未回刷增量」的合并用量，达到上限就抛 A0501。</li>
     * </ol>
     *
     * <p>容易踩的坑：必须是合并值。只看数据库的话，定时回刷之前的那几次提问
     * 都不算数（表现为「问了好几次还没到上限」），配额就成了摆设。
     * 合并读取还有个好处：预检不依赖回刷是否已经发生，行为在时间上是均匀的。
     * 另外比较用 used &gt;= limit 而不是 used &gt; limit：
     * 上限 5 次的含义是「最多问 5 次」，第 6 次应当被拒绝。
     *
     * @param userId 登录用户 ID，调用方已保证非 null（prepareChat 已挡掉游客）
     * @return true 表示不限额（成员 / 管理员）；false 表示按次数上限计
     * @throws com.bhu.runshistudioweb.exception.BusinessException 账号不存在（A0201）、
     * 提问次数已达上限（A0501）时抛出
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
     * <p>为什么这样设计：配额只有三档角色里的普通用户需要受约束。成员与管理员
     * 是工作室内部的维护者，给他们设次数上限只会让人在答疑或整理资料时被拦住。
     * 判断写成「显式列出两个不限额的角色」，而不是「不等于 user 就不限额」：
     * 后者在将来新增角色（例如只读访客）时会默认放行，而新角色到底该不该
     * 受配额约束是需要产品决策的，不该由一行默认逻辑替它决定。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用常量做两次等值比较，任一命中即返回 true。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较必须用常量（UserRoleConstant）而不是手写字面量。
     * 角色值是与 @SaCheckRole 注解共用的编译期字符串，手写一份就多了一个
     * 「改了一处忘改另一处」的机会，而后果是「成员突然开始受配额限制」且不报错。
     * 另外入参允许为 null：equals 的调用方是常量，所以 null 只会得到 false，
     * 不会抛空指针。
     *
     * @param role 用户角色原始值（取自 sys_user.user_role），可为 null（按不限额之外处理）
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
     * <p>为什么这样设计：查询必须「倒序 + 限量」，返回必须「正序」。
     * 若正序取前 N 条，拿到的是会话最早的那些消息，长会话里反而看不到最新的几句；
     * 而对话界面自上而下就是「旧 → 新」，所以在服务端翻一次，比让前端每次再 reverse 更可靠
     * （前端有多个入口会用到这段历史，翻正逻辑只写一处才不会漂移）。
     * searchCount 关掉是刻意的：这里只要数据不要总数，开着会白白多一次 COUNT 查询。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按 session_id 等值、id 倒序构造查询；</li>
     *     <li>用 Page 取前 limit 条，并显式关闭 count；</li>
     *     <li>把结果复制成可变列表后 reverse（Page 返回的列表不保证可变）；</li>
     *     <li>逐条转 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序键必须用雪花 ID 而不是 created_at。created_at 是秒级精度，
     * 同一秒内写入的 user / assistant 两条消息时间完全相同，只按时间排序时
     * 数据库返回的先后顺序不稳定，表现为「对话记录偶发颠倒」。
     * 另外必须 new ArrayList 再 reverse：直接对查询结果调 reverse，
     * 若返回的是不可变列表会抛 UnsupportedOperationException。
     *
     * @param sessionId 会话 ID，调用方已确认当前用户有权访问
     * @param limit 取多少条，必须大于 0；实际返回可能少于它（会话消息不足时）
     * @return 消息 VO 列表，旧 → 新；会话没有消息时返回空列表，不会返回 null
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
     * <p>为什么这样设计：插入语句只有三列，但三列都必需。role 存枚举的字符串值
     * 而不是序号：序号一旦调整枚举顺序就会把历史数据全部解释错，
     * 而字符串值在数据库里是自解释的，出问题时看一眼就知道是什么角色。
     * 插入后必须校验影响行数：为 0 说明写库没成功，此时继续往下走
     * 会得到一个「消息 ID 是 0」的假结果，错误会拖到很远才暴露。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>组装实体：会话 ID、角色、正文；</li>
     *     <li>插入并校验影响行数，不为 1 抛 B0002；</li>
     *     <li>返回回填后的 ID。</li>
     * </ol>
     *
     * <p>容易踩的坑：ID 是插入之后才有值的。MyBatis-Plus 会把生成的雪花 ID
     * 回填到实体对象上，所以必须在 insert 之后读 entity.getId()，
     * 不能提前把它存进局部变量。另外本方法不校验 content 非空：
     * 调用方已经保证（提问经过 trim 判空，回答由 AiManager 保证非空），
     * 在这里再判一次只会让「空回答」这种上游问题被掩盖成本地问题。
     *
     * @param sessionId 会话 ID，调用方已确认存在且有权访问
     * @param role 消息角色（user / assistant），不能为 null
     * @param content 消息正文，调用方保证非空
     * @return 新消息 ID（雪花 ID，由 MyBatis-Plus 回填到实体上，
     * 流式接口要把它放进 done 事件）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 插入影响行数不为 1 时抛出（B0002）
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
     * <p>为什么这样设计：会话列表要按「最近聊过什么」排序，而消息表上没有这个信息，
     * 只有每条消息各自的创建时间。两种做法：每次列表查询都 join 消息表求 MAX，
     * 或者让会话表自己维护一个冗余列。这里选后者——一次单表查询就能排序，
     * 而冗余列的刷新成本很低：每次提问本来就要写消息，顺手多一条 UPDATE。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>构造一个只带主键的实体（其它字段全为 null）；</li>
     *     <li>调 updateById。MyBatis-Plus 会跳过 null 字段，
     *     再加上审计字段的更新填充，等价于只更新 updated_at / updated_by。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要改成「先查出来、改 updatedAt、再 updateById」。
     * 那样会多一次查询，而且会把整个实体写回去——并发下可能用旧值覆盖掉
     * 别人刚改的 title。这里刻意用「只带 id 的空实体」，就是为了让 UPDATE
     * 只触碰审计字段。另外本方法不校验返回值：刷新活跃时间失败不该让
     * 整次提问失败，会话仍然可读，只是列表里的排序暂时不准。
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
     * <p>为什么这样设计：实体与 VO 的字段名和类型一一对应（id / role / content / createdAt），
     * 所以可以整体拷贝，不必逐字段赋值。VO 里没有 sessionId 与审计字段，
     * 因此「不泄露内部标识」是结构上成立的，而不是靠记得别赋值。
     * 手写赋值在这里反而更容易漏字段：将来实体加一个该展示的字段时，
     * copyProperties 会自动带上，而手写清单会漏。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>新建 VO；</li>
     *     <li>按同名同类型拷贝，返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：copyProperties 只拷「两边都存在且类型兼容」的属性，
     * 名字不同或类型不同会静默跳过，不报错。所以给 VO 加字段时，
     * 若字段名与实体不一致（例如实体叫 id、VO 叫 messageId），必须手动赋值，
     * 否则前端会拿到 null 而不是报错。
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
     * <p>为什么这样设计：会话列表要显示「这个会话聊了几句」，而消息条数不在会话表上。
     * 最直觉的写法是在循环里对每个会话 count 一次，那是最典型的 N+1
     * （先查 1 次列表、再为每行各查 1 次，一页 20 条就变成 21 次查询）。
     * 这里改成先收集本页全部会话 ID，用一条 IN 查询 + GROUP BY 一次算完。
     * 空页必须提前返回：拿空集合去拼 IN () 是 SQL 语法错误，
     * 而「列表页没数据」是最常见的场景之一。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>空列表直接返回不可变空列表，不做任何查询；</li>
     *     <li>一次批量统计拿到「会话 ID → 消息条数」；</li>
     *     <li>逐个组装 VO，取不到条数的补 0（GROUP BY 不会返回「0 条」的行）。</li>
     * </ol>
     *
     * <p>容易踩的坑：messageCount 必须补 0 而不是留 null。前端若直接用这个数字
     * 做判断（例如「有消息才显示删除按钮」），null 会让它走进意料之外的分支；
     * 而且 VO 里这个字段是 Integer，null 与 0 在界面上是两种显示。
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
     * <p>为什么这样设计：这是本类最关键的一条查询纪律。绝不能写成
     * for (会话 s : 本页) { count(s.id) } —— 那是最典型的 N+1
     * （查 1 次列表，再对每条记录各查 1 次），一页 20 条会变成 21 次查询。
     * 改成一条 IN + GROUP BY 之后，查询次数与页大小无关。
     * deleted_at = 0 由 MyBatis-Plus 依 @TableLogic 自动追加，
     * 因此「已被删除的消息」不会计入条数——与列表接口的可见性保持一致。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用字符串列名构造 select：分组列 + COUNT(*) AS cnt。
     *     这里不能用 LambdaQueryWrapper，因为它表达不了聚合函数；</li>
     *     <li>加 IN 条件与 GROUP BY；</li>
     *     <li>逐行读结果，跳过分组列为 null 的行；</li>
     *     <li>把计数按 Number 取出并转 int。</li>
     * </ol>
     *
     * <p>容易踩的坑：绝不硬转 (Long)。COUNT(*) 在不同驱动 / 版本下可能是 Long
     * 或 BigInteger，硬转属于「本机测试通过、换环境 ClassCastException」的经典坑。
     * 另外 IN 的列表不能为空——调用方（toSessionVOs）已经提前挡掉了空列表，
     * 本方法依赖这个前提。
     *
     * @param sessionIds 本页会话 ID 列表，调用方保证非空
     * @return 会话 ID → 消息条数；某会话一条消息都没有时不会出现在 Map 里，
     * 由调用方用 getOrDefault 补 0
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
     * <p>为什么这样设计：AI 问答允许游客使用（匿名会话），因此「没有用户」是正常状态，
     * 不能用抛异常来表达。返回 null 让调用方自己决定怎么处理：
     * 提问路径把 null 当成游客，列表与历史路径则用 requireLoginUserId 把 null 转成 A0201。
     * 如果这里直接抛异常，「游客提问」这个功能就没法实现了。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>未登录直接返回 null；</li>
     *     <li>已登录则取 loginId 并转成 Long，转不出来也按游客处理。</li>
     * </ol>
     *
     * <p>容易踩的坑：本项目登录统一用 Long 型用户 id，但 Sa-Token 的 loginId 是 Object。
     * 万一将来出现非数字 loginId（换了账号体系、或 token 被篡改），
     * 这里按游客处理而不是抛异常——提问不应该因为一个 id 形态问题变成 500。
     * 另外本方法依赖 ThreadLocal 里的登录态，必须在请求线程调用；
     * 在虚拟线程里调会读到空的登录态，于是游客与登录用户被混为一谈。
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
     * <p>为什么这样设计：AI 咨询本身对游客开放，但「会话列表」「历史」「删除会话」
     * 这三件事都需要一个明确的「我」——游客没有会话列表这个维度，
     * 历史与删除则必须校验归属。所以这些入口统一用本方法取用户，
     * 把「取 id + 判空 + 抛 A0201」三步收在一处，避免每个入口各写一遍（漏一处就是越权）。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>复用 currentUserIdOrNull 取 id；</li>
     *     <li>为 null 抛 A0201，否则返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：提示文案写成「请先登录后再使用 AI 助手」而不是笼统的「未登录」，
     * 是因为这里的调用方都来自 AI 模块，用户需要知道「登录之后能继续做什么」。
     * 另外返回值是基本类型 long，调用方不必判空——这正是把它与
     * currentUserIdOrNull 分成两个方法的意义：一个允许空，一个不允许。
     *
     * @return 登录用户 ID（保证非 null，已拆箱）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
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
     * <p>为什么这样设计：prepareChat 需要一次返回五个值（用户、会话、提问、上下文、
     * 是否不限额），而 Java 没有多返回值。用 record 而不是普通类：它是纯粹的
     * 数据载体，不可变、不需要 setter，也不需要进 Spring 容器。
     * 也不用 Map 或 Object[]：那样调用方要按字符串或下标取值，拼错一个 key
     * 就得到 null，而 record 的访问器是编译期检查的。
     * 把 unlimited 一起带出来，是为了让 persistAnswer 不必再查一次数据库判断角色——
     * 预检时已经查过了，重复查只会多一次往返，还可能因为中间角色被改而前后不一致。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>构造时按顺序传入五个值；</li>
     *     <li>调用方用 userId() / sessionId() 等访问器取值，由编译器自动生成。</li>
     * </ol>
     *
     * <p>容易踩的坑：userId 是包装类型 Long，其余是基本类型。这不是随手写的：
     * userId 需要允许 null 表达「游客」，而 sessionId 与 unlimited 在准备完成后
     * 一定有意义，用基本类型可以让调用方省掉判空。
     *
     * @param userId 当前登录用户 ID，游客为 null（同步路径允许游客提问，匿名会话的 user_id 就存 null）
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
     * <p>为什么这样设计：SSE 的断开是异步通知的（onCompletion / onError 回调），
     * 而推流线程可能正跑在回调到来之前的某一步。没有这个标记，
     * 就会出现「用户已经关掉页面，服务端还在把回答写进库、还算进配额」——
     * 用户没拿到内容却被扣了额度，是最不能接受的一类错误。
     * 用 volatile 而不是 AtomicBoolean：这里只有「写一个 boolean、读一个 boolean」，
     * 没有任何复合操作，volatile 的可见性保证已经足够，
     * 而 AtomicBoolean 的 CAS 能力在这里完全用不上。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>markAborted 由回调线程或推流线程调用，写入 true；</li>
     *     <li>isAborted 由推流线程在每个关键节点（发事件前、落库前）读取。</li>
     * </ol>
     *
     * <p>容易踩的坑：标记是单向的，没有「恢复」操作。这是刻意的——
     * 一次连接断开之后不可能再恢复，与其提供 reset 让人误用，不如让它只进不退。
     * 另外它只是「尽力而为」的会合点：回调与推流线程之间仍可能有一个极窄的窗口，
     * 极端情况下仍会写进一条消息；这是可接受的，因为代价只是一条无用的记录。
     */
    private static final class StreamState {

        private volatile boolean aborted;

        /**
         * 置位中止标记
         *
         * <p>为什么这样设计：它会被三个回调（onCompletion / onTimeout / onError）
         * 与推流线程自己（写事件失败时）共同调用，因此必须是幂等的——
         * 重复置位不产生任何副作用。做成单向的「只进不退」也是刻意的：
         * 连接断开之后不可能恢复，提供 reset 只会让人误用。
         *
         * <p>执行步骤（与代码里的顺序一一对应）：
         * <ol>
         *     <li>把 aborted 写成 true。volatile 保证这次写对其他线程立即可见。</li>
         * </ol>
         *
         * <p>容易踩的坑：本方法没有并发上的陷阱——两个线程同时写同一个 boolean
         * 得到的结果一样，不需要 CAS。
         */
        void markAborted() {
            this.aborted = true;
        }

        /**
         * 是否已中止
         *
         * <p>为什么这样设计：推流线程在三个关键节点都要问一次——发事件前、落库前、
         * 计数前。收成一个方法，是为了让「哪些地方需要检查」在代码里一眼可数；
         * 若各处直接读字段，很容易在新增分支时漏掉一处。
         *
         * <p>执行步骤（与代码里的顺序一一对应）：
         * <ol>
         *     <li>直接返回字段值。volatile 读保证能看到其他线程最近的写入。</li>
         * </ol>
         *
         * <p>容易踩的坑：本方法没有边界陷阱。它只是「尽力而为」的会合点：
         * 回调与推流线程之间仍有一个极窄的窗口，极端情况下仍会写进一条消息，
         * 代价只是一条无用记录，不值得为此加锁。
         *
         * @return true 表示客户端已断开、超时或写事件失败
         */
        boolean isAborted() {
            return aborted;
        }
    }
}
