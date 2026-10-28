package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 生成标题与摘要的请求：当前正文（可带原标题作为参考）
 *
 * <p>正文上限 8000 字：生成标题/摘要只需要「读懂主旨」，
 * 不是把全文塞给模型——正文越长，token 成本越高、模型越容易抓错重点。
 *
 * @author shaoshing
 */
@Data
public class AiComposeMetaRequest {

    /** 当前正文（Markdown）；必填由 Service 校验（@Size 只管上限，空值另有语义） */
    @Size(max = 8000, message = "正文过长，生成标题摘要最多参考 8000 字")
    private String content;

    /** 当前标题（可为空；非空时作为「原标题」参考，避免生成风格突变） */
    @Size(max = 128, message = "标题不能超过 128 字")
    private String title;
}
