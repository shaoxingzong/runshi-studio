package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 润色请求：用户在编辑器里选中的那段文字
 *
 * @author shaoshing
 */
@Data
public class AiComposePolishRequest {

    /** 待润色的片段（用户选中的原文） */
    @Size(max = 2000, message = "润色片段不能超过 2000 字")
    private String text;
}
