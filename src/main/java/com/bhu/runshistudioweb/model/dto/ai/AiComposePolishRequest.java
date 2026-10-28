package com.bhu.runshistudioweb.model.dto.ai;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 润色请求：用户在编辑器里选中的那段文字
 *
 * <p>只润色「选中的片段」而不是整篇正文，是刻意的：整篇重写会覆盖用户已经想好的结构与措辞，
 * 而片段润色只解决「这一段读着别扭」的问题，风险与 token 成本都小得多。
 *
 * @author shaoshing
 */
@Data
public class AiComposePolishRequest {

    /**
     * 待润色的片段（用户选中的原文）
     *
     * <p>上限 2000 字：润色是「改表达」不是「重写长文」，超过这个长度基本是整篇正文，
     * 那属于另一个功能（起草），不该走润色这条低风险路径。
     */
    @Size(max = 2000, message = "润色片段不能超过 2000 字")
    private String text;
}
