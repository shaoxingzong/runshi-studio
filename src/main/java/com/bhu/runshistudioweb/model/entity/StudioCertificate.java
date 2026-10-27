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
 * 级别（award_level）与类型（award_type）正交，禁止合并成单字段：
 * 合并后任一维度都无法独立筛选统计，只能 LIKE 模糊匹配，既走不了索引也会误匹配。
 *
 * 坑：award_date 用 LocalDate 且不得晚于今天；image_url 是 NOT NULL，须在入参侧校验；
 * 默认排序 = sort_order 倒序 + award_date 倒序，与组合索引同向，避免 filesort。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_certificate")
public class StudioCertificate implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 证书 / 获奖名称（NOT NULL） */
    private String title;

    /** 级别维度：national / provincial / municipal，取值见 CertificateLevelEnum */
    private String awardLevel;

    /** 类型维度：competition / soft_copyright / patent / paper，取值见 CertificateTypeEnum */
    private String awardType;

    /**
     * 获奖 / 颁发日期（date → LocalDate），业务上不得晚于今天
     * 由 DTO 的 @PastOrPresent 与 Service 双重校验
     */
    private LocalDate awardDate;

    /** 证书图片 URL（NOT NULL）：先调 /file/upload 拿到 /uploads/... 相对路径再填入 */
    private String imageUrl;

    /** 展示置顶权重：数值越大越靠前（默认排序的第一关键字） */
    private Integer sortOrder;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（datetime，映射 LocalDateTime） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除毫秒时间戳（0-未删除，非0-已删除）；@TableLogic 让查询自动追加 deleted_at = 0，删除改写为 UPDATE */
    @TableLogic
    private Long deletedAt;
}
