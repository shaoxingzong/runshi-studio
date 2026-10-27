package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.notification.NotificationQueryRequest;
import com.bhu.runshistudioweb.model.vo.notification.NotificationVO;

/**
 * 站内通知服务
 *
 * 两组方法职责完全不同：投递（notify）是内部能力，由业务触发点在同一事务内调用，
 * 幂等、不给自己发、失败不拖垮主业务；消费（列表 / 未读数 / 已读）由用户操作自己的通知，
 * 当前登录人从 Sa-Token 取，归属校验在 Service 做（他人的通知按不存在处理）。
 *
 * 约定：参数不合法 / 归属不符抛 BusinessException；notify 是例外——
 * 它是「顺带完成」的附属写，任何失败都只记日志不抛出。
 *
 * @author shaoshing
 */
public interface NotificationService {

    /**
     * 投递一条通知（内部方法，触发点调用）
     *
     * 幂等由唯一键 uk_receiver_biz（receiver_id + type + biz_type + biz_id + deleted_at）保证，
     * 冲突即视为已投递、静默返回。触发点自身的状态守卫是第一道防线，唯一键是第二道——
     * 重放、重试与并发都可能绕过第一道。
     *
     * 本方法与调用方同一事务，不另开独立事务：独立事务会产生「主业务回滚了、通知却已发出」。
     * 其它异常 catch 后只记日志，此时事务大概率已损坏，与主业务一起回滚是可接受的最坏结果。
     * title / content 存的是快照而不是模板引用：帖子事后改名、评论被删，
     * 都不该改写用户已经收到的文字。
     *
     * @param receiverId 接收者用户 ID；为 null 时直接跳过
     * @param actorId 触发者用户 ID，可为 null（系统动作没有操作者）；与 receiverId 相等则跳过
     * @param type 通知类型（见 NotificationTypeEnum）；为 null 时直接跳过
     * @param bizType 来源业务类型：POST / COMMENT / USER，可为 null
     * @param bizId 来源业务 ID，可为 null；参与构成幂等键
     * @param title 通知标题（一句话摘要，快照）
     * @param content 通知正文（快照）
     */
    void notify(Long receiverId, Long actorId, String type,
                String bizType, Long bizId, String title, String content);

    /**
     * 分页查询「我的」通知（新的在前）
     *
     * 查询范围由服务端从登录态决定，不接受前端传 userId，否则任何人都能翻别人的通知。
     * 分页参数的非法值做兜底纠正而不是报错。
     *
     * 坑：排序用 created_at，正好是 idx_receiver_read 的最后一列，按接收者查时索引能直接
     * 给出有序结果。换成 updated_at 就用不上这条索引了。
     *
     * @param request 查询条件（isRead 过滤 + 分页）；可为 null，按全部 + 第一页处理
     * @return 分页结果，记录为 NotificationVO；没有通知时 records 为空列表
     */
    Page<NotificationVO> listMy(NotificationQueryRequest request);

    /**
     * 我的未读通知数（铃铛轮询专用）
     *
     * 单独一个 COUNT 接口而不是让前端拉列表自己数：铃铛要周期性轮询，
     * 拉整页数据只为数个数，流量与数据库压力都不划算。
     *
     * 坑：两个等值条件必须成对出现——只带接收者会把已读算进来，只带未读会统计全站。
     *
     * @return 未读条数；没有未读时返回 0
     */
    long unreadCount();

    /**
     * 标记单条通知已读（幂等：重复已读不刷新 read_at）
     *
     * 他人的通知按「不存在」处理（A0402）而不是「无权访问」——后者等于确认了这条通知存在。
     * 只在「当前是未读」时才改成已读，重复点击匹配 0 行、read_at 保持不变。
     *
     * 坑：不要写成「先查出来、set 已读、updateById」，那样每次调用都会刷新 read_at，
     * 并发下两个请求还会都认为自己是第一次。
     *
     * @param id 通知 ID，必须大于 0
     * @throws BusinessException 通知不存在、或不属于当前登录用户时抛出（A0402）
     */
    void markRead(Long id);

    /**
     * 全部标记已读
     *
     * 只更新未读的那些，因此重复调用第二次返回 0。返回值有意义——
     * 前端据此判断「有没有发生变化」，决定要不要重新拉列表。
     *
     * 坑：条件里必须带未读。少了它，已读通知的 read_at 会被整体刷新成「现在」，
     * 让「最近读过的通知」排序全部错乱。
     *
     * @return 本次被置为已读的条数（已是已读的不计入）
     */
    int markAllRead();
}
