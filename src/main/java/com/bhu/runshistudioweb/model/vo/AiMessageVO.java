package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 消息视图对象（历史记录接口使用）
 *
 * author: shaoshing
 *
 * <p>只暴露前端渲染对话气泡需要的四个字段：id（列表 key）、role（决定气泡在左还是在右）、
 * content、createdAt（时间戳展示）。
 *
 * <p><b>刻意不包含</b>：{@code sessionId}（前端已经知道，属于冗余）、
 * {@code createdBy / updatedBy / updatedAt / deletedAt}（审计字段，与展示无关）。
 * 与其它模块一致：脱敏靠「目标 VO 没有这些字段」，而不是拷完再手动置空。
 *
 * <p>{@code createdAt} 由全局 {@code JsonConfig} 统一序列化成 {@code yyyy-MM-dd HH:mm:ss}，
 * 前端不需要再处理 ISO-8601 的 "T"。
 */
@Data
public class AiMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 ID（雪花算法 19 位，序列化为字符串）
     * <p>前端用它做列表 key，因此绝不能是数字（JS 会丢精度导致 key 冲突）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 消息角色：{@code user}（用户提问，气泡靠右） / {@code assistant}（AI 回答，气泡靠左）
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.AiMessageRoleEnum}
     */
    private String role;

    /**
     * 消息正文
     */
    private String content;

    /**
     * 创建时间（由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss）
     */
    private LocalDateTime createdAt;
}
