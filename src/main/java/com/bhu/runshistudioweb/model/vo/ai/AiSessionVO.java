package com.bhu.runshistudioweb.model.vo.ai;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话视图对象（「我的会话」列表使用）
 *
 * 只有四个字段：id（点进去看历史）、title（列表主文案）、updatedAt（最近聊过的时间）、
 * messageCount（聊了多少轮）。
 *
 * 刻意不含 userId（返回它等于把账号关系暴露给前端，而列表本来就是当前用户的会话）
 * 与 createdBy / updatedBy / createdAt / deletedAt（审计字段）：脱敏靠「目标 VO 没有这些字段」。
 *
 * 类型选择：id 用 Long + ToStringSerializer（雪花 ID 19 位超出 JS 安全整数范围，必须出字符串）；
 * messageCount 用 Integer（前端要拿它做展示，用 Long 会被全局约定变成 "12"）。主键出字符串，计数出数字。
 *
 * @author shaoshing
 */
@Data
public class AiSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（雪花算法 19 位，序列化为字符串）
     * 前端点进某条会话时，用它调历史 / 提问 / 删除接口
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 会话标题（取首问前 30 字） */
    private String title;

    /**
     * 最后活跃时间（每次追加消息都会刷新）
     * 由全局 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss
     */
    private LocalDateTime updatedAt;

    /** 会话下的消息条数（批量统计得出，不是逐条 count） */
    private Integer messageCount;
}
