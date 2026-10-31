package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_question 表的数据访问层（题库）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizQuestionMapper extends BaseMapper<StudioQuizQuestion> {
}
