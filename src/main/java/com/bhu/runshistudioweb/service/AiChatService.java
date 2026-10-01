package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionQueryRequest;
import com.bhu.runshistudioweb.model.vo.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
import com.bhu.runshistudioweb.model.vo.AiSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 咨询服务接口
 *
 * author: shaoshing
 *
 * <p>两个方法对应两个匿名接口：提问与历史。二者共用同一套<b>会话归属规则</b>——
 * 这是本模块的安全核心：
 * <ul>
 *     <li>会话存在 {@code user_id}（登录用户创建）→ <b>仅本人可访问</b>；</li>
 *     <li>会话 {@code user_id} 为 null（游客创建）→ 凭不可枚举的雪花 ID 可访问
 *     （知道 ID 等价于持有凭据，因为 19 位 ID 无法被枚举）；</li>
 *     <li>不存在 / 无权访问 → <b>统一 40400、同一句提示</b>。
 *     把这两种情况合成一个响应是刻意的：分开提示等于给攻击者一个
 *     「哪些会话 ID 真实存在」的探测器。</li>
 * </ul>
 *
 * <p>约定与其它模块一致：参数不合法 / 业务不允许时抛 {@code BusinessException}，不返回错误码。
 */
public interface AiChatService {

    /**
     * 提问（匿名可用）
     *
     * <p>流程：定位或新建会话 → 配额预检 → 取上下文窗口 → 落用户消息 → 调 AI → 成功后一步写。
     *
     * <p><b>事务边界（本方法最容易被写错的地方）</b>：
     * <ul>
     *     <li>调用 AI 的 HTTP 请求<b>绝不能</b>包在数据库事务里——模型生成可能几十秒，
     *     包进事务会让数据库连接被长时间占用，并发稍高就把连接池耗尽；</li>
     *     <li>HTTP 失败时<b>保留用户消息</b>（先落库、后调用），只返回 50001，
     *     用户刷新历史仍能看到自己问过什么；</li>
     *     <li>HTTP 成功后的「assistant 消息 + 会话 updated_at + 计数」三处写，
     *     用 {@code TransactionTemplate} 包成一步——它们必须同生共死，
     *     否则会出现「有回答但配额没加」或「配额加了但没存回答」的对不上账。</li>
     * </ul>
     *
     * @param request 提问请求（message 必填；sessionId 可空表示新建会话）
     * @return 会话 ID（新建的或原会话的）与 AI 回答
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（40000）、
     *         会话不存在或无权访问（40400）、提问次数超限（42900）、AI 调用失败（50001）时抛出
     */
    AiChatResponseVO chat(AiChatRequest request);

    /**
     * 查询会话历史（匿名可用）
     *
     * <p>返回<b>最近 50 条</b>、<b>时间正序</b>（旧 → 新，与对话界面自上而下一致）。
     * 归属校验与 {@link #chat} 完全相同：越权与不存在同样返回 40400。
     *
     * @param sessionId 会话 ID（必填）
     * @return 消息列表；会话没有消息时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（40000）、
     *         会话不存在或无权访问（40400）时抛出
     */
    List<AiMessageVO> listHistory(String sessionId);

    /**
     * 查询当前登录用户的会话分页列表（<b>需登录</b>）
     *
     * <p>用户维度由服务端从登录态取，<b>不接受前端传 userId</b>——
     * 否则就成了「可以翻别人会话列表」的功能。
     *
     * <p>排序固定为 {@code updated_at 倒序 → id 倒序}：
     * 后者是稳定键，因为 {@code updated_at} 是秒级精度，同一秒内活跃过的会话会并列。
     *
     * <p>分页中的 {@code messageCount} 用<b>一次批量统计</b>得出
     * （{@code WHERE session_id IN (本页) GROUP BY session_id}），
     * 绝不是「循环里逐个 count」——那会让一页 20 条变成 21 次查询。
     *
     * @param request 分页参数（允许为 null，按第一页 10 条处理）
     * @return 会话分页结果，记录为 {@link AiSessionVO}
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（40100）
     */
    Page<AiSessionVO> listSessions(AiSessionQueryRequest request);

    /**
     * 删除会话及其全部消息（<b>匿名可用</b>，归属规则与 {@link #chat} 完全一致）
     *
     * <p>删除方式是「<b>双逻辑删</b>」：父表（会话）与子表（消息）都执行
     * {@code UPDATE ... SET deleted_at = 毫秒时间戳}，两者在<b>同一事务</b>内完成。
     *
     * <p>注意与/19 的关联表清理对比：那两张关联表是<b>物理删除</b>
     * （关系解除即无业务意义），而这里父子两张表都是<b>逻辑删除</b>
     * （对话是用户资产，误删需要可追溯）。两种机制的选择理由见 db/DESIGN.md 2.2。
     *
     * @param sessionId 会话 ID（必填）
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（40000）、
     *         会话不存在或无权访问（40400）时抛出
     */
    boolean deleteSession(String sessionId);

    /**
     * SSE 流式提问（<b>匿名可用</b>，鉴权与归属规则与 {@link #chat} 完全一致）
     *
     * <p><b>契约例外</b>：本方法对应的接口<b>不返回 {@code BaseResponse}</b>——
     * SSE 响应体是事件流，不是一次性 JSON，套上 {@code {code,data,message}} 会让前端无法边收边渲染。
     * 代价是所有失败（含参数错误、会话不存在、配额超限、AI 失败）都以 {@code error} <b>事件</b>返回，
     * HTTP 状态码恒为 200，业务码在事件负载里。
     *
     * <p><b>事件序列</b>：{@code meta(sessionId)} → {@code delta}* → {@code done} / {@code error}
     * <ul>
     *     <li>{@code meta}：{@code {"sessionId":"<会话ID>"}}，新建会话时前端只能从这里拿到 ID；</li>
     *     <li>{@code delta}：{@code {"delta":"<增量文本>"}}，可能多次；</li>
     *     <li>{@code done}：{@code {"messageId":"<回答消息ID>"}}，表示回答已<b>落库提交</b>；</li>
     *     <li>{@code error}：{@code {"code":<业务码>,"message":"<提示>"}}，替代 done 结束序列。</li>
     * </ul>
     *
     * <p><b>落库与计数</b>：用户消息在开始推流前先落库；回答在<b>流结束后</b>与
     * 「会话 updated_at + 配额计数」在同一事务内一次写入（与 {@link #chat} 共用实现）。
     * 失败或客户端中断时<b>只保留用户消息</b>，不写回答、不计数。
     *
     * @param request 提问请求（message 必填；sessionId 可空表示新建会话）
     * @return SSE 发射器（方法立即返回，推流在虚拟线程中进行）
     */
    SseEmitter chatStream(AiChatRequest request);
}
