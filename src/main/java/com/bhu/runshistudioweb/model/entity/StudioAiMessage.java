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
 * AI 咨询消息实体（对应 studio_ai_message）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_ai_message")
public class StudioAiMessage implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话 ID（关联 studio_ai_session.id），走 idx_session_created 的最左前缀 */
    private Long sessionId;

    /** 消息角色：user（用户提问）/ assistant（AI 回答），取值见 AiMessageRoleEnum */
    private String role;

    /** 消息正文（用户问题 或 AI 回答） */
    private String content;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（NOT NULL，无 DB 默认值，由 MetaObjectHandler 填充）；秒级精度，排序需配合 id */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Long deletedAt;
}
