package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 提问请求
 *
 * author: shaoshing
 *
 * <p>只有两个字段：
 * <ul>
 *     <li>{@code sessionId}：<b>可空</b>。为空表示「开一个新会话」（Service 会建会话并把新 ID 返回），
 *     非空表示「在已有会话里接着说」；</li>
 *     <li>{@code message}：必填，≤500 字（与 {@code AiChatConstant#MESSAGE_MAX_LENGTH} 一致）。</li>
 * </ul>
 *
 * <p><b>为什么 sessionId 用 {@code String} 而不是 {@code Long}</b>：
 * 雪花 ID 是 19 位，响应里按全局约定序列化成<b>字符串</b>（防 JS 精度丢失），
 * 前端拿到的就是字符串。如果这里声明成 Long，前端原样回传也能靠 Jackson 自动转型，
 * 但「同一个值在请求侧是数字、响应侧是字符串」会让人反复确认；
 * 统一用 String 再接 {@code Convert.toLong} 转换，边界更清楚。
 * 顺带的好处：传了脏值（如 {@code "abc"}）时能被识别成 A0401，而不是 Jackson 反序列化异常。
 *
 * <p>{@code @Size} 只加在 message 上：长度限制属于业务策略，必须同时被
 * 「注解（Web 层第一道）+ Service 常量校验（非 Web 入口兜底）」覆盖，缺一不可。
 */
@Data
public class AiChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（可空）：为空表示新建会话；非空表示继续该会话
     * <p>无论是不存在还是无权续聊，都统一返回 A0402「会话不存在」，不暴露存在性
     */
    private String sessionId;

    /**
     * 提问内容（必填，≤500 字）
     */
    @NotBlank(message = "提问内容不能为空")
    @Size(max = 500, message = "提问内容不能超过 500 字")
    private String message;
}
