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
