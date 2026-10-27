package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post 表的数据访问层（帖子）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：公开列表、审核队列、我的发帖各走一条索引——审核队列不能借用公开列表那条，
 * 它的第二列是 pinned，优化器无法跳过中间列按 created_at 排序。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostMapper extends BaseMapper<StudioPost> {
}
