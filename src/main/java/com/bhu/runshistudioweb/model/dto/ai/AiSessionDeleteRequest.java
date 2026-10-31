package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 会话删除请求
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
