package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioKnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档数据访问层（对应 studio_knowledge_doc）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，本期不需要写任何方法：
 * 同步任务只需要「按 (source_type, source_id) 查 / 插入 / 更新状态」，
 * 都能用 {@code LambdaQueryWrapper} 表达。
 *
 * <p><b>两条使用提醒</b>：
 * <ol>
 *     <li>按来源定位文档时条件顺序要写成 {@code eq(sourceType).eq(sourceId)}，
 *     才能命中 {@code idx_source (source_type, source_id)} 的最左前缀；</li>
 *     <li>本表<b>不存向量</b>：向量的增删在 LangChain4j 的 {@code EmbeddingStore} 里，
 *     两者的一致性由上层 Service 保证（先写库拿到 id，再用它当向量条目 id）。</li>
 * </ol>
 *
 * <p>逻辑删除由 {@code @TableLogic} 驱动：所有查询自动追加 {@code deleted_at = 0}。
 */
@Mapper
public interface StudioKnowledgeDocMapper extends BaseMapper<StudioKnowledgeDoc> {
}
