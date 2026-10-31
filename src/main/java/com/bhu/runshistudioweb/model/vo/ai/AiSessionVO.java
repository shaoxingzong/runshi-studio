package com.bhu.runshistudioweb.model.vo.ai;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话视图对象（「我的会话」列表使用）
 *
 * @author shaoshing
 */
@Data
public class AiSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID（雪花算法 19 位，序列化为字符串）前端点进某条会话时，用它调历史 / 提问 / 删除接口 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 会话标题（取首问前 30 字） */
    private String title;

    /** 最后活跃时间（每次追加消息都会刷新）由全局 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime updatedAt;

    /** 会话下的消息条数（批量统计得出，不是逐条 count） */
    private Integer messageCount;
}
