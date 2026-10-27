package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 会话删除请求
 *
 * 不复用通用 DeleteRequest：会话 ID 在本模块一律是字符串（响应里雪花 ID 即字符串，前端原样回传）。
 * 用 String 接参后，像 "abc" 这样的脏值能被识别成 A0401，而不是反序列化异常被兜成 B0001。
 *
 * 归属规则与提问一致（见 AiChatServiceImpl）：不存在与无权统一返回 A0402，不暴露存在性。
 *
 * @author shaoshing
 */
@Data
public class AiSessionDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID（必填） */
    @NotBlank(message = "会话 id 不能为空")
    private String sessionId;
}
