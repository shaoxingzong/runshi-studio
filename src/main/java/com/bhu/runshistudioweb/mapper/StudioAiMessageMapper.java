package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_ai_message 表的数据访问层（AI 对话消息）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAiMessageMapper extends BaseMapper<StudioAiMessage> {
}
