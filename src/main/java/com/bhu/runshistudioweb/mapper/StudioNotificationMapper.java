package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * studio_notification 数据访问层
 *
 * 继承 BaseMapper 即自动获得单表 CRUD，这里不写任何方法——
 * 与 StudioQuizWrongMapper 同一纪律：查询全部用 LambdaQueryWrapper 表达，
 * 不上手写 SQL（本表没有任何聚合/复杂关联需求）。
 *
 * 两条索引的用途（建表语句见 db/user.sql 第 20 张表）：
 * - uk_receiver_biz：幂等的数据库层保证，同一事件重复投递撞键即已知存在；
 * - idx_receiver_read：列表（receiver + is_read 等值 + 创建时间倒序）
 * 与未读计数一条索引全覆盖，ORDER BY 不走 filesort。
 *
 * @author shaoshing
 */
@Mapper
public interface StudioNotificationMapper extends BaseMapper<StudioNotification> {
}
