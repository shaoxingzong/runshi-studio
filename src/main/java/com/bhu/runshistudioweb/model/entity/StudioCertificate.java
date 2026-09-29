package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 荣誉证书实体（对应 studio_certificate）
 *
 * author: shaoshing
 *
 * <p>字段与列的映射依赖 MyBatis-Plus 的「驼峰 ↔ 下划线」自动转换：
 * {@code awardLevel → award_level}、{@code imageUrl → image_url}，无需逐个写 {@code @TableField}。
 *
 * <p><b>本表最重要的设计：级别与类型是两个正交维度（ADR-5）</b>：
 * <ul>
 *     <li>{@code award_level} 级别：national / provincial / municipal
 *     （见 {@link com.bhu.runshistudioweb.model.enums.CertificateLevelEnum}）；</li>
 *     <li>{@code award_type} 类型：competition / soft_copyright / patent / paper
 *     （见 {@link com.bhu.runshistudioweb.model.enums.CertificateTypeEnum}）。</li>
 * </ul>
 * 二者必须分开存：<b>禁止</b>合并成「国家级竞赛」这种单字段写法。
 * 合并后任一维度都无法独立筛选与统计，只能用 {@code LIKE} 模糊匹配，
 * 既走不了索引（本表为两个维度各建了组合索引），也必然出现误匹配。
 *
 * <p>其它字段要点：
 * <ul>
 *     <li><b>{@code award_date} 是 {@code date} 类型</b>，Java 侧必须用 {@code LocalDate}
 *     （不是 LocalDateTime）。用错类型的典型症状是「时间字段莫名多了 00:00:00，
 *     或前端传 2026-09-30T10:00 时报错」；业务上还要求它<b>不得晚于今天</b>
 *     （还没发生的获奖日期没有意义，多半是填错）；</li>
 *     <li><b>{@code image_url} 是 {@code NOT NULL}</b>：证书本身就是一张图，
 *     没有图的记录对官网展示毫无价值，因此必须在入参侧校验非空，
 *     别等数据库抛「Column 'image_url' cannot be null」;</li>
 *     <li>{@code sort_order} 是展示置顶权重（数值越大越靠前），
 *     与 {@code award_date} 共同决定默认排序（见下方「默认排序」说明）。</li>
 * </ul>
 *
 * <p><b>默认排序 = {@code sort_order} 倒序 + {@code award_date} 倒序</b>：
 * 先按运营配置的置顶权重排，权重相同时按获奖时间从新到旧。
 * 这样排序与 {@code idx_type_date} / {@code idx_level_date} 的
 * {@code (维度列, award_date DESC)} 结构同向，按维度筛选时索引能直接提供有序结果，避免 filesort。
 *
 * <p>逻辑删除、时间填充、雪花主键三条全局约定与 {@link SysUser}、{@link StudioMember} 完全一致，
 * 见 db/DESIGN.md。注意：成员-证书关联表 {@code studio_member_certificate} 是<b>物理删除</b>，
 * 其实体不能带本类的 {@code deletedAt} 字段。
 */
@Data
@TableName("studio_certificate")
public class StudioCertificate implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 证书 / 获奖名称（NOT NULL）
     */
    private String title;

    /**
     * 级别维度：national / provincial / municipal
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.CertificateLevelEnum}
     */
    private String awardLevel;

    /**
     * 类型维度：competition / soft_copyright / patent / paper
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.CertificateTypeEnum}
     */
    private String awardType;

    /**
     * 获奖 / 颁发日期（date 类型 → LocalDate）
     *
     * <p>业务约束：不得晚于今天（由 DTO 上的 {@code @PastOrPresent} 与 Service 双重校验保证）。
     */
    private LocalDate awardDate;

    /**
     * 证书图片 URL（NOT NULL）
     * <p>一般先调 {@code /file/upload} 拿到 {@code /uploads/...} 相对路径再填到这里
     */
    private String imageUrl;

    /**
     * 展示置顶权重：数值越大越靠前（默认排序的第一关键字）
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
     * 创建时间（datetime，映射 LocalDateTime）
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
     * <p>加 {@code @TableLogic} 后 MP 会自动改写 SQL：查询追加 {@code deleted_at = 0}，
     * 删除改成 UPDATE（值取 yml 里配置的毫秒时间戳表达式）。
     */
    @TableLogic
    private Long deletedAt;
}
