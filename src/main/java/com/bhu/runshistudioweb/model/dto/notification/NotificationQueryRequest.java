package com.bhu.runshistudioweb.model.dto.notification;

import lombok.Data;

import java.io.Serializable;

/**
 * 通知列表查询请求（C 端）
 *
 * @author shaoshing
 */
@Data
public class NotificationQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 已读状态过滤（选填）：0-只要未读，1-只要已读，null-全部 */
    private Integer isRead;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛 */
    private Long pageSize = 10L;
}
