package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.dto.ai.AiComposeMetaRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiComposePolishRequest;
import com.bhu.runshistudioweb.model.vo.ai.AiComposeMetaVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 写作助手：发帖页的「片段润色」与「标题/摘要生成」
 *
 * <p>为什么与 AI 咨询分开成两个 Service：两者的人设、上下文、产物完全不同——
 * 咨询是「多轮追问 + 知识库引用」，写作是「一次处理一段文本 + 风格参考」。
 * 合在一个 Service 里，迟早会出现「调错 method、把聊天历史带进润色」这类串味问题。
 *
 * <p>共同纪律：都只对工作室成员开放（与发帖准入同一口径），并复用 {@code AiManager} 的
 * 熔断、超时与错误收敛；任何 AI 故障都不得污染用户已经写好的草稿。
 *
 * @author shaoshing
 */
public interface AiComposeService {

    /**
     * 润色用户选中的片段（SSE 流式）
     *
     * <p>为什么流式而不是一次性返回：润色虽然短，但用户盯着一个「转圈」等十几秒，
     * 体验远差于看着文字逐句改出来；而且流式天然支持「不满意就停止」。
     *
     * <p>事件契约：delta* → done / error（没有 meta/sources，本接口不涉及会话与检索）。
     * 失败一律以 error 事件返回，HTTP 状态恒为 200（SSE 的必然结果）。
     *
     * @param request 待润色片段；为空或超长时以 error 事件返回 A0401
     * @return SSE 发射器，方法立即返回，推流在虚拟线程中继续
     */
    SseEmitter polishStream(AiComposePolishRequest request);

    /**
     * 根据正文生成标题与摘要（同步）
     *
     * <p>为什么同步而不是流式：产物是两个短字段、要一起回填表单，逐字流式没有意义；
     * 一次性返回还能保证「标题与摘要基于同一份上下文」。
     *
     * @param request 正文与可选的原标题
     * @return 生成的标题与摘要，两个字段都不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     *         非成员（A0301）、参数不合法（A0401）、AI 失败或结果无法解析（C0200）
     */
    AiComposeMetaVO generateMeta(AiComposeMetaRequest request);
}
