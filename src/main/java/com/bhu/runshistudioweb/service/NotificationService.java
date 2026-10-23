package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.notification.NotificationQueryRequest;
import com.bhu.runshistudioweb.model.vo.notification.NotificationVO;

/**
 * 站内通知服务
 *
 * 方法分两组，职责完全不同：
 * - 投递（ notify）：内部能力，由业务触发点（审核结论、评论回复、
 * 身份授予）在同一事务内调用。幂等、不给自己发、失败不拖垮主业务——
 * 三条约束全在这一处，调用方不需要关心；
 * - 消费（列表 / 未读数 / 已读）：C 端用户操作自己的通知，
 * 当前登录人从 Sa-Token 取，归属校验在 Service 做（他人的通知按不存在处理）。
 *
 * 约定：参数不合法 / 归属不符抛 BusinessException；notify 是例外——
 * 它是「顺带完成」的附属写，任何失败都只记日志不抛出（主业务优先）。
 */
public interface NotificationService {

    // 投递一条通知（内部方法，触发点调用）
    //
    // 幂等：唯一键 uk_receiver_biz 冲突视为已投递，静默返回
    // （与帖子点赞撞 uk_post_user 同一套路）。触发点自身的状态守卫
    // （如审核只处理待审帖）是第一道，唯一键是第二道。
    //
    // 不给自己发：receiverId 与 actorId 相同时跳过——
    // 回复自己的评论是真实场景。判断放在这里而非每个调用处，单一防线更可靠。
    //
    // 失败不拖垮主业务：本方法与调用方同一事务（不加 REQUIRES_NEW——
    // 独立事务会产生「主业务回滚了、通知却已发出」，用户看到指向不存在结果的通知）。
    // 唯一键冲突 catch 后事务照常可用；其它异常（连接断等）catch 后记 error 日志，
    // 此时事务大概率已损坏，与主业务一起回滚是可接受的最坏结果。
    //
    // @param receiverId 接收者用户 ID（sys_user.id）
    // @param actorId 触发者用户 ID（可为 null——系统动作没有操作者；与 receiverId 相等则跳过）
    // @param type 通知类型（见 NotificationTypeEnum）
    // @param bizType 来源业务类型：POST / COMMENT / USER（可为 null）
    // @param bizId 来源业务 ID（可为 null）
    // @param title 通知标题（一句话摘要，快照）
    // @param content 通知正文（快照）
    void notify(Long receiverId, Long actorId, String type,
                String bizType, Long bizId, String title, String content);

    /**
     * 分页查询「我的」通知（新的在前）
     *
     * @param request 查询条件（isRead 过滤 + 分页；可为 null，按全部 + 第一页处理）
     * @return 分页结果（记录为 NotificationVO）
     */
    Page<NotificationVO> listMy(NotificationQueryRequest request);

    /**
     * 我的未读通知数（铃铛轮询专用）
     *
     * @return 未读条数；未登录时由调用方（Controller 的 @SaCheckLogin）先行拦截
     */
    long unreadCount();

    // 标记单条通知已读（幂等：重复已读不刷新 read_at——第一次阅读时间才有意义）
    //
    // @param id 通知 ID
    // @throws com.bhu.runshistudioweb.exception.BusinessException 通知不存在、或不属于当前登录用户时抛出（A0402）
    void markRead(Long id);

    /**
     * 全部标记已读
     *
     * @return 本次被置为已读的条数（已是已读的不计入——重复调用第二次返回 0）
     */
    int markAllRead();
}
