import { http } from './request';
import type { Notification, PageResult } from './types';

/**
 * 站内通知接口（C 端，全部要求登录）
 *
 * 与「后台登录」的关系：同一条登录态——门户登录后铃铛才有数据，
 * 未登录时 PortalLayout 不发任何请求（接口本身也要求登录）。
 */
export const notificationApi = {
    // 分页查询我的通知（新的在前）
    //
    // @param params.isRead 0-只要未读，1-只要已读，不传-全部
    list: (params: { current?: number; pageSize?: number; isRead?: number }) =>
        http.get<PageResult<Notification>>('/notification/page', { ...params }),

    // 未读通知数（铃铛轮询专用）
    //
    // 与列表分开的接口：轮询不该拉整页数据。60s 一次，COUNT 查询毫无压力。
    unreadCount: () => http.get<number>('/notification/unread-count'),

    // 标记单条已读（幂等：重复调用不刷新已读时间）
    markRead: (id: string) => http.post<boolean>('/notification/read', { id }),

    // 全部标记已读；返回本次实际更新的条数（第二次调用返回 0）
    markAllRead: () => http.post<number>('/notification/read-all', {}),
};
