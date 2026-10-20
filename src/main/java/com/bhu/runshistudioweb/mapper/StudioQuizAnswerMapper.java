package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_answer 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，这里不写任何方法。
 *
 * <p><b>本表只增不改不删</b>（与 {@code StudioAttendanceMapper} 同一条纪律）：
 * 作答是审计凭证，写进去就不再改动。因此这里<b>不应该</b>被调用
 * {@code updateById} / {@code deleteById}——需要「撤销」时靠逻辑删除标记，
 * 但那也应该由 DBA 手工处理，应用层不提供入口。
 *
 * <p><b>幂等靠唯一键，不靠先查后插</b>：
 * {@code uk_user_batch_question(user_id, batch_id, question_id, deleted_at)}
 * 是「一题一批只判一次」的根基。并发双击时两个请求可能同时通过应用层查重，
 * 第二个会撞键抛 {@code DuplicateKeyException}，Service 必须接住它并
 * <b>重读已有记录、原样返回首次判定</b>（而不是报错说「本题已答」）——
 * 断网重试是正常现象，让用户看到红条比看到判定结果更糟。
 */
@Mapper
public interface StudioQuizAnswerMapper extends BaseMapper<StudioQuizAnswer> {
}
