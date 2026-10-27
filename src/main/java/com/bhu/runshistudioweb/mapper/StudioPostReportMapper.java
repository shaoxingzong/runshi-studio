package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_report 表的数据访问层（内容举报）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：uk_target_reporter 带 deleted_at 才能让「撤销后再举报」重新提交，不带就永远撞键；
 * targetId 软关联帖子或评论、无外键，删帖删评论时必须一并处置举报记录，否则留悬空举报。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostReportMapper extends BaseMapper<StudioPostReport> {
}
