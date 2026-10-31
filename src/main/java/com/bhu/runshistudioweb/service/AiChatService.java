package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.ai.AiChatRequest;
import com.bhu.runshistudioweb.model.dto.ai.AiSessionQueryRequest;
import com.bhu.runshistudioweb.model.vo.ai.AiChatResponseVO;
import com.bhu.runshistudioweb.model.vo.ai.AiMessageVO;
import com.bhu.runshistudioweb.model.vo.ai.AiSessionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 咨询服务接口（知识库问答的对外契约）
 *
 * @author shaoshing
 */
public interface AiChatService {

    /**
     * 同步提问：检索知识库 → 调模型 → 落库本轮对话，一次性返回完整回答
     *
     * @param request 提问请求：message 必填且不超过 500 字；sessionId 可空表示新建会话；整个请求体为 null 时得到 A0401
     * @throws BusinessException 参数不合法（A0401）、会话不存在或无权访问（A0402）、提问次数超限（A0501）、AI 调用失败（C0200）
     */
    AiChatResponseVO chat(AiChatRequest request);

    /**
     * 查询会话历史：最近 50 条消息，按时间正序（旧 → 新）
     *
     * @param sessionId 会话 ID，必填；空或非法时得到 A0401
     * @return 消息列表（旧 → 新）；会话存在但没有消息时返回空列表，不是 null
     * @throws BusinessException id 缺失或非法（A0401）、会话不存在或无权访问（A0402）、未登录（A0201）
     */
    List<AiMessageVO> listHistory(String sessionId);

    /**
     * 分页查询「我的会话列表」：按最近活跃倒序，附带每个会话的消息条数
     *
     * @param request 分页参数，允许为 null，此时按第一页 10 条处理
     * @return 会话分页结果；无数据时 records 为空列表、total 为 0
     * @throws BusinessException 未登录时抛出（A0201）
     */
    Page<AiSessionVO> listSessions(AiSessionQueryRequest request);

    /**
     * 删除会话及其全部消息（父子两张表一起逻辑删除）
     *
     * @param sessionId 会话 ID，必填；空或非法时得到 A0401
     * @return 恒为 true；删除不成功一律抛异常，没有「返回 false 表示失败」的分支
     * @throws BusinessException id 缺失或非法（A0401）、会话不存在或无权访问（A0402）、未登录（A0201）、影响行数不为 1（B0002）
     */
    boolean deleteSession(String sessionId);

    /**
     * SSE 流式提问：回答边生成边推给前端
     *
     * @param request 提问请求：message 必填；sessionId 可空表示新建会话
     * @return SSE 发射器。方法立刻返回，推流在虚拟线程中继续；业务结果只能从事件里读，不要在这个对象上做判断
     * @throws BusinessException 本方法自身不抛：鉴权、参数、配额、AI 失败一律转成 error 事件
     */
    SseEmitter chatStream(AiChatRequest request);
}
