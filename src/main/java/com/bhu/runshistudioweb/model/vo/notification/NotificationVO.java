package com.bhu.runshistudioweb.model.vo.notification;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知列表项（C 端）
 *
 * typeDesc 由服务端按 NotificationTypeEnum 填：前端不维护「类型值 → 中文」的映射，
 * 那是枚举的职责，散到前端就会出现「后端加了新类型、前端显示原始值」的静默降级。
 *
 * bizType / bizId 是跳转指针，允许悬空：来源被删后前端按「内容已失效」降级展示（不可点），
 * 通知本身的可读性不受影响。
 *
 * @author shaoshing
 */
@Data
public class NotificationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 通知类型（原始值，见 NotificationTypeEnum） */
    private String type;

    /** 通知类型中文描述（服务端按枚举填充） */
    private String typeDesc;

    /** 来源业务类型：POST / COMMENT / USER */
    private String bizType;

    /** 来源业务 ID（序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bizId;

    /** 通知标题（快照） */
    private String title;

    /** 通知正文（快照） */
    private String content;

    /** 是否已读：0-未读，1-已读（前端据此做视觉区分） */
    private Integer isRead;

    /** 通知时间（创建时间） */
    private LocalDateTime createdAt;
}
