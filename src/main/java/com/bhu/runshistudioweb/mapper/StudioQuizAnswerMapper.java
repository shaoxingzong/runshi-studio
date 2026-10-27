package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_answer 表的数据访问层（作答记录）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：本表只增不改不删，作答是审计凭证；幂等靠唯一键而非先查后插，并发双击撞键时
 * Service 必须接住 DuplicateKeyException 并返回首次判定，而不是报「本题已答」。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizAnswerMapper extends BaseMapper<StudioQuizAnswer> {
}
