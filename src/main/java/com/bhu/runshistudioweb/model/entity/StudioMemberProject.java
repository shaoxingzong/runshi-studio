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
 * 成员-项目关联实体（对应 studio_member_project，物理删除）
 *
 * 只有 DDL 里真实存在的 4 列（id / member_id / project_id / created_at），
 * 绝不能加 deletedAt / updatedAt / createdBy / updatedBy，多一个字段就是一次 500。
 *
 * 不变量：项目队长（studio_project.leader_id）必然在本表有一行，由 Service 同事务同步；
 * 解绑接口会拦住「移除当前队长」，否则该不变量被打破。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_member_project")
public class StudioMemberProject implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 成员 ID（关联 studio_member.id，NOT NULL） */
    private Long memberId;

    /** 项目 ID（关联 studio_project.id，NOT NULL） */
    private Long projectId;

    /** 创建时间（NOT NULL 且无 DB 默认值 → 必须由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
