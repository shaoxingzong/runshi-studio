package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_ai_session 表的数据访问层（AI 会话）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：已删除会话必须查不出来，否则用户凭旧 ID 还能继续往里写消息。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAiSessionMapper extends BaseMapper<StudioAiSession> {
}
