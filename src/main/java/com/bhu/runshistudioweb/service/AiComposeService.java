package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.model.dto.ai.AiComposeMetaRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiComposePolishRequest;
import com.bhu.runshistudioweb.model.vo.ai.AiComposeMetaVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 写作助手：发帖页的「片段润色」与「标题/摘要生成」
 *
 * @author shaoshing
 */
public interface AiComposeService {

    /**
     * 润色用户选中的片段（SSE 流式）
     *
     * @param request 待润色片段；为空或超长时以 error 事件返回 A0401
     * @return SSE 发射器，方法立即返回，推流在虚拟线程中继续
     */
    SseEmitter polishStream(AiComposePolishRequest request);

    /**
     * 根据正文生成标题与摘要（同步）
     *
     * @param request 正文与可选的原标题
     * @return 生成的标题与摘要，两个字段都不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、非成员（A0301）、参数不合法（A0401）、AI 失败或结果无法解析（C0200）
     */
    AiComposeMetaVO generateMeta(AiComposeMetaRequest request);
}
