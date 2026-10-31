package com.bhu.runshistudioweb.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 生成的标题与摘要
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
