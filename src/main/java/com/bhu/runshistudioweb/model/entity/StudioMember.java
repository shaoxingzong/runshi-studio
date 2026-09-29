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
 * author: shaoshing
 *
 * <p>字段与列的映射依赖 MyBatis-Plus 的「驼峰 ↔ 下划线」自动转换：
 * {@code gradeYear → grade_year}、{@code userId → user_id}，无需逐个写 {@code @TableField}。
 *
 * <p><b>与 sys_user 的关系是本表最需要理解的一点</b>（DESIGN.md 2.1）：
 * 成员档案与登录账号是**可选 1:1**——{@code user_id} 可为 NULL，表示「这个人还没开通登录账号」。
 * 官网展示数据以**本表为唯一权威源**，不反向同步 sys_user；
 * 数据库侧靠 {@code uk_userid_deleted (user_id, deleted_at)} 保证一个账号最多绑定一条成员记录
 * （MySQL 唯一索引不约束 NULL，所以多条未绑定记录可以共存，无需额外的标记字段）。
 *
 * <p>字段类型注意点：
 * <ul>
 *     <li>{@code grade_year} 是 {@code smallint}，Java 侧用 {@code Integer} 接收，
 *     并校验合理区间（1950~2100）——绝不能用字符串存「2026级」，那样无法排序、无法按届筛选；</li>
 *     <li>{@code member_status} 是 {@code tinyint}，取值 0-在读/在队、1-毕业/离队，
 *     定义见 {@link com.bhu.runshistudioweb.model.enums.MemberStatusEnum}；</li>
 *     <li>{@code team_position} 是 {@code varchar}，取值定义见
 *     {@link com.bhu.runshistudioweb.model.enums.TeamPositionEnum}；</li>
 *     <li>{@code sort_order} 是官网展示置顶权重（数值越大越靠前），
 *     列表默认排序以它为准，而不是 id 倒序。</li>
 * </ul>
 *
 * <p>逻辑删除、时间填充、雪花主键三条全局约定与 {@link SysUser} 完全一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_member")
public class StudioMember implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 绑定的系统用户 ID（关联 sys_user.id），可为 NULL
     *
     * <p>NULL 表示该成员尚未开通登录账号；非 NULL 时必须保证「账号存在」且
     * 「未被其他成员绑定」（应用层校验 + uk_userid_deleted 唯一索引兜底并发竞态）。
     */
    private Long userId;

    /**
     * 成员姓名（展示主体，NOT NULL）
     */
    private String name;

    /**
     * 成员照片 URL（一般先调 /file/upload 拿到相对路径再填这里）
     */
    private String avatar;

    /**
     * 入学年份（如 2022）：smallint 存储，用于按届别筛选与排序
     */
    private Integer gradeYear;

    /**
     * 专业
     */
    private String major;

    /**
     * 技术方向（如：Java后端, AI应用），用于按方向筛选
     */
    private String direction;

    /**
     * 团队职务：member-成员, leader-队长, tech_lead-组长
     * 取值定义见 {@link com.bhu.runshistudioweb.model.enums.TeamPositionEnum}
     */
    private String teamPosition;

    /**
     * 成员状态：0-在读/在队, 1-毕业/离队
     * 取值定义见 {@link com.bhu.runshistudioweb.model.enums.MemberStatusEnum}
     */
    private Integer memberStatus;

    /**
     * GitHub 主页
     */
    private String githubUrl;

    /**
     * 个人简介
     */
    private String summary;

    /**
     * 展示置顶权重：数值越大越靠前（官网列表默认按它倒序）
     */
    private Integer sortOrder;

    /**
     * 创建人 ID：插入时由 MyMetaObjectHandler 填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /**
     * 更新人 ID：插入与更新时都会填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /**
     * 创建时间（datetime，映射 LocalDateTime；不用 timestamp，避免 2038 与时区隐式转换）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     *
     * <p>加 {@code @TableLogic} 后，MP 会自动改写 SQL：查询追加 {@code deleted_at = 0}，
     * 删除改成 UPDATE（值取 yml 里配置的毫秒时间戳表达式）。
     * <b>关联表实体不能出现本字段</b>，否则会给中间表也套上逻辑删除，与物理删除的设计冲突。
     */
    @TableLogic
    private Long deletedAt;
}