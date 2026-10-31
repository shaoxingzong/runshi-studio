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
 * 工作室成员档案实体（对应 studio_member）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_member")
public class StudioMember implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 绑定的登录账号 ID（sys_user.id），可空：NULL 表示还没开号。非空时须保证账号存在且未被其他成员绑定（应用层校验 + uk_userid_deleted 兜并发） */
    private Long userId;

    /** 成员姓名（展示主体，NOT NULL） */
    private String name;

    /** 成员照片 URL（一般先调 /file/upload 拿到相对路径再填这里） */
    private String avatar;

    /** 入学年份（如 2022）：smallint 存储，用于按届别筛选与排序 */
    private Integer gradeYear;

    /** 专业 */
    private String major;

    /** 技术方向（如：Java后端, AI应用），用于按方向筛选 */
    private String direction;

    /** 团队职务：member / leader / tech_lead，取值见 TeamPositionEnum */
    private String teamPosition;

    /** 成员状态：0-在读/在队，1-毕业/离队，取值见 MemberStatusEnum */
    private Integer memberStatus;

    /** GitHub 主页 */
    private String githubUrl;

    /** 个人简介 */
    private String summary;

    /** 展示置顶权重：数值越大越靠前（官网列表默认按它倒序） */
    private Integer sortOrder;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（datetime，映射 LocalDateTime；不用 timestamp，避免 2038 与时区隐式转换） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除毫秒时间戳（0-未删除，非0-已删除）；关联表实体不能出现本字段，否则与物理删除冲突 */
    @TableLogic
    private Long deletedAt;
}