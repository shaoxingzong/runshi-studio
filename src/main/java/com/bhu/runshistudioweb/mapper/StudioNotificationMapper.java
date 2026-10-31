package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_notification 表的数据访问层（站内通知）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioNotificationMapper extends BaseMapper<StudioNotification> {
}
