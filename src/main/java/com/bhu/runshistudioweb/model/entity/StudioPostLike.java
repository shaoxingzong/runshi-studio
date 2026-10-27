package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子点赞实体（对应 studio_post_like，物理删除）
 *
 * 只有 DDL 里真实存在的 4 列（id / post_id / user_id / created_at），
 * 绝不能加 deletedAt / updatedAt / createdBy / updatedBy，多一个字段就是一次 Unknown column 的 500。
 *
 * 本表只能赞、不能取消，关系建立后不再更新，所以物理删除、不留逻辑删除位。
 * uk_post_user(post_id, user_id) 是「一人一赞」的根基，兜住并发下的重复插入。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_post_like")
public class StudioPostLike implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被点赞的帖子 ID（关联 studio_post.id，NOT NULL） */
    private Long postId;

    /** 点赞用户 ID（sys_user.id，NOT NULL）：点赞必须登录，这是与可空会话 user_id 的区别 */
    private Long userId;

    /** 点赞时间（NOT NULL 且无 DB 默认值 → 必须由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
