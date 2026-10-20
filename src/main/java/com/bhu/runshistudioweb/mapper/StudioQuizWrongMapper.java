package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_wrong 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，这里不写任何方法。
 * 错题本的状态迁移全部由 {@code QuizWrongServiceImpl#applyOutcome} 一处完成，
 * 且**只通过实体更新**（不写 {@code setSql} 自增）——因为每次迁移都要同时改
 * {@code status / wrongCount / correctStreak / resolvedAt / resolveType} 五个字段，
 * 用「读出来改再写回」比原子自增更直观，且错题本的并发量极低（同一用户同一时刻
 * 只会答一道题），不存在计数被覆盖的实际风险。
 *
 * <p><b>唯一键 {@code uk_user_question} 带 {@code deleted_at}</b>：
 * 否则「移出错题本后再答错」无法重新入本（全项目铁律）。
 * 应用层先查后插，该唯一键兜住并发下的重复插入。
 */
@Mapper
public interface StudioQuizWrongMapper extends BaseMapper<StudioQuizWrong> {
}
