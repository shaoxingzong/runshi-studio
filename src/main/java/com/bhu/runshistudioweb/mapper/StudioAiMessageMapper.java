package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_ai_message 表的数据访问层（AI 对话消息）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：created_at 只有秒级精度，排序必须追加 id 作次级键，否则对话记录偶发颠倒。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioAiMessageMapper extends BaseMapper<StudioAiMessage> {
}
