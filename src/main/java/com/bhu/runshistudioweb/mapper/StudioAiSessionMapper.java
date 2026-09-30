package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话数据访问层（对应 studio_ai_session）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，本类不需要写任何方法：
 * 会话的定位（按 ID 查）、创建、刷新 updated_at 都是单条操作。
 *
 * <p>逻辑删除由 {@code @TableLogic} 驱动：所有查询自动追加 {@code deleted_at = 0}。
 * 这一点在会话场景格外重要——已删除的会话必须查不出来，
 * 否则用户凭旧 ID 还能继续往「已删除会话」里写消息。
 */
@Mapper
public interface StudioAiSessionMapper extends BaseMapper<StudioAiSession> {
}
