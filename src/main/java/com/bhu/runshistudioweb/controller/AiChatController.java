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
     * @param aiChatRequest message 必填且不超过 500 字；sessionId 为空表示新建会话
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
     * @param sessionId 会话 ID，缺失时由 Service 报 A0401
     * @return 消息列表（旧 → 新），没有消息时为空列表
     */
    @GetMapping("/chat/history")
    @Operation(summary = "AI 会话历史", description = "需登录；返回最近 50 条消息（时间正序）")
    public BaseResponse<List<AiMessageVO>> listHistory(@RequestParam(value = "sessionId", required = false)
                                                      String sessionId) {
        return ResultUtils.success(aiChatService.listHistory(sessionId));
    }

    /**
     * AI 提问（SSE 流式）：回答边生成边推送
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

    // 会话管理

    /**
     * 我的会话列表（分页）
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
