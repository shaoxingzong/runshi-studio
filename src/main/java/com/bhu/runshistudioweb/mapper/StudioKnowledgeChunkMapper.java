package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_knowledge_chunk 表的数据访问层（知识库切分块）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioKnowledgeChunkMapper extends BaseMapper<StudioKnowledgeChunk> {
}
