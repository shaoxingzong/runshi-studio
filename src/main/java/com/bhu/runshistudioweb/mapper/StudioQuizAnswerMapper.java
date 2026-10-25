package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_answer 数据访问层
 *
 * 继承 BaseMapper 即自动获得单表 CRUD，这里不写任何方法。
 *
 * 本表只增不改不删（与 StudioAttendanceMapper 同一条纪律）：
 * 作答是审计凭证，写进去就不再改动。因此这里不应该被调用
 * updateById / deleteById——需要「撤销」时靠逻辑删除标记，
 * 但那也应该由 DBA 手工处理，应用层不提供入口。
 *
 * 幂等靠唯一键，不靠先查后插：
 * uk_user_batch_question(user_id, batch_id, question_id, deleted_at)
 * 是「一题一批只判一次」的根基。并发双击时两个请求可能同时通过应用层查重，
 * 第二个会撞键抛 DuplicateKeyException，Service 必须接住它并
 * 重读已有记录、原样返回首次判定（而不是报错说「本题已答」）——
 * 断网重试是正常现象，让用户看到红条比看到判定结果更糟。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioQuizAnswerMapper extends BaseMapper<StudioQuizAnswer> {
}
