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
 * 知识库文档实体（对应 studio_knowledge_doc，RAG 源文档的<b>元数据</b>）
 *
 * author: shaoshing
 *
 * <p><b>本表最需要理解的一点：它不存向量。</b>
 * 向量本体存放在 LangChain4j 的 {@code EmbeddingStore} 中（本期为内存实现，
 * 后续可替换为 Redis / PGVector / Milvus 而无需改表），
 * 库里只存「这份文档是什么、来自哪条业务数据、切了几块、索引到哪一步」。
 * 这样划分的理由：向量是「可重建的派生数据」，而元数据与溯源是不可重建的业务事实——
 * 把两者混在一张表里，换向量库时会连元数据一起被迫迁移。
 *
 * <p><b>{@code sourceType} + {@code sourceId} 是 R2 的溯源字段</b>：
 * 检索命中后要走 {@code chunk → doc.source_type / source_id → 业务数据}，
 * 才能回答「这个答案是依据哪条项目/成员/证书生成的」。
 * {@code manual}（手工录入）来源没有业务数据，{@code sourceId} 为 null。
 *
 * <p><b>{@code contentHash} 用于增量更新去重</b>：重建索引前先比对哈希，
 * 内容没变就整份跳过——Embedding 调用既慢又要钱，重算整库是纯粹的浪费。
 *
 * <p><b>{@code status} 与 {@code chunkCount}</b>：
 * 0-待处理 / 1-已索引 / 2-失败（失败要能重试，所以必须落库）；
 * chunkCount 与 {@link StudioKnowledgeChunk} 的实际行数对齐，用于校验切分是否完整。
 *
 * <p>审计字段、逻辑删除、雪花主键三条全局约定与其它 8 张表完全一致，见 db/DESIGN.md。
 */
@Data
@TableName("studio_knowledge_doc")
public class StudioKnowledgeDoc implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 文档标题（检索结果溯源展示用）
     */
    private String title;

    /**
     * 来源类型：{@code manual} 手工录入 / {@code project} 项目案例 /
     * {@code member} 成员档案 / {@code certificate} 荣誉证书
     *
     * <p>取值字典以 {@code db/user.sql} 的列注释为准（DDL 注释即取值字典）；
     * 等第一个消费方（同步/检索 Service）落地时再补一个枚举类统一取值，
     * 现在只有 DDL 与实体注释两处，不会漂移
     */
    private String sourceType;

    /**
     * 来源业务数据 ID（R2 溯源字段）：指向对应业务表主键；{@code manual} 来源为 null
     *
     * <p>刻意<b>不建数据库外键</b>：来源可能是项目/成员/证书三张不同表，
     * 外键约束表达不了这种「多态来源」，只能靠 source_type 配合应用层解释
     */
    private Long sourceId;

    /**
     * 正文内容哈希（增量更新去重：内容未变则跳过重建向量）
     */
    private String contentHash;

    /**
     * 索引状态：0-待处理，1-已索引，2-失败（DDL 默认 0）
     */
    private Integer status;

    /**
     * 已切分块数：与 studio_knowledge_chunk 的实际行数对齐
     */
    private Integer chunkCount;

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
     * <p>文档逻辑删除时，其下所有 chunk 也必须逻辑删除（同一事务），
     * 否则检索会命中「文档已删、块还活着」的孤儿数据
     */
    @TableLogic
    private Long deletedAt;
}
