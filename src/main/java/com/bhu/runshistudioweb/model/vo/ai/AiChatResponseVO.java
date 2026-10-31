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
 * sessionId 是雪花 ID，按全局约定序列化成字符串防 JS 精度丢失，前端原样回传即可。
 *
 * @author shaoshing
 */
@Data
public class AiChatResponseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID（雪花算法 19 位，序列化为字符串）前端必须保存它，用于下一轮提问（续聊）与查询历史 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    /** AI 回答正文 */
    private String answer;

    private List<KnowledgeSourceVO> sources = new ArrayList<>();
}
