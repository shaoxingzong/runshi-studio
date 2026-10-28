package com.bhu.runshistudioweb.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 生成的标题与摘要
 *
 * <p>两个字段都不允许为 null：生成失败时上游会抛 C0200，而不是返回半份结果——
 * 只回填标题、摘要还是旧的，用户很难发现哪里没生效。
 *
 * @author shaoshing
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiComposeMetaVO {

    /** 生成的标题（不超过 40 字，由提示词约束） */
    private String title;

    /** 生成的摘要（80~150 字，由提示词约束） */
    private String summary;
}
