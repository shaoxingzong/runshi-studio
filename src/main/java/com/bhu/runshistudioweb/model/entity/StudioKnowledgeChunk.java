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
 * 知识库切分块实体（对应 studio_knowledge_chunk）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_knowledge_chunk")
public class StudioKnowledgeChunk implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文档 ID（关联 studio_knowledge_doc.id），走 uk_doc_chunk 最左前缀 */
    private Long docId;

    /** 块序号（同一文档内从 0 开始递增，决定上下文拼接顺序） */
    private Integer chunkIndex;

    /** 块正文（切分后的文本） */
    private String content;

    /** 向量库条目 ID（LangChain4j EmbeddingStore）；可空，允许「块已入库、向量未写入」以便重试 */
    private String embeddingId;

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

    /** 逻辑删除毫秒时间戳（0-未删除，非0-已删除）；参与 uk_doc_chunk，「删旧块 + 插新块」不会撞键 */
    @TableLogic
    private Long deletedAt;
}
