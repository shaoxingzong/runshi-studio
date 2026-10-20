package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_quiz_question 数据访问层
 *
 * author: shaoshing
 *
 * <p>继承 {@link BaseMapper} 即自动获得单表 CRUD，因此这里<b>不需要写任何方法</b>。
 * 教材/章节树、章节题数这些聚合查询由 Service 的 {@code listMaps} +
 * {@code QueryWrapper.groupBy} 完成，不必引入 XML，也不必手写 {@code @Select}——
 * 全项目至今零手写 SQL，这条纪律对刷题模块同样适用。
 *
 * <p>三条要点（与其它 Mapper 一致）：
 * <ul>
 *     <li><b>逻辑删除自动生效</b>：实体有 {@code @TableLogic}，查询自动追加 {@code deleted_at = 0}；
 *     手写 SQL 时必须自己带该条件（本模块没有手写 SQL）；</li>
 *     <li><b>审计字段自动填充</b>：insert 触发 {@code MyMetaObjectHandler}，不用自己 set 时间；</li>
 *     <li><b>索引利用</b>：按教材+章节取题必须带 {@code status} 并走 {@code idx_scope}，
 *     顺序模式还要按 {@code question_seq, id} 排序——索引最后一列是 {@code id}，
 *     正是为了让「同题序」的行次序稳定。</li>
 * </ul>
 */
@Mapper
public interface StudioQuizQuestionMapper extends BaseMapper<StudioQuizQuestion> {
}
