package com.bhu.runshistudioweb.model.dto.report;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：举报列表查询请求
 *
 * @author shaoshing
 */
@Data
public class ReportQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 处理状态（精确匹配）：0-待处理, 1-已处置, 2-举报不成立；不传按 0 处理 */
    private Integer status;

    /** 目标类型（精确匹配）：post / comment；不传表示不限 */
    @Size(max = 16, message = "举报对象类型不能超过 16 个字符")
    private String targetType;

    /** 页码，从 1 开始 */
    private Long current = 1L;

    /** 每页条数，默认 10，上限由 Service 收敛 */
    private Long pageSize = 10L;
}
