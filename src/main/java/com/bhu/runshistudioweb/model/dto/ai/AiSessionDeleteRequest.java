package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 会话删除请求
 *
 * author: shaoshing
 *
 * <p><b>为什么不复用通用的 {@code DeleteRequest}</b>：那个 DTO 的字段是 {@code Long id}，
 * 而会话 ID 在本模块一律以<b>字符串</b>形式传递（雪花 ID 在响应里是字符串，
 * 前端原样回传）。用 {@code Long} 接参数值时虽然 Jackson 也能转型，
 * 但「同一个值在请求侧是数字、响应侧是字符串」会让人反复确认；
 * 更重要的是：用 String 接参后，像 {@code "abc"} 这样的脏值能被识别成 40000，
 * 而不是在反序列化阶段抛异常被兜成 50000。
 *
 * <p>删除的<b>归属规则与提问完全一致</b>（见 {@code AiChatServiceImpl}）：
 * 绑定用户的会话仅本人可删；匿名会话凭不可枚举的雪花 ID 可删；
 * 不存在与无权统一返回 40400，不暴露存在性。
 */
@Data
public class AiSessionDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（必填）
     */
    @NotBlank(message = "会话 id 不能为空")
    private String sessionId;
}
