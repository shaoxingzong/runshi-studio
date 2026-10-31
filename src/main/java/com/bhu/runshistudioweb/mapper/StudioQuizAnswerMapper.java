package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_answer 表的数据访问层（作答记录）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizAnswerMapper extends BaseMapper<StudioQuizAnswer> {
}
