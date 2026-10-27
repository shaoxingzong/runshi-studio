package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_wrong 表的数据访问层（错题本）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：uk_user_question 带 deleted_at，否则「移出错题本后再答错」无法重新入本；
 * 状态迁移只由 QuizWrongServiceImpl#applyOutcome 一处完成，且走实体更新而非 setSql 自增。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizWrongMapper extends BaseMapper<StudioQuizWrong> {
}
