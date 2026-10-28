package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.ai.AiComposeMetaRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiComposePolishRequest;
import com.bhu.runshistudioweb.model.vo.ai.AiComposeMetaVO;
import com.bhu.runshistudioweb.service.AiComposeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 写作助手接口：发帖页的「润色」与「标题/摘要生成」
 *
 * <p>为什么单独一个 Controller 而不是塞进 {@code AiChatController}：两者的准入门槛不同——
 * AI 咨询对登录用户开放，写作助手只对工作室成员开放。放在同一个类里，
 * 后来者很容易把「已登录」当成「可以写作」。
 *
 * <p>润色是 SSE：HTTP 状态恒为 200，鉴权与参数错误也以 error 事件返回——
 * 响应头一旦发出就无法再改状态码。
 *
 * @author shaoshing
 */
@Tag(name = "AI 写作助手", description = "发帖页 AI 润色与标题摘要生成（仅工作室成员）")
@RestController
@RequestMapping("/ai/compose")
@RequiredArgsConstructor
public class AiComposeController {

    private final AiComposeService aiComposeService;

    /**
     * 润色选中片段（SSE 流式）
     *
     * <p>刻意不加 {@code @Valid} 也不在此处判空：SSE 的响应头一发出就无法改状态码，
     * 参数与权限问题统一交给 Service 以 error 事件表达，前端才能用一种方式处理失败。
     *
     * @param request 待润色片段，可为 null（会以 error 事件返回 A0401）
     * @return SSE 发射器，推流在 Service 的虚拟线程中进行
     */
    @PostMapping(value = "/polish", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 润色选中片段（SSE 流式）",
            description = "仅工作室成员；事件序列 delta* → done/error，HTTP 恒 200")
    public SseEmitter polish(@RequestBody(required = false) AiComposePolishRequest request) {
        return aiComposeService.polishStream(request);
    }

    /**
     * 根据正文生成标题与摘要
     *
     * <p>用 {@code required = false} + 方法内判空：缺 body 时得到统一的 A0401，
     * 而不是 Spring 抛 {@code HttpMessageNotReadableException} 被全局处理器兜成 B0001。
     *
     * @param request 正文与可选原标题，可为 null
     * @return 生成的标题与摘要
     */
    @PostMapping("/meta")
    @Operation(summary = "AI 生成标题与摘要", description = "仅工作室成员；失败返回 C0200")
    public BaseResponse<AiComposeMetaVO> meta(
            @Valid @RequestBody(required = false) AiComposeMetaRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiComposeService.generateMeta(request));
    }
}
