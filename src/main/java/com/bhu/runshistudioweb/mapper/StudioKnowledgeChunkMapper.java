package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_knowledge_chunk 表的数据访问层（知识库切分块）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：重建索引是「逻辑删旧块 → 插新块 → 更新 chunk_count」同一事务，唯一索引带
 * deleted_at 才不会撞键；按文档取块用 eq(docId).orderByAsc(chunkIndex) 即命中其最左前缀。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioKnowledgeChunkMapper extends BaseMapper<StudioKnowledgeChunk> {
}
