package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post 表的数据访问层（帖子）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostMapper extends BaseMapper<StudioPost> {
}
