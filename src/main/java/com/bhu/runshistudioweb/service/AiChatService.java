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
 * 会话归属规则（五个方法共用）：user_id 非空时只有创建它的登录用户能访问；
 * user_id 为空（匿名会话）时凭雪花 ID 即可访问，ID 无法枚举，知道 ID 等价于持有凭据。
 * 会话不存在与无权访问统一抛 A0402 并用同一句提示——分开提示等于送出「哪些会话真实存在」的探测器。
 *
 * 提问是全项目唯一同时依赖数据库与外部 AI 服务的链路：先做 RAG 检索，再调用模型生成回答。
 *
 * @author shaoshing
 */
public interface AiChatService {

    /**
     * 同步提问：检索知识库 → 调模型 → 落库本轮对话，一次性返回完整回答
     *
     * 事务边界有三条硬约束：整个方法不能标 {@code @Transactional}（模型生成几十秒，
     * 连接会被占满，只有落库那一小段用 TransactionTemplate）；用户消息必须在调用模型前落库，
     * 否则模型失败时提问就丢了；配额预检必须排在检索之前，超限请求不该产生 Embedding 开销。
     * 配额 +1 走 Redis 自增，放在事务外——事务回滚撤不回外部系统的写入。
     *
     * 坑：上下文窗口要在用户消息落库之前取，否则本次提问会作为「最近一条历史」再送模型一遍。
     *
     * @param request 提问请求：message 必填且不超过 500 字；sessionId 可空表示新建会话；
     *                整个请求体为 null 时得到 A0401
     * @return 会话 ID、回答正文、本次引用的资料列表；无命中时 sources 是空列表而不是 null
     * @throws BusinessException 参数不合法（A0401）、会话不存在或无权访问（A0402）、
     *         提问次数超限（A0501）、AI 调用失败（C0200）
     */
    AiChatResponseVO chat(AiChatRequest request);

    /**
     * 查询会话历史：最近 50 条消息，按时间正序（旧 → 新）
     *
     * 只取最近 50 条是因为会话会无限增长，匿名接口全量返回尤其危险。
     *
     * 坑：排序键必须用雪花 ID 而不是 created_at。后者是秒级精度，同一秒写入的
     * user / assistant 两条消息时间相同，顺序不稳定，表现为对话记录偶发颠倒。
     *
     * @param sessionId 会话 ID，必填；空或非法时得到 A0401
     * @return 消息列表（旧 → 新）；会话存在但没有消息时返回空列表，不是 null
     * @throws BusinessException id 缺失或非法（A0401）、会话不存在或无权访问（A0402）、未登录（A0201）
     */
    List<AiMessageVO> listHistory(String sessionId);

    /**
     * 分页查询「我的会话列表」：按最近活跃倒序，附带每个会话的消息条数
     *
     * 用户维度由服务端从登录态取，请求里没有 userId 参数——允许前端传就成了「看别人的会话列表」功能。
     * messageCount 用一次批量统计得出，不在循环里逐条 count。
     *
     * 坑：次级排序键必须是 id。updated_at 只有秒级精度，同秒活跃的会话会并列，
     * 缺稳定键时翻页会重复或漏条；空页要提前返回，拿空集合拼 {@code IN ()} 是语法错误。
     *
     * @param request 分页参数，允许为 null，此时按第一页 10 条处理
     * @return 会话分页结果；无数据时 records 为空列表、total 为 0
     * @throws BusinessException 未登录时抛出（A0201）
     */
    Page<AiSessionVO> listSessions(AiSessionQueryRequest request);

    /**
     * 删除会话及其全部消息（父子两张表一起逻辑删除）
     *
     * 两张表的写必须在同一事务内，且先删子表再删父表——顺序反过来一旦子表失败，
     * 就会留下「会话没了、消息还在」的孤儿数据。归属校验与提问共用同一套规则，
     * 因此重复删除第二次会得到 A0402，这是刻意的。
     *
     * @param sessionId 会话 ID，必填；空或非法时得到 A0401
     * @return 恒为 true；删除不成功一律抛异常，没有「返回 false 表示失败」的分支
     * @throws BusinessException id 缺失或非法（A0401）、会话不存在或无权访问（A0402）、
     *         未登录（A0201）、影响行数不为 1（B0002）
     */
    boolean deleteSession(String sessionId);

    /**
     * SSE 流式提问：回答边生成边推给前端
     *
     * SSE 不适合套统一响应体，套上之后前端只能等整个 JSON 结束才能渲染。
     * 因此这是全项目唯一不返回 BaseResponse 的接口：所有失败（参数、会话、配额、AI）
     * 都以 error 事件返回，HTTP 状态码恒为 200，业务码放在事件负载里。
     * 前端必须监听 error 事件，不能再依赖「非 0 code 就报错」的统一拦截。
     *
     * 事件顺序：meta（会话 ID）→ sources（恒发，无命中发空数组）→ delta（可多次）→ done / error。
     *
     * 坑：登录身份必须在请求线程里取好再传进推流逻辑。Sa-Token 与 Spring 的请求上下文都在
     * ThreadLocal 里，推流跑在另一个线程上读不到，表现为连 meta 都发不出来。
     * 客户端断开后不能再落库、不能再计数——用户没拿到回答就不该扣额度。
     *
     * @param request 提问请求：message 必填；sessionId 可空表示新建会话
     * @return SSE 发射器。方法立刻返回，推流在虚拟线程中继续；
     *         业务结果只能从事件里读，不要在这个对象上做判断
     * @throws BusinessException 本方法自身不抛：鉴权、参数、配额、AI 失败一律转成 error 事件
     */
    SseEmitter chatStream(AiChatRequest request);
}
