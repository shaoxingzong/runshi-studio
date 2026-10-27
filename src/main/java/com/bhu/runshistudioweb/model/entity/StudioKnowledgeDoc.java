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
 * 知识库文档实体（对应 studio_knowledge_doc，RAG 源文档元数据）
 *
 * 不存向量：向量在 LangChain4j 的 EmbeddingStore 里（换库不必改表），
 * 本表只存「是什么、来自哪条业务数据、切了几块、索引到哪一步」。
 * 向量可重建，元数据与溯源不可重建，混在一起换库时就得连元数据一起迁。
 *
 * sourceType + sourceId 是溯源字段（manual 来源的 sourceId 为 null）；
 * contentHash 做增量去重（Embedding 既慢又要钱）；status 0-待处理 / 1-已索引 / 2-失败。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_knowledge_doc")
public class StudioKnowledgeDoc implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 文档标题（检索结果溯源展示用） */
    private String title;

    /** 来源类型：manual 手工录入 / project 项目案例 / member 成员档案 / certificate 荣誉证书 */
    private String sourceType;

    /**
     * 来源业务数据 ID（溯源用）：指向对应业务表主键，manual 来源为 null
     * 刻意不建外键：来源可能是三张不同的表，外键表达不了这种多态来源
     */
    private Long sourceId;

    /** 正文内容哈希（增量更新去重：内容未变则跳过重建向量） */
    private String contentHash;

    /** 索引状态：0-待处理，1-已索引，2-失败（DDL 默认 0） */
    private Integer status;

    /** 已切分块数：与 studio_knowledge_chunk 的实际行数对齐 */
    private Integer chunkCount;

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

    /** 逻辑删除毫秒时间戳（0-未删除，非0-已删除）；文档逻辑删除时其下 chunk 必须同事务一并删除 */
    @TableLogic
    private Long deletedAt;
}
