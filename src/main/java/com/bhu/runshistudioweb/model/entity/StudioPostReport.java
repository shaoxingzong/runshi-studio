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
 * 内容举报实体（对应 studio_post_report）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_post_report")
public class StudioPostReport implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 举报目标类型：post-帖子，comment-评论，取值见 ReportTargetTypeEnum */
    private String targetType;

    /** 举报目标 ID（指向 studio_post.id 或 studio_post_comment.id，由 targetType 决定） */
    private Long targetId;

    /** 举报人 ID（关联 sys_user.id） */
    private Long reporterId;

    /** 举报理由（举报人填写） */
    private String reason;

    /** 处理状态：0-待处理，1-已处置，2-举报不成立，取值见 ReportStatusEnum */
    private Integer status;

    /** 处理人 ID（管理员） */
    private Long handleBy;

    /** 处理时间 */
    private LocalDateTime handleAt;

    /** 处理结果说明（内部复盘用）：不返回给举报人，避免被研究出「什么样的举报会被受理」 */
    private String handleResult;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（datetime，映射 LocalDateTime） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Long deletedAt;
}
