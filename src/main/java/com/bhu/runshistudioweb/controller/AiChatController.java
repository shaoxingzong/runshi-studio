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
 * AI 咨询接口（HTTP 入口，全部要求登录）
 *
 * 鉴权分工：/ai/chat、/chat/stream、/chat/history、/session/delete 不在白名单，由全局拦截器
 * 要求登录、Service 再兜一层；/ai/session/list 额外标 {@code @SaCheckLogin} 作为纵深防御。
 *
 * 登录后普通用户受 studio.ai.query-limit（默认 5 次）约束，成员与管理员不限次也不计数。
 *
 * @author shaoshing
 */
@Tag(name = "AI 咨询模块", description = "官网 AI 咨询：提问与历史记录（匿名可访问）")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * AI 提问（同步）：一次返回完整回答
     *
     * sessionId 可空，为空即新建会话并在响应里带回新 ID，因此不需要单独的「创建会话」接口——
     * 少一个接口就少一次「会话建好了但提问失败」的空会话垃圾。
     *
     * @param aiChatRequest message 必填且不超过 500 字；sessionId 为空表示新建会话
     * @return 会话 ID、回答正文与本次引用的资料列表
     */
    @PostMapping("/chat")
    @Operation(summary = "AI 提问", description = "需登录；sessionId 可空（为空则新建会话），失败返回 C0200")
    public BaseResponse<AiChatResponseVO> chat(@RequestBody @Valid AiChatRequest aiChatRequest) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(aiChatRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.chat(aiChatRequest));
    }

    /**
     * 查询会话历史（一次返回最近 50 条）
     *
     * 上限固定 50：会话随对话轮次无限增长，全量返回会让前端一打开历史页就拉回整个会话。
     * required = false 是为了让缺参落到 Service 报 A0401；写成 true 会被兜成 B0001「系统错误」。
     *
     * @param sessionId 会话 ID，缺失时由 Service 报 A0401
     * @return 消息列表（旧 → 新），没有消息时为空列表
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
     * AI 提问（SSE 流式）：回答边生成边推送
     *
     * 全项目唯一不返回统一响应体的接口：事件流套上 {@code {code,data,message}} 就无法边收边渲染，
     * 因此 HTTP 状态恒为 200、业务码在事件负载里；不加 {@code @Valid}，校验交给 Service。
     *
     * @param aiChatRequest message 必填；sessionId 为空表示新建会话
     * @return SSE 发射器，方法立即返回，推流在 Service 的虚拟线程里进行
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 提问（SSE 流式）",
            description = "需登录；事件序列 meta → delta* → done/error，失败以 error 事件返回（HTTP 恒 200）")
    public SseEmitter chatStream(@RequestBody AiChatRequest aiChatRequest) {
        return aiChatService.chatStream(aiChatRequest);
    }

    // ==================== 会话管理 ====================

    /**
     * 我的会话列表（分页）
     *
     * 用户维度由服务端从登录态取，请求里没有 userId，否则「看别人的会话列表」就成了功能。
     * 额外标 {@code @SaCheckLogin} 是纵深防御：本路径未进白名单，万一被加进去注解仍能兜住。
     *
     * @param aiSessionQueryRequest 分页参数，允许为 null 或字段全空
     * @return 会话分页结果，按最近活跃倒序，含每个会话的消息条数
     */
    @GetMapping("/session/list")
    @SaCheckLogin
    @Operation(summary = "我的 AI 会话列表", description = "需登录；按最近活跃倒序，含每个会话的消息条数，每页最多 50 条")
    public BaseResponse<Page<AiSessionVO>> listSessions(AiSessionQueryRequest aiSessionQueryRequest) {
        return ResultUtils.success(aiChatService.listSessions(aiSessionQueryRequest));
    }

    /**
     * 删除会话及其全部消息
     *
     * 会话与消息在同一事务内各自标记 deleted_at，数据可追溯。重复删除会得到 A0402：
     * 第一次删除后按 ID 查不到，与「不存在」走同一条路径。
     *
     * @param aiSessionDeleteRequest sessionId 必填，整个对象为 null 时得到 A0401
     * @return 恒为 true，删除失败一律抛异常
     */
    @PostMapping("/session/delete")
    @Operation(summary = "删除 AI 会话", description = "需登录；同事务逻辑删除会话与全部消息，重复删除返回 A0402")
    public BaseResponse<Boolean> deleteSession(@RequestBody @Valid AiSessionDeleteRequest aiSessionDeleteRequest) {
        ThrowUtils.throwIf(aiSessionDeleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.deleteSession(aiSessionDeleteRequest.getSessionId()));
    }
}
