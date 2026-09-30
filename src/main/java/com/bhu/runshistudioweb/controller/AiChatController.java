package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.vo.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.AiMessageVO;
import com.bhu.runshistudioweb.service.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 咨询接口（<b>两个接口均匿名可访问</b>）
 *
 * author: shaoshing
 *
 * <p>为什么匿名开放：官网的 AI 咨询是「不需要注册也能试用」的能力——
 * 这是产品定位（拉新入口），也是 DESIGN 的既定口径。
 * 代价是必须防滥用，本模块用两条措施兜住：
 * <ul>
 *     <li><b>会话凭不可枚举的雪花 ID 访问</b>：游客能续聊，但无法遍历别人的会话；</li>
 *     <li><b>登录用户有提问次数上限</b>（超限返回 42900）。
 *     游客的 IP 级限流属于后续工程化任务，本期不做，已在 DESIGN 登记。</li>
 * </ul>
 *
 * <p>白名单登记的是<b>两条精确路径</b>（见 SaTokenMvcConfig）：
 * {@code /ai/chat} 与 {@code /ai/chat/history}。绝不能写成 {@code /ai/**}——
 * 将来若新增「会话列表」「管理端 AI 配置」等接口，会被这条通配一起放行。
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
public class AiChatController {

    @Resource
    private AiChatService aiChatService;

    /**
     * AI 提问（匿名可用）
     *
     * <p>{@code sessionId} 可空：为空即新建会话，响应里会带回新的会话 ID；
     * 前端把它存下来，下一轮带上即可续聊（不需要单独的「创建会话」接口）。
     *
     * @param aiChatRequest 提问请求（message 必填且 ≤500 字）
     * @return 会话 ID 与 AI 回答
     */
    @PostMapping("/chat")
    @Operation(summary = "AI 提问", description = "匿名可访问；sessionId 可空（为空则新建会话），失败返回 50001")
    public BaseResponse<AiChatResponseVO> chat(@RequestBody @Valid AiChatRequest aiChatRequest) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(aiChatRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.chat(aiChatRequest));
    }

    /**
     * 查询会话历史（匿名可用）
     *
     * <p>返回最近 50 条、时间正序（旧 → 新），与对话界面自上而下的顺序一致。
     *
     * @param sessionId 会话 ID（必填）
     * @return 消息列表；会话不存在或无权访问返回 40400
     */
    @GetMapping("/chat/history")
    @Operation(summary = "AI 会话历史", description = "匿名可访问；返回最近 50 条消息（时间正序）")
    public BaseResponse<List<AiMessageVO>> listHistory(@RequestParam(value = "sessionId", required = false)
                                                      String sessionId) {
        // 刻意用 required = false + 包装类型：缺参数时让 Service 返回
        // 40000「会话 id 不能为空」，而不是 Spring 抛 MissingServletRequestParameterException
        // 被全局处理器兜成 50000「系统错误」
        return ResultUtils.success(aiChatService.listHistory(sessionId));
    }
}
