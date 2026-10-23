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
 * 与 StudioMemberProject 同一条铁律：只有 DDL 里真实存在的列才能写进来。
 * 这张表只有 4 列（ id / post_id / user_id / created_at），因此：
 * - 绝不能有 deletedAt：表里没有这列。本模块只能赞、不能取消，
 * 不存在「解除点赞关系」的场景，所以设计上就是物理删除、不留逻辑删除位。
 * 一旦写上，MP 生成的 SQL 会带上 deleted_at → 直接报 Unknown column；
 * - 绝不能有 updatedAt / createdBy / updatedBy：同理，多一个字段就是一次 500。
 * 点赞关系建立后不会再被更新，不需要更新时间与操作人。
 *
 * created_at 是 NOT NULL 且数据库没有默认值，所以必须标
 * @TableField(fill = FieldFill.INSERT) 交给 MyMetaObjectHandler 填充。
 *
 * 本表承载的核心约束：唯一键 uk_post_user(post_id, user_id)
 * 是「一人一赞」的根基。应用层会先查重，该唯一键兜住并发下的重复插入
 * （两个请求同时通过查重时，第二个会撞键失败并回滚）。
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

    /**
     * 点赞用户 ID（关联 sys_user.id，NOT NULL）
     *
     * 点赞必须登录，因此该列不为空——这也是与 studio_ai_session.user_id
     * （可空，用于匿名会话）的本质区别。
     */
    private Long userId;

    /** 点赞时间（NOT NULL 且无 DB 默认值 → 必须由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
