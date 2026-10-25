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
 * <p>本类只做三件事：接参、调 Service、包响应。会话归属、配额、事务边界、
 * 检索与模型调用全部在 Service 层（见 AiChatServiceImpl），
 * 因此读本类时不必关心「回答怎么来的」，只需要看「参数从哪来、结果怎么包」。
 *
 * <p>访问门槛：AI 咨询不向游客开放，未登录一律 A0201。登录后普通用户受
 * studio.ai.query-limit（默认 5 次）约束，成员与管理员不限次也不计数。
 * 具体鉴权分工如下：
 * <ul>
 *     <li>/ai/chat、/ai/chat/stream、/ai/chat/history、/ai/session/delete：
 *     不在 Sa-Token 白名单里，全局拦截器默认要求登录，Service 层再兜一层；</li>
 *     <li>/ai/session/list：额外标 {@code @SaCheckLogin}，属于双保险。</li>
 * </ul>
 *
 * <p>接口地址前缀来自 server.servlet.context-path=/api，完整路径形如
 * http://localhost:8080/api/ai/chat。
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
     * <p>为什么这样设计：sessionId 允许为空，为空即新建会话，响应里带回新的会话 ID，
     * 前端把它存下来、下一轮带上即可续聊——因此不需要单独一个「创建会话」接口。
     * 少一个接口就少一次「会话建好了但提问失败了」的空会话垃圾。
     * 本方法不加事务：事务边界在 Service 里，Controller 只负责把请求转过去。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空：整个请求体为 null 时 {@code @Valid} 不会触发，
     *     后面取值会抛空指针被兜成 B0001，这里提前给出 A0401；</li>
     *     <li>② 调 Service 并包成统一响应体。异常（A0401 / A0402 / A0501 / C0200）
     *     直接往外抛，由全局异常处理器转成响应，这里不做 try-catch。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code @Valid} 只校验字段级约束（非空、长度），
     * 它管不到「请求体本身是不是 null」。少了第 ① 步，一个空 body 的请求
     * 会变成 500 而不是 400 语义的 A0401。
     *
     * @param aiChatRequest 提问请求：message 必填且不超过 500 字；
     *                      sessionId 可空（为空即新建会话）
     * @return 统一响应体，data 里是会话 ID、回答正文与本次引用的资料列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     * 会话不存在或无权访问（A0402）、提问次数超限（A0501）、AI 调用失败（C0200）时抛出
     */
    @PostMapping("/chat")
    @Operation(summary = "AI 提问", description = "需登录；sessionId 可空（为空则新建会话），失败返回 C0200")
    public BaseResponse<AiChatResponseVO> chat(@RequestBody @Valid AiChatRequest aiChatRequest) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(aiChatRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.chat(aiChatRequest));
    }

    /**
     * 查询会话历史（同步接口，一次返回最近 50 条）
     *
     * <p>为什么这样设计：返回时间正序（旧 → 新），与对话界面自上而下的顺序一致，
     * 前端拿到即可直接渲染，不需要再排一次序。上限固定 50 条：会话会随对话轮次
     * 无限增长，一次全量返回会让前端一打开历史页就拉回整个会话。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 从查询参数取 sessionId。刻意声明 required = false，
     *     让「没传参数」也进到 Service 里；</li>
     *     <li>② 调 Service 并把列表包成统一响应体。越权与不存在都得到 A0402，
     *     两种情况的提示完全相同，不暴露会话是否存在。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的 required = false 不是「参数可选」，而是「参数缺失的报错
     * 交给 Service 统一处理」。若写成 required = true，缺参数时 Spring 会抛
     * MissingServletRequestParameterException，被全局处理器兜成 B0001「系统错误」——
     * 明明是用户少传了一个参数，却报成后端故障。
     *
     * @param sessionId 会话 ID（必填，但用包装类型接收，缺失时由 Service 报 A0401）
     * @return 统一响应体，data 里是消息列表（旧 → 新）；没有消息时是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 会话 id 缺失或非法（A0401）、
     * 会话不存在或无权访问（A0402）、未登录（A0201）时抛出
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
     * <p>为什么这样设计：本接口是全项目唯一不返回统一响应体的地方。
     * SSE 的响应体是事件流而不是一次性 JSON，套上 {@code {code,data,message}}
     * 会让前端无法边收边渲染。由此带来两个必须知道的后果：
     * 第一，HTTP 状态码恒为 200——参数错误、会话不存在、配额超限、AI 失败
     * 都以 error 事件返回，业务码在事件负载里，前端必须监听 error 事件，
     * 不能再依赖「非 0 code 就报错」那套统一拦截逻辑；
     * 第二，这里刻意不加 {@code @Valid}：加了之后 Spring 会抛
     * MethodArgumentNotValidException，被全局异常处理器包成 application/json 的 A0401 响应，
     * 与 text/event-stream 的内容类型混在一起。校验交给 Service，
     * 失败时同样以 error 事件返回。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接把请求交给 Service，拿回一个发射器就立刻返回。
     *     方法不等待回答生成完成——那样会把这个 HTTP 工作线程钉住几十秒；</li>
     *     <li>② 真正的推流在 Service 的虚拟线程里进行，事件序列是
     *     meta → sources → delta* → done / error。前端按事件名分派即可。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code produces = text/event-stream} 不能省，也不能被
     * 「统一响应体」的写法带偏。一旦返回类型写成 {@code BaseResponse<SseEmitter>}
     * 或者加了 {@code @ResponseBody} 之外的包装，浏览器收到的就不是事件流，
     * 前端 onmessage 一个字节都收不到。另外客户端断开后不要再发事件——
     * 对已关闭的连接写数据会抛 IOException，由 Service 标记中止并跳过落库。
     *
     * @param aiChatRequest 提问请求：message 必填；sessionId 可空表示新建会话。
     *                      这里不加 {@code @Valid}，非法参数会在流里以 error 事件返回
     * @return SSE 发射器。方法立即返回，回答在后台线程中推送；
     *         调用方不要对它做业务判断
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
     * <p>为什么这样设计：用户维度由服务端从登录态取，请求里没有 userId 参数——
     * 否则「看别人的会话列表」就成了一个功能。
     * 这里额外标 {@code @SaCheckLogin} 是纵深防御（多层各拦一次，任一层失效仍有下一层）：
     * 本路径未进白名单，全局拦截器本来就会要求登录；万一有人误把它加进白名单，
     * 注解仍能兜住——而「翻别人会话列表」这类问题一旦漏出就是数据泄露。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收分页参数。它不是 {@code @RequestBody}，因此由 Spring 按字段名
     *     从查询参数绑定，一个都不传时对象字段全为 null；</li>
     *     <li>② 调 Service 查分页并包响应。参数归一化（null 按第一页 10 条、上限 50）
     *     在 Service 里做，Controller 不重复一遍，避免两处口径漂移。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid} 是刻意的。分页参数非法（例如
     * pageSize=-1）不该报错，Service 会把它归一成默认值；若加了校验注解，
     * 一个手滑的负数就会变成 400，而用户只是想翻页。
     *
     * @param aiSessionQueryRequest 分页参数（current / pageSize），允许为 null 或字段全空
     * @return 统一响应体，data 里是会话分页结果（含每个会话的消息条数，按最近活跃倒序）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
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
     * <p>为什么这样设计：删除方式是「双逻辑删」——会话与消息在同一事务内
     * 各自把 deleted_at 写成毫秒时间戳，数据可追溯、误删可恢复。
     * 归属规则与提问完全一致，因此重复删除第二次会得到 A0402：
     * 第一次删除后会话已被逻辑删除，按 ID 查不到，与「不存在」走同一条路径。
     * 请求体用一个只有 sessionId 的 DTO 而不是查询参数，是为了与其它写接口保持一致
     * （写操作统一走 POST + JSON body）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空：整个请求体为 null 时 {@code @Valid} 不触发，提前给 A0401；</li>
     *     <li>② 取出 sessionId 交给 Service，由 Service 完成归属校验与事务内的双逻辑删。
     *     Controller 不判 sessionId 是否为空——那是 Service 的职责，两处都判会让
     *     「提示文案以哪一处为准」变得含糊。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这个方法上再套一层事务或 try-catch。删除失败时
     * Service 已经抛了带业务码的异常，这里再捕获一次只会把具体原因
     * （A0402 还是 B0002）压成一句笼统的失败提示。
     *
     * @param aiSessionDeleteRequest 删除请求：sessionId 必填；整个对象为 null 时得到 A0401
     * @return 统一响应体，data 为 true。删除不成功一律抛异常，因此 true 是唯一可能的取值
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）、
     * 会话不存在或无权访问（A0402）、未登录（A0201）时抛出
     */
    @PostMapping("/session/delete")
    @Operation(summary = "删除 AI 会话", description = "需登录；同事务逻辑删除会话与全部消息，重复删除返回 A0402")
    public BaseResponse<Boolean> deleteSession(@RequestBody @Valid AiSessionDeleteRequest aiSessionDeleteRequest) {
        ThrowUtils.throwIf(aiSessionDeleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(aiChatService.deleteSession(aiSessionDeleteRequest.getSessionId()));
    }
}
