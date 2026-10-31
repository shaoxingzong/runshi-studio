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
 * 项目案例实体（对应 studio_project）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_project")
public class StudioProject implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 项目名称（NOT NULL，≤128） */
    private String title;

    /** 项目封面 URL（一般先调 /file/upload 拿到相对路径再填这里） */
    private String coverImage;

    /** 项目摘要（NOT NULL，≤512）：列表页展示的短描述 */
    private String description;

    /** 项目详情正文（Markdown，RAG 切分数据源）：大字段，只有详情接口返回 */
    private String content;

    /** 技术栈的 JSON 字符串快照（如 ["Java","Spring Boot"]）：对外用 List<String>，转换在 Service */
    private String techStack;

    /** 在线体验地址 */
    private String demoUrl;

    /** 开源仓库地址 */
    private String githubUrl;

    /** 项目队长 ID（NOT NULL，权威数据源，关联 studio_member.id） */
    private Long leaderId;

    /** 项目状态：0-研发中，1-已上线，2-已结题（DDL 默认 1），取值见 ProjectStatusEnum */
    private Integer status;

    /** 展示置顶权重：数值越大越靠前（默认排序的第一关键字） */
    private Integer sortOrder;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Long deletedAt;
}
