package com.bhu.runshistudioweb.model.vo.ai;

import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSourceVO;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 提问响应
 *
 * sessionId 每次回答后都会返回，包括「本次刚新建会话」的情况：前端不必在发问前先调一次创建会话接口，
 * 只要把响应里的 sessionId 存下来，下一轮带上即可续聊——这是「新建会话」与「续聊」共用一个接口的关键。
 *
 * sessionId 是雪花 ID，按全局约定序列化成字符串防 JS 精度丢失，前端原样回传即可。
 *
 * @author shaoshing
 */
@Data
public class AiChatResponseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（雪花算法 19 位，序列化为字符串）
     * 前端必须保存它，用于下一轮提问（续聊）与查询历史
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    /** AI 回答正文 */
    private String answer;

    /**
     * 本次回答引用的资料来源（RAG 溯源）
     *
     * 无命中时是空数组而不是 null：前端写 sources.map(...) 时不必先判空，
     * 「没有引用资料」是正常情况（闲聊、或知识库为空），不该和「出错了」混在一起。
     *
     * 顺序即资料送进模型的顺序（相似度降序），前端按序渲染即可。
     */
    private List<KnowledgeSourceVO> sources = new ArrayList<>();
}
