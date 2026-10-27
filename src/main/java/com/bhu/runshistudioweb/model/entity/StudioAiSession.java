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
 * AI 咨询会话实体（对应 studio_ai_session）
 *
 * 一个会话 = 一次打开聊天窗口，下挂若干 StudioAiMessage。
 * userId 可空：NULL 表示游客匿名会话，凭不可枚举的雪花 ID 续聊（知道 ID 即持有凭据）；
 * 非空则只有本人能续聊，越权统一返回 A0402，不暴露「会话是否存在」。
 *
 * updated_at 在每次追加消息时由 Service 刷新，供「最近活跃」排序，免去 join 消息表。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_ai_session")
public class StudioAiSession implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法（19 位 Long），不走自增；不可枚举，是游客续聊的凭据 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 提问用户 ID（sys_user.id），可空：NULL 为游客匿名会话，任何人凭会话 ID 可续聊 */
    private Long userId;

    /** 会话标题：取首问前 30 个字，仅用于会话列表展示 */
    private String title;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间：每次追加消息都会被刷新（同一事务内），用于「最近活跃」排序 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     * 会话不物理删除：它下挂消息，删了会留下孤儿
     */
    @TableLogic
    private Long deletedAt;
}
