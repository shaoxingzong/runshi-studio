package com.bhu.runshistudioweb.model.vo.ai;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 消息视图对象（历史记录接口使用）
 *
 * @author shaoshing
 */
@Data
public class AiMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息 ID（雪花算法 19 位，序列化为字符串）前端用它做列表 key，因此绝不能是数字（JS 会丢精度导致 key 冲突） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    /** 消息角色：user（用户提问，气泡靠右）/ assistant（AI 回答，气泡靠左）取值见 com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum */
    private String role;

    /** 消息正文 */
    private String content;

    /** 创建时间（由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss） */
    private LocalDateTime createdAt;
}
