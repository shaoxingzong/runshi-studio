package com.bhu.runshistudioweb.model.dto.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 标记单条通知已读的请求
 *
 * 走 POST + 请求体：19 位雪花 ID 放查询参数容易在日志里被截断展示。
 *
 * @author shaoshing
 */
@Data
public class NotificationReadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 通知 ID（必填）
     *
     * @Positive 是廉价兜底：挡住 -1、0 这类明显非法的探测请求，避免它们打到数据库再返回「不存在」。
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;
}
