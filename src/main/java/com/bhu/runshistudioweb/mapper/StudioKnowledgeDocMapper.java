package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_knowledge_doc 表的数据访问层（知识库文档）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：按来源定位要写成 eq(sourceType).eq(sourceId) 才命中 idx_source 最左前缀；
 * 本表不存向量，向量在 LangChain4j 的 EmbeddingStore 里，一致性由上层 Service 保证。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioKnowledgeDocMapper extends BaseMapper<StudioKnowledgeDoc> {
}
