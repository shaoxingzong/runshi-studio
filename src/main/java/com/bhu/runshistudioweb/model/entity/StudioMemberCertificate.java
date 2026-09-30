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
 * 成员-证书关联实体（对应 studio_member_certificate，<b>物理删除</b>）
 *
 * author: shaoshing
 *
 * <p><b>本实体最重要的规则：只有 DDL 里真实存在的列才能写进来。</b>
 * 这张表只有 4 列（{@code id / member_id / certificate_id / created_at}），因此：
 * <ul>
 *     <li><b>绝不能有 {@code deletedAt}</b>：表里没有这列，而且设计上是物理删除。
 *     一旦写上（哪怕不加 {@code @TableLogic}），MyBatis-Plus 生成的 SQL 会带上
 *     {@code deleted_at} → 直接报 Unknown column；</li>
 *     <li><b>绝不能有 {@code updatedAt / createdBy / updatedBy}</b>：表里同样没有。
 *     多一个字段就是一次 500（插入时报不存在该列）。</li>
 * </ul>
 * 对比一下主表：{@link SysUser}、{@link StudioMember}、{@link StudioCertificate} 都是逻辑删除，
 * 带 {@code deletedAt} + {@code @TableLogic}；本表是**唯一的物理删除表**，
 * 因为关联关系本身没有独立价值——成员或证书被删了，关系就该立刻消失，不需要「回收站」。
 *
 * <p>{@code created_at} 是 NOT NULL 且数据库**没有默认值**，所以必须标
 * {@code @TableField(fill = FieldFill.INSERT)} 交给 {@code MyMetaObjectHandler} 填充，
 * 否则插入直接失败。
 */
@Data
@TableName("studio_member_certificate")
public class StudioMemberCertificate implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），与业务表保持同一套 ID 方案
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 成员 ID（关联 studio_member.id，NOT NULL）
     */
    private Long memberId;

    /**
     * 证书 ID（关联 studio_certificate.id，NOT NULL）
     */
    private Long certificateId;

    /**
     * 创建时间（NOT NULL 且无 DB 默认值 → 必须由 MetaObjectHandler 填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
