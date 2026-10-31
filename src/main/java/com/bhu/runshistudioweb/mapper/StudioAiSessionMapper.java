package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_ai_session 表的数据访问层（AI 会话）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAiSessionMapper extends BaseMapper<StudioAiSession> {
}
