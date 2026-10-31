import { http } from './request';
import type { PageResult, ReportTicket } from './types';

/** 举报接口（用户提交 + 管理端处置） */
export const reportApi = {
    /** 提交举报（需登录） */
    submit: (body: { targetType: 'post' | 'comment'; targetId: string; reason: string }) =>
        http.post<string>('/post/report', body),

    /** 管理端举报分页 */
    adminList: (params: {
        current?: number;
        pageSize?: number;
        status?: number;
        targetType?: string;
    }) => http.get<PageResult<ReportTicket>>('/admin/report/list/page', params),

    /** 处置举报 */
    handle: (body: { id: string; action: 'resolve' | 'delete' | 'dismiss'; handleResult?: string }) =>
        http.post<boolean>('/admin/report/handle', body),
};
