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
 * 工作室内部事项（对应 studio_board_item）
 *
 * <p>与帖子不同：不是公开内容，没有审核/点赞/评论；与通知不同：是可流转的实体，
 * 不给每个成员复制一行。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_board_item")
public class StudioBoardItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 事项标题（NOT NULL，≤128） */
    private String title;

    /** 事项说明（Markdown 原文；渲染前必须清洗） */
    private String description;

    /** 状态：0-待办, 1-进行中, 2-已完成，取值见 BoardItemStatusEnum */
    private Integer status;

    /** 负责人成员档案 ID（关联 studio_member.id；null = 未指派） */
    private Long assigneeMemberId;

    /** 截止日期时间；null = 不设截止。过期只做高亮，不自动改状态 */
    private LocalDateTime deadline;

    /** 完成时间（进入已完成时写入，回退时清空） */
    private LocalDateTime completedAt;

    /** 同列内展示权重，越大越靠前 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Long deletedAt;
}
