package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知实体（对应 studio_notification）
 *
 * 字段与列的映射依赖 MyBatis-Plus 的「驼峰 ↔ 下划线」自动转换。
 * 三条全局约定同其它实体：雪花 ID（ASSIGN_ID）、逻辑删除（deleted_at 毫秒时间戳）、
 * 审计四列由 MyMetaObjectHandler 自动填充。
 *
 * title / content 是快照不是模板引用：通知发出后业务数据怎么变
 * （帖子改名、评论被删）都不影响已收到通知的文字——通知记录的是「那一刻发生了什么」。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_notification")
public class StudioNotification implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long，接口层序列化为字符串） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 接收者用户 ID（sys_user.id） */
    private Long receiverId;

    /**
     * 通知类型，取值见 com.bhu.runshistudioweb.model.enums.NotificationTypeEnum
     * （POST_APPROVED / POST_REJECTED / COMMENT_REPLIED / MEMBER_GRANTED）
     */
    private String type;

    /** 来源业务类型：POST / COMMENT / USER（与 biz_id 配合做跳转） */
    private String bizType;

    /** 来源业务 ID（点击跳转用；允许悬空——来源被删后前端降级展示） */
    private Long bizId;

    /** 通知标题（一句话摘要，快照） */
    private String title;

    /** 通知正文（快照；驳回类带理由） */
    private String content;

    /** 是否已读：0-未读，1-已读（用 Integer 而非 Boolean：与项目其它状态列一致，且 tinyint 映射直观） */
    private Integer isRead;

    /** 已读时间（未读为 null；重复已读不刷新——第一次阅读时间才有意义） */
    private LocalDateTime readAt;

    /** 创建时间（insert 时由 MyMetaObjectHandler 自动填充；DDL 无 DB 默认值，漏注解会插入失败） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（insert / update 时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 创建人 ID（insert 时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 修改人 ID（insert / update 时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /**
     * 逻辑删除毫秒时间戳，0 表示未删除
     *
     * 注意：本字段不能加 @TableField(fill = ...)，它由逻辑删除机制自己维护
     * （与 SysUser 同一条约定）。
     */
    @TableLogic
    private Long deletedAt;
}
