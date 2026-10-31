package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_knowledge_doc 表的数据访问层（知识库文档）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioKnowledgeDocMapper extends BaseMapper<StudioKnowledgeDoc> {
}
