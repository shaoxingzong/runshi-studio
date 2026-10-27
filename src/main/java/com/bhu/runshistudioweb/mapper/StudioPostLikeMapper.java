package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_like 表的数据访问层（点赞）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：「当前用户赞了这批里的哪几篇」要一次 selectList(in(post_id).eq(user_id)) 查完，
 * 逐篇各查一次就是 N+1；uk_post_user 是「一人一赞」的唯一性根基。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostLikeMapper extends BaseMapper<StudioPostLike> {
}
