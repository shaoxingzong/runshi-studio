package com.bhu.runshistudioweb.model.vo.board;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作室内部事项视图对象（成员看板与管理端列表共用）
 *
 * @author shaoshing
 */
@Data
public class BoardItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事项 ID（19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 事项标题 */
    private String title;

    /** 事项说明（Markdown 原文；前端渲染前必须清洗） */
    private String description;

    /** 状态：0-待办, 1-进行中, 2-已完成 */
    private Integer status;

    /** 状态中文标签（服务端按枚举填，前端不维护映射） */
    private String statusLabel;

    /** 负责人成员档案 ID（19 位，序列化为字符串）；null = 未指派 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assigneeMemberId;

    /** 负责人姓名（由 Service 批量反查，成员档案不存在时为空） */
    private String assigneeName;

    /** 负责人头像 */
    private String assigneeAvatar;

    /** 截止日期时间；null = 不设截止 */
    private LocalDateTime deadline;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 同列内展示权重 */
    private Integer sortOrder;

    /** 是否逾期：未完成且已过截止时间；已完成且完成时间晚于截止时间也算逾期完成 */
    private Boolean overdue;

    /** 当前登录人能否改这条的状态：管理员恒 true，成员仅自己被指派时为 true */
    private Boolean canChangeStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
