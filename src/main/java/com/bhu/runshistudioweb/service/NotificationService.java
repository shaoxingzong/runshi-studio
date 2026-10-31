package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.model.dto.notification.NotificationQueryRequest;
import com.bhu.runshistudioweb.model.vo.notification.NotificationVO;

/**
 * 站内通知服务
 *
 * @author shaoshing
 */
public interface NotificationService {

    /**
     * 投递一条通知（内部方法，触发点调用）
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
     * @param request 查询条件（isRead 过滤 + 分页）；可为 null，按全部 + 第一页处理
     * @return 分页结果，记录为 NotificationVO；没有通知时 records 为空列表
     */
    Page<NotificationVO> listMy(NotificationQueryRequest request);

    /**
     * 我的未读通知数（铃铛轮询专用）
     *
     * @return 未读条数；没有未读时返回 0
     */
    long unreadCount();

    /**
     * 标记单条通知已读（幂等：重复已读不刷新 read_at）
     *
     * @param id 通知 ID，必须大于 0
     * @throws BusinessException 通知不存在、或不属于当前登录用户时抛出（A0402）
     */
    void markRead(Long id);

    /** 全部标记已读 */
    int markAllRead();
}
