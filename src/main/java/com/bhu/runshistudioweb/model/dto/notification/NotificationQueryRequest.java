package com.bhu.runshistudioweb.model.dto.notification;

import lombok.Data;

import java.io.Serializable;

/**
 * 通知列表查询请求（C 端）
 *
 * 与其它查询 DTO 同一惯例：GET + 对象绑定，分页字段由本类声明，
 * 非法值由 Service 兜底纠正，不报错。
 *
 * 为什么支持按已读/未读过滤：通知积累后的高频操作是
 * 「只看没读过的」——全部已读的旧通知不该占着第一屏。
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
