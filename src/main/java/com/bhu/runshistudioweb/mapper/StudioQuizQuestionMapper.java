package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_question 表的数据访问层（题库）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：按教材 + 章节取题必须带 status 并走 idx_scope；顺序模式还要按 question_seq, id
 * 排序，索引最后一列是 id 正是为了让「同题序」的行次序稳定。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizQuestionMapper extends BaseMapper<StudioQuizQuestion> {
}
