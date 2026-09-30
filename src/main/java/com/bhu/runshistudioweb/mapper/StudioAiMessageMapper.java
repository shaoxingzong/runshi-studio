package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioAiMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 消息数据访问层（对应 studio_ai_message）
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD。本模块的两处查询
 * （上下文窗口、历史记录）都是「按 session_id 取最近 N 条」，
 * 用 {@code LambdaQueryWrapper + Page} 即可表达，不需要自定义 SQL 与 XML。
 *
 * <p><b>提醒</b>：{@code created_at} 只有秒级精度，同秒的两条消息时间相同，
 * 因此排序一定要追加 {@code id} 作为次级键（雪花 ID 单调递增）。
 * 只按时间排序会导致对话记录偶发颠倒。
 */
@Mapper
public interface StudioAiMessageMapper extends BaseMapper<StudioAiMessage> {
}
