package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_post_comment 表的数据访问层（楼中楼评论）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioPostCommentMapper extends BaseMapper<StudioPostComment> {
}
