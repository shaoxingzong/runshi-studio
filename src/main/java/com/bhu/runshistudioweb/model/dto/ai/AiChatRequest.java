package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 提问请求
 *
 * sessionId 可空：为空表示开一个新会话（Service 建会话并返回新 ID），非空表示在已有会话里接着说。
 * message 必填，≤500 字（与 AiChatConstant#MESSAGE_MAX_LENGTH 一致）。
 *
 * sessionId 用 String 而不是 Long：雪花 ID 是 19 位，响应里按全局约定序列化成字符串防精度丢失，
 * 统一用 String 再接 Convert.toLong 转换，边界更清楚；脏值（如 "abc"）也能被识别成 A0401，
 * 而不是 Jackson 反序列化异常。
 *
 * @Size 只加在 message 上：长度限制必须同时由「注解（Web 层第一道）+ Service 常量校验（非 Web 入口兜底）」
 * 覆盖，缺一不可。
 *
 * @author shaoshing
 */
@Data
public class AiChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（可空）：为空表示新建会话；非空表示继续该会话
     * 无论是不存在还是无权续聊，都统一返回 A0402「会话不存在」，不暴露存在性
     */
    private String sessionId;

    /** 提问内容（必填，≤500 字） */
    @NotBlank(message = "提问内容不能为空")
    @Size(max = 500, message = "提问内容不能超过 500 字")
    private String message;
}
