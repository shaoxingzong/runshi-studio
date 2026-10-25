package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionQueryRequest;
import com.bhu.runshistudioweb.model.vo.ai.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.ai.AiMessageVO;
import com.bhu.runshistudioweb.model.vo.ai.AiSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 咨询服务接口（知识库问答的对外契约）
 *
 * <p>本接口描述五件事：同步提问、流式提问、查历史、查我的会话列表、删会话。
 * 其中「提问」是全项目唯一同时依赖数据库与外部 AI 服务的链路：
 * 它先用 RAG（Retrieval-Augmented Generation，检索增强生成——先从自有知识库里
 * 检索相关资料，再把资料和问题一起交给大模型）取资料，再调用模型生成回答。
 *
 * <p>会话归属规则（本模块的安全核心，五个方法共用同一套判断）：
 * <ul>
 *     <li>会话的 user_id 非空：只有创建它的那个登录用户能访问；</li>
 *     <li>会话的 user_id 为空（匿名会话）：凭雪花 ID 即可访问。雪花 ID 是
 *     「时间戳 + 机器位 + 序列号」拼出来的 19 位整数，无法被逐个枚举，
 *     因此「知道这个 ID」等价于「持有访问凭据」；</li>
 *     <li>会话不存在、与无权访问：统一抛 A0402 并使用同一句提示。
 *     把两种情况合成一个响应是刻意的——分开提示等于送给攻击者一个
 *     「哪些会话 ID 真实存在」的探测器。</li>
 * </ul>
 *
 * <p>失败约定：参数不合法、业务上不允许时抛 BusinessException（业务异常，
 * 携带 A/B/C 开头的五位业务码，A 表示用户端问题、B 表示本系统问题、
 * C 表示第三方服务问题），而不是返回一个错误码对象。这样调用方（Controller）
 * 不需要在每个方法后判断「成功还是失败」，异常由全局异常处理器统一转成响应体。
 *
 * @author shaoshing
 */
public interface AiChatService {

    /**
     * 同步提问：把一句提问交给大模型，拿到完整回答后一次性返回，并把这一轮对话存进库
     *
     * <p>为什么这样设计：本方法最容易写错的地方是「事务边界」，这里给出三条取舍。
     * 第一，整个方法不能标 {@code @Transactional}——模型生成一段回答可能要几十秒，
     * 事务一旦把它包住，数据库连接就被占着几十秒，并发稍高就会把连接池耗尽；
     * 所以只有「回答落库」这一小段用 TransactionTemplate（编程式事务模板）手动开事务。
     * 第二，用户消息必须在调用模型之前落库：反过来的话模型一失败，用户的提问就丢了——
     * 用户明明问了，历史里却什么都没有，而前端往往已经把它渲染在界面上了。
     * 第三，配额预检排在检索之前：检索要先把问题送去 Embedding（把文本变成一串数字向量，
     * 供相似度计算使用，是一次按文本量计费的外部 HTTP 调用），超限的请求不该产生这笔开销。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验登录：AI 咨询不向游客开放，未登录直接 A0201。
     *     放在最前面，是为了让后面的会话解析、配额查询、Embedding 都不必为「没有身份」写分支；</li>
     *     <li>② 准备：校验参数 → 定位或新建会话 → 配额预检 → 取上下文窗口。
     *     会话解析必须早于配额预检，因为配额是「按用户」算的，而新建会话会把当前用户写进去，
     *     后面的计数也要用同一个用户 ID；</li>
     *     <li>③ 用户消息落库：先落库、再调模型（理由见上）；</li>
     *     <li>④ 检索资料：把问题向量化后在知识库中找最相关的几块。
     *     必须排在配额预检之后（超限请求不该产生 Embedding 开销），且必须排在模型调用之前
     *     （资料要拼进本次提问一起送进去）；检索失败降级为「无资料」，不阻断提问；</li>
     *     <li>⑤ 调用大模型：一次同步阻塞的外部 HTTP 调用，刻意留在数据库事务之外；</li>
     *     <li>⑥ 事务内落回答：assistant 消息与会话活跃时间必须同生共死，
     *     否则会出现「有回答但会话列表不更新」或「会话更新了但回答没存」；</li>
     *     <li>⑦ 事务外计数：配额 +1 走 Redis 的自增（INCR），
     *     它是外部系统，写它不属于数据库事务——事务回滚了，Redis 里的 +1 也撤不回来。</li>
     * </ol>
     *
     * <p>容易踩的坑：上下文窗口必须在第 ③ 步落库之前取。若先落用户消息再取历史，
     * 本次提问就会作为「最近一条历史」再被送进模型一遍，模型会看到两个一模一样的问题。
     * 另外，④ 一旦挪到 ② 之前，超限用户每次提问都会白烧一次 Embedding。
     *
     * @param request 提问请求：message 必填且不超过 500 字；sessionId 可空，
     *                为空表示新建会话。整个请求体为 null 时得到 A0401，不会抛空指针
     * @return 会话 ID（新建的或原会话的）、回答正文、本次引用到的资料列表；
     *         没有命中任何资料时 sources 是空列表而不是 null，调用方无需判空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     * 会话不存在或无权访问（A0402）、提问次数超限（A0501）、AI 调用失败（C0200）时抛出
     */
    AiChatResponseVO chat(AiChatRequest request);

    /**
     * 查询会话历史：取最近 50 条消息，按时间正序（旧 → 新）返回
     *
     * <p>为什么这样设计：只取最近 50 条而不是全部，是因为会话会随对话轮次无限增长，
     * 一次全量返回会让前端一打开历史页就拉回整个会话（匿名接口尤其危险）；
     * 返回时间正序而不是倒序，是因为对话界面自上而下就是「旧 → 新」，
     * 数据库按倒序取完在内存里翻正一次，比让前端每次再 reverse 一次更可靠。
     * 归属校验与提问完全一致——否则这个接口就成了「探测某会话是否存在」的入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 解析会话 ID：空、非数字、小于等于 0 都按 A0401 处理。
     *     这一步必须最先做，脏 ID 若留到后面会变成一次无意义的数据库查询；</li>
     *     <li>② 取登录用户：未登录直接 A0201。AI 咨询不向游客开放，
     *     因此这里不接受「游客也能查」的分支；</li>
     *     <li>③ 校验会话可访问：不存在与无权访问统一 A0402（理由见接口注释）。
     *     必须排在查询消息之前，否则会先查出数据再发现没权限，白白多查一次；</li>
     *     <li>④ 倒序取最近 50 条再翻正：SQL 里必须 desc + limit，
     *     正序取 50 条取到的是最早的 50 条，长会话里会看不到最新几条。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序键必须用雪花 ID 而不是 created_at。created_at 是秒级精度，
     * 同一秒内写入的 user / assistant 两条消息时间完全相同，只按时间排序时
     * 数据库返回的先后顺序不稳定，表现为「对话记录偶发颠倒」。
     *
     * @param sessionId 会话 ID（必填，来自查询参数）；为空或非法时得到 A0401
     * @return 消息列表（旧 → 新）；会话存在但一条消息都没有时返回空列表，不会是 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（A0401）、
     * 会话不存在或无权访问（A0402）、未登录（A0201）时抛出
     */
    List<AiMessageVO> listHistory(String sessionId);

    /**
     * 分页查询「我的会话列表」：按最近活跃倒序，并带上每个会话的消息条数
     *
     * <p>为什么这样设计：用户维度必须由服务端从登录态取，请求里根本没有 userId 参数——
     * 一旦允许前端传 userId，「看别人的会话列表」就成了一个功能。
     * 分页里的 messageCount 用一次批量统计得出（一条
     * {@code WHERE session_id IN (本页) GROUP BY session_id}），
     * 而不是在循环里逐个会话 count——后者是最典型的 N+1 查询
     * （先查 1 次列表、再为每行各查 1 次，一页 20 条就变成 21 次查询）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 取登录用户：未登录 A0201。会话列表天然是「我的」，游客没有这个维度；</li>
     *     <li>② 归一化分页参数：null / 小于 1 一律按第一页 10 条处理，并把每页条数收敛到上限 50。
     *     不收敛上限的话，一个 pageSize=100000 就能把整张表读进内存；</li>
     *     <li>③ 按 user_id 等值 + updated_at 倒序 + id 倒序查会话分页。
     *     user_id 条件不能省：少了它就是把所有人的会话都列出来；</li>
     *     <li>④ 批量补齐 messageCount 后转 VO：一次 IN 查询覆盖整页，
     *     没有消息的会话补 0（GROUP BY 不会返回「0 条」的行）。</li>
     * </ol>
     *
     * <p>容易踩的坑：次级排序键必须是 id。updated_at 只有秒级精度，同一秒内活跃过的
     * 多个会话会并列；没有稳定键时翻页会出现「同一条会话在两页里都出现，或者两页都没有」。
     * 另外空页要提前返回，拿空集合去拼 {@code IN ()} 是 SQL 语法错误。
     *
     * @param request 分页参数（current / pageSize），允许为 null，此时按第一页 10 条处理
     * @return 会话分页结果；一页都没有数据时 records 是空列表，total 为 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
     */
    Page<AiSessionVO> listSessions(AiSessionQueryRequest request);

    /**
     * 删除会话及其全部消息（父子两张表一起逻辑删除）
     *
     * <p>为什么这样设计：对话是用户资产，误删需要可追溯，所以父子两张表都用逻辑删除
     * （不执行 DELETE，而是把 deleted_at 写成毫秒时间戳，查询时自动过滤掉非 0 的行），
     * 而不是物理删除。两张表的写必须在同一个事务内：先删子表（消息）再删父表（会话），
     * 顺序反过来一旦子表删除失败，就会留下「会话没了、消息还在」的孤儿数据。
     * 归属校验与提问共用同一套规则，因此重复删除第二次会得到 A0402——
     * 逻辑删除后按 ID 查不到，与「不存在」走同一条路径，这是刻意的。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 解析会话 ID（空 / 非数字 / 非正数 → A0401）；</li>
     *     <li>② 取登录用户并校验会话可访问：不存在与无权访问统一 A0402。
     *     必须排在删除之前，否则「删别人的会话」会先删成功再报错；</li>
     *     <li>③ 事务内先删消息、再删会话：两条 UPDATE 要么都成功、要么都回滚，
     *     这样才不存在「会话已删、消息还在」的中间状态。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要用「先查消息条数、再决定删不删」的写法。这里删除的语义是
     * 「把会话和它的全部消息一起标记为已删除」，不需要逐条确认消息存在；
     * 多一次 count 查询只会让删除变慢，还会引入「查询与删除之间消息又变多」的竞态。
     *
     * @param sessionId 会话 ID（必填）；为空或非法时得到 A0401
     * @return 恒为 true。方法没有「删除失败返回 false」的分支——
     *         删除不成功一律抛异常，调用方不必判返回值
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（A0401）、
     * 会话不存在或无权访问（A0402）、未登录（A0201）、数据库影响行数不为 1（B0002）时抛出
     */
    boolean deleteSession(String sessionId);

    /**
     * SSE 流式提问：回答边生成边推给前端，而不是等全部生成完再一次性返回
     *
     * <p>为什么这样设计：SSE（Server-Sent Events，服务器推送事件）是一种
     * 「服务端保持一个 HTTP 连接、持续往里写事件帧」的传输方式，浏览器可以逐段读取。
     * 它天生不适合套统一响应体：{@code {code,data,message}} 是一次性 JSON，
     * 套上之后前端只能等整个 JSON 结束才能渲染，流式就失去了意义。
     * 因此这是全项目唯一不返回 BaseResponse 的接口，代价是所有失败
     * （参数错误、会话不存在、配额超限、AI 失败）都以 error 事件返回，
     * HTTP 状态码恒为 200，业务码放在事件负载里——前端必须监听 error 事件，
     * 不能再依赖「非 0 code 就报错」的统一拦截逻辑。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① meta：先告知本次的会话 ID。新建会话时前端只能从这里拿到 ID，
     *     所以它必须是第一个事件；</li>
     *     <li>② 用户消息落库：与同步接口同一约定，先落库再调模型，失败时提问不丢；</li>
     *     <li>③ 检索资料：位置与降级策略都与同步接口一致（失败即当作无资料）；</li>
     *     <li>④ sources：恒发，没有命中就发空数组。若只在命中时才发，
     *     前端就得靠「等一段时间没收到」来判断没有资料——那是竞态；</li>
     *     <li>⑤ delta：每收到一段增量就推一个事件，可能多次；</li>
     *     <li>⑥ done：回答落库并提交后再发，负载里带 assistant 消息 ID；</li>
     *     <li>⑦ error：替代 done 结束序列，负载里带业务码与提示。</li>
     * </ol>
     *
     * <p>容易踩的坑：登录身份必须在请求线程里取好再传进推流逻辑。
     * Sa-Token 的登录态与 Spring 的请求上下文都存在 ThreadLocal（线程本地变量，
     * 每个线程一份副本）里，而推流跑在另一个线程上，跨线程读不到，
     * 表现为「连 meta 都发不出来，只收到一个 error 事件」。
     * 另外客户端断开后不能再落库、不能再计数：用户没拿到回答，就不该扣额度。
     *
     * @param request 提问请求：message 必填；sessionId 可空表示新建会话
     * @return SSE 发射器。方法立刻返回（让 Tomcat 释放请求线程），推流在虚拟线程中继续；
     *         调用方不要在这个对象上做业务判断，业务结果只能从事件里读
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本方法自身不抛业务异常：
     * 鉴权、参数、配额、AI 失败都发生在推流阶段，一律转成 error 事件返回
     */
    SseEmitter chatStream(AiChatRequest request);
}
