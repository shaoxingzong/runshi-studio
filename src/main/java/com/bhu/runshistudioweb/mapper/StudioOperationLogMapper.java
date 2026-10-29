package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioOperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_operation_log 表的数据访问层（管理端操作日志）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：
 * 逻辑删除（查询自动加 deleted_at = 0）、审计填充、分页须走 selectPage。
 *
 * 坑：日志表只增不改，别给它加 update 路径——能改的审计记录等于没有审计记录。
 * 索引 idx_created / idx_operator_created / idx_action_created / idx_target
 * 分别覆盖「按时间翻页」「这个管理员干了什么」「这类动作有哪些」「这条内容被谁处置过」。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioOperationLogMapper extends BaseMapper<StudioOperationLog> {
}
