package com.bhu.runshistudioweb.model.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 会话分页查询请求（「我的会话」列表用）
 *
 * @author shaoshing
 */
@Data
public class AiSessionQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过上限（50）会被收敛 */
    private Long pageSize = 10L;
}
