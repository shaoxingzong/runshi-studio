package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库切分块数据访问层（对应 studio_knowledge_chunk）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD。本期检索链路还没落地，
 * 因此不声明任何自定义方法；将来要按文档取全部块时，
 * 用 {@code eq(docId).orderByAsc(chunkIndex)} 即可——正好走
 * {@code uk_doc_chunk (doc_id, chunk_index, deleted_at)} 的最左前缀，
 * <b>不需要</b>再单独建一个 doc_id 索引（那会是冗余索引）。
 *
 * <p><b>重建索引的顺序</b>（写这段逻辑时对照看）：先逻辑删除该文档的旧块，
 * 再插入新块，最后更新文档的 chunk_count——三步在同一事务内；
 * 因为唯一索引带 {@code deleted_at}，删旧插新不会撞键。
 *
 * <p>逻辑删除由 {@code @TableLogic} 驱动：所有查询自动追加 {@code deleted_at = 0}，
 * 因此「文档已删、块还活着」这类孤儿数据不会出现在检索结果里。
 */
@Mapper
public interface StudioKnowledgeChunkMapper extends BaseMapper<StudioKnowledgeChunk> {
}
