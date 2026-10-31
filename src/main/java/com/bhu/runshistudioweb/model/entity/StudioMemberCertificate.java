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
