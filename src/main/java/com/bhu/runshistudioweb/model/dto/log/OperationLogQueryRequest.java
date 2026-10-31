package com.bhu.runshistudioweb.model.dto.log;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端：操作日志查询请求
 *
 * @author shaoshing
 */
@Data
public class OperationLogQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 起始时间（含），选填；与时区无关的本地时间，与库中 datetime 同口径 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 结束时间（含），选填；早于起始时间由 Service 报参数错误 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 动作码（精确匹配，选填），取值见 OperationTypeEnum */
    @Size(max = 48, message = "动作码不能超过 48 个字符")
    private String action;

    /** 操作人关键词（模糊匹配，选填） */
    @Size(max = 64, message = "操作人关键词不能超过 64 个字符")
    private String operatorKeyword;

    /** 对象类型（精确匹配，选填）：POST / COMMENT / USER / QUIZ */
    @Size(max = 32, message = "对象类型不能超过 32 个字符")
    private String targetType;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛（防止一次拉全表） */
    private Long pageSize = 10L;
}
