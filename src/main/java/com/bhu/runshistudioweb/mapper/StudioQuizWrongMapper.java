package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_wrong 表的数据访问层（错题本）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizWrongMapper extends BaseMapper<StudioQuizWrong> {
}
