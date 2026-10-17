package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionDeleteRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionQueryRequest;
import com.bhu.runshistudioweb.model.vo.ai.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.ai.AiMessageVO;
import com.bhu.runshistudioweb.model.vo.ai.AiSessionVO;
import com.bhu.runshistudioweb.service.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 咨询接口（<b>全部要求登录</b>）
 *
 * author: shaoshing
 *
 * <p><b>访问门槛</b>：AI 咨询不向游客开放——未登录一律 A0201。
 * 登录后：普通用户限 {@code studio.ai.query-limit}（默认 5 次），成员与管理员不限次。
 * 登录校验与配额都在 Service 层（见 {@code AiChatServiceImpl}）。
 *
 * <p>本 Controller 各接口的鉴权：
 * <table border="1">
 *     <caption>接口清单</caption>
 *     <tr><th>路径</th><th>鉴权</th></tr>
 *     <tr><td>{@code /ai/chat}、{@code /ai/chat/stream}、{@code /ai/chat/history}、
 *     {@code /ai/session/delete}</td>
 *     <td>不在 Sa-Token 白名单里 → 默认要求登录（拦截器）；Service 层再兜一层</td></tr>
 *     <tr><td>{@code /ai/session/list}</td>
 *     <td>{@code @SaCheckLogin}（双保险）</td></tr>
 * </table>
 *
 * <p>本 Controller 只做接参与包装：会话归属、配额、事务边界全在 Service 里
 * （见 {@code AiChatServiceImpl} 的类注释）。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/ai/chat}。
 */
@Tag(name = "AI 咨询模块", description = "官网 AI 咨询：提问与历史记录（匿名可访问）")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * AI 提问（<b>需登录</b>）
     *
     * <p>{@code sessionId} 可空：为空即新建会话，响应里会带回新的会话 ID；
     * 前端把它存下来，下一轮带上即可续聊（不需要单独的「创建会话」接口）。
     *
     * @param aiChatRequest 提问请求（message 必填且 ≤500 字）
     * @return 会话 ID 与 AI 回答
     */
    @PostMapping("/chat")
    @Operation(summary = "AI 提问", description = "需登录；sessionId 可空（为空则新建会话），失败返回 C0200")
    public BaseResponse<AiChatResponseVO> chat(@RequestBody @Valid AiChatRequest aiChatRequest) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(aiChatRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.chat(aiChatRequest));
    }

    /**
     * 查询会话历史（<b>需登录</b>）
     *
     * <p>返回最近 50 条、时间正序（旧 → 新），与对话界面自上而下的顺序一致。
     *
     * @param sessionId 会话 ID（必填）
     * @return 消息列表；会话不存在或无权访问返回 A0402
     */
    @GetMapping("/chat/history")
    @Operation(summary = "AI 会话历史", description = "需登录；返回最近 50 条消息（时间正序）")
    public BaseResponse<List<AiMessageVO>> listHistory(@RequestParam(value = "sessionId", required = false)
                                                      String sessionId) {
        // 刻意用 required = false + 包装类型：缺参数时让 Service 返回
        // A0401「会话 id 不能为空」，而不是 Spring 抛 MissingServletRequestParameterException
        // 被全局处理器兜成 B0001「系统错误」
        return ResultUtils.success(aiChatService.listHistory(sessionId));
    }

    /**
     * AI 提问（<b>SSE 流式，需登录</b>）
     *
     * <p><b>契约例外：本接口不返回 {@code BaseResponse}</b>。SSE 的响应体是事件流，
     * 套上 {@code {code,data,message}} 会让前端无法边收边渲染。
     * 由此带来两个必须知道的后果：
     * <ol>
     *     <li><b>HTTP 状态码恒为 200</b>，包括参数错误、会话不存在、配额超限、AI 失败——
     *     它们都以 {@code error} 事件返回（负载含业务码），前端必须监听 error 事件，
     *     不能再依赖「非 0 code 就报错」那套统一拦截逻辑；</li>
     *     <li>因此这里<b>不加 {@code @Valid}</b>：加了之后 Spring 会抛
     *     MethodArgumentNotValidException，被全局异常处理器包成 application/json 的 A0401 响应，
     *     与 text/event-stream 的内容类型混在一起。校验交给 Service，
     *     失败时同样以 error 事件返回（见 AiChatService#chatStream）。</li>
     * </ol>
     *
     * <p>事件序列：{@code meta(sessionId)} → {@code delta}* → {@code done} / {@code error}。
     *
     * @param aiChatRequest 提问请求（message 必填；sessionId 可空表示新建会话）
     * @return SSE 发射器（异步推流）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 提问（SSE 流式）",
            description = "需登录；事件序列 meta → delta* → done/error，失败以 error 事件返回（HTTP 恒 200）")
    public SseEmitter chatStream(@RequestBody AiChatRequest aiChatRequest) {
        return aiChatService.chatStream(aiChatRequest);
    }

    // ==================== 会话管理 ====================

    /**
     * 我的会话列表（<b>需登录</b>，不进白名单）
     *
     * <p>用户维度由服务端从登录态取，请求里没有 userId 参数——
     * 否则「看别人的会话列表」就成了一个功能。
     *
     * <p>这里额外标 {@code @SaCheckLogin} 是<b>纵深防御</b>：本路径未进白名单，
     * 全局拦截器本来就会要求登录；一旦将来有人误把它加进白名单，
     * 注解仍能兜住——而「翻别人会话列表」这类问题一旦漏出就是数据泄露。
     *
     * @param aiSessionQueryRequest 分页参数（current / pageSize），允许为空
     * @return 会话分页结果（含 messageCount，按 updated_at 倒序）
     */
    @GetMapping("/session/list")
    @SaCheckLogin
    @Operation(summary = "我的 AI 会话列表", description = "需登录；按最近活跃倒序，含每个会话的消息条数，每页最多 50 条")
    public BaseResponse<Page<AiSessionVO>> listSessions(AiSessionQueryRequest aiSessionQueryRequest) {
        return ResultUtils.success(aiChatService.listSessions(aiSessionQueryRequest));
    }

    /**
     * 删除会话及其全部消息（<b>需登录</b>，归属规则与提问完全一致）
     *
     * <p>删除方式是「双逻辑删」：会话与消息在同一事务内各自写 {@code deleted_at}，
     * 数据可追溯。重复删除返回 A0402（第二次查不到已删除的会话）。
     *
     * @param aiSessionDeleteRequest 删除请求（sessionId 必填）
     * @return true 表示删除成功
     */
    @PostMapping("/session/delete")
    @Operation(summary = "删除 AI 会话", description = "需登录；同事务逻辑删除会话与全部消息，重复删除返回 A0402")
    public BaseResponse<Boolean> deleteSession(@RequestBody @Valid AiSessionDeleteRequest aiSessionDeleteRequest) {
        ThrowUtils.throwIf(aiSessionDeleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.deleteSession(aiSessionDeleteRequest.getSessionId()));
    }
}
