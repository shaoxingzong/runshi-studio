package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_like 表的数据访问层（点赞）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostLikeMapper extends BaseMapper<StudioPostLike> {
}
