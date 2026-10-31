import { http } from './request';
import type { Notification, PageResult } from './types';

/** 站内通知接口（C 端，全部要求登录） */
export const notificationApi = {
    list: (params: { current?: number; pageSize?: number; isRead?: number }) =>
        http.get<PageResult<Notification>>('/notification/page', { ...params }),

    unreadCount: () => http.get<number>('/notification/unread-count', undefined, { silent: true }),

    // 标记单条已读（幂等：重复调用不刷新已读时间）
    markRead: (id: string) => http.post<boolean>('/notification/read', { id }),

    markAllRead: () => http.post<number>('/notification/read-all', {}),
};
