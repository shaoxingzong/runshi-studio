package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_notification 表的数据访问层（站内通知）
 *
 * 继承 BaseMapper 即得单表 CRUD，故接口为空。三个自动行为只对 MP 方法生效：逻辑删除
 * （查询自动加 deleted_at = 0、deleteById 实为 UPDATE）、审计填充、分页须走 selectPage。
 * 坑：uk_receiver_biz 是幂等的数据库层保证，重复投递撞键即已知存在；
 * idx_receiver_read 让列表与未读计数共用一条索引，ORDER BY 不走 filesort。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioNotificationMapper extends BaseMapper<StudioNotification> {
}
