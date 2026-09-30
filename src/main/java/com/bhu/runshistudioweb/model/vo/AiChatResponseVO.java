package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * AI 提问响应
 *
 * author: shaoshing
 *
 * <p>{@code sessionId} 在<b>每一次</b>回答后都会返回，包括「本次刚新建会话」的情况：
 * 前端不需要在发问前先调一次「创建会话」接口，只要把响应里的 sessionId 存下来，
 * 下一轮带上即可续聊。这是「新建会话」与「续聊」共用一个接口的关键。
 *
 * <p>{@code sessionId} 用雪花 ID，按全局约定序列化成字符串（防 JS 精度丢失）。
 * 前端原样回传即可（见 {@code AiChatRequest#sessionId} 的说明）。
 */
@Data
public class AiChatResponseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（雪花算法 19 位，序列化为字符串）
     * <p>前端必须保存它，用于下一轮提问（续聊）与查询历史
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    /**
     * AI 回答正文
     */
    private String answer;
}
