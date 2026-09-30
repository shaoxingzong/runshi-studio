package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.vo.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;

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
}
