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
 * author: shaoshing
 *
 * <p>一份文档（{@link StudioKnowledgeDoc}）被切分成若干块，每块一行。
 * 检索的落点是「块」而不是「文档」：只有块级向量才能给出精准的语义匹配，
 * 命中后再通过 {@code docId} 回到文档、再通过 {@code sourceType / sourceId} 回到业务数据。
 *
 * <p><b>{@code embeddingId} 是两边关联的桥</b>：
 * 向量检索返回的是 {@code EmbeddingStore} 里的条目 ID，
 * 用它反查本表就能拿到块的正文与所属文档（走 {@code idx_embedding}）。
 * 它是<b>可空</b>的：创建块与写入向量是两步，允许「块已入库、向量写入中」的中间状态，
 * 这样即使向量库这一步失败，也能靠 status=2 重试而不必回滚整份切分。
 *
 * <p><b>{@code chunkIndex} 从 0 开始递增</b>：检索命中多个块时按它排序拼接，
 * 保证送给模型的上下文是原文顺序（乱序拼接会明显降低回答质量）。
 * 唯一索引 {@code uk_doc_chunk (doc_id, chunk_index, deleted_at)} 带上了
 * {@code deleted_at}——整份文档重建时先逻辑删除旧块再插新块，
 * 不带它的话第二次重建会直接撞唯一键。
 *
 * <p>审计字段、逻辑删除、雪花主键三条全局约定与其它 9 张表完全一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_knowledge_chunk")
public class StudioKnowledgeChunk implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属文档 ID（关联 studio_knowledge_doc.id），走 uk_doc_chunk 最左前缀
     */
    private Long docId;

    /**
     * 块序号（同一文档内从 0 开始递增，决定上下文拼接顺序）
     */
    private Integer chunkIndex;

    /**
     * 块正文（切分后的文本）
     */
    private String content;

    /**
     * 向量库条目 ID（LangChain4j EmbeddingStore 的条目标识）
     *
     * <p>可空：允许「块已入库、向量尚未写入」的中间状态，便于失败重试
     */
    private String embeddingId;

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
     * 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充）
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
     * <p>它参与唯一索引 uk_doc_chunk，因此「删旧块 + 插新块」的重建流程不会撞键
     */
    @TableLogic
    private Long deletedAt;
}
