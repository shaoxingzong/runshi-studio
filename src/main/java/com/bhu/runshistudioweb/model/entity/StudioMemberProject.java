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
 * 成员-项目关联实体（对应 studio_member_project，<b>物理删除</b>）
 *
 * author: shaoshing
 *
 * <p><b>与 {@link StudioMemberCertificate} 同一条铁律：只有 DDL 里真实存在的列才能写进来。</b>
 * 这张表只有 4 列（{@code id / member_id / project_id / created_at}），因此：
 * <ul>
 *     <li><b>绝不能有 {@code deletedAt}</b>：表里没有这列，且设计上是物理删除。
 *     一旦写上，MP 生成的 SQL 会带上 {@code deleted_at} → 直接报 Unknown column；</li>
 *     <li><b>绝不能有 {@code updatedAt / createdBy / updatedBy}</b>：同理，多一个字段就是一次 500。</li>
 * </ul>
 *
 * <p>{@code created_at} 是 NOT NULL 且数据库<b>没有默认值</b>，所以必须标
 * {@code @TableField(fill = FieldFill.INSERT)} 交给 {@code MyMetaObjectHandler} 填充。
 *
 * <p><b>本表承载的一个不变量</b>：项目队长（{@code studio_project.leader_id}）
 * 必然在本表中有一行对应记录，由 Service 在写项目时同一事务内同步（DESIGN 2.3）。
 * 解绑接口会拦住「移除当前队长」，否则这个不变量会被打破。
 */
@Data
@TableName("studio_member_project")
public class StudioMemberProject implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 成员 ID（关联 studio_member.id，NOT NULL）
     */
    private Long memberId;

    /**
     * 项目 ID（关联 studio_project.id，NOT NULL）
     */
    private Long projectId;

    /**
     * 创建时间（NOT NULL 且无 DB 默认值 → 必须由 MetaObjectHandler 填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
