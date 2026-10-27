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
 * 成员-证书关联实体（对应 studio_member_certificate，物理删除）
 *
 * 只有 DDL 里真实存在的 4 列（id / member_id / certificate_id / created_at），
 * 绝不能加 deletedAt / updatedAt / createdBy / updatedBy：
 * 表里没有这些列，多一个字段就是一次 Unknown column 的 500。
 *
 * 本表是唯一的物理删除表：关联关系没有独立价值，成员或证书没了关系就该立刻消失。
 * created_at 无 DB 默认值，必须标 @TableField(fill = FieldFill.INSERT)。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_member_certificate")
public class StudioMemberCertificate implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），与业务表保持同一套 ID 方案 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 成员 ID（关联 studio_member.id，NOT NULL） */
    private Long memberId;

    /** 证书 ID（关联 studio_certificate.id，NOT NULL） */
    private Long certificateId;

    /** 创建时间（NOT NULL 且无 DB 默认值 → 必须由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
