import { http } from './request';
import type { PageResult, ReportTicket } from './types';

/**
 * 举报接口（用户提交 + 管理端处置）
 *
 * 用户端与后台放在同一个文件：它们操作的是同一张举报表，分开写会让
 * 「提交时的 targetType 取值」与「后台筛选的同名字段」各维护一份，迟早漂移。
 */
export const reportApi = {
    /**
     * 提交举报（需登录）
     *
     * targetId 必须原样传**字符串**：雪花 ID 是 19 位，写成 Number 会丢精度，
     * 后端按错误 ID 查不到内容，只会得到「内容不存在」。
     */
    submit: (body: { targetType: 'post' | 'comment'; targetId: string; reason: string }) =>
        http.post<string>('/post/report', body),

    /**
     * 管理端举报分页
     *
     * status 不传时后端默认只返回待处理——举报页的首要任务是清空队列。
     */
    adminList: (params: {
        current?: number;
        pageSize?: number;
        status?: number;
        targetType?: string;
    }) => http.get<PageResult<ReportTicket>>('/admin/report/list/page', params),

    /**
     * 处置举报
     *
     * resolve=撤下（转已驳回，可追溯）/ delete=删除 / dismiss=举报不成立。
     * 处置结果会通知举报人，并写入操作日志。
     */
    handle: (body: { id: string; action: 'resolve' | 'delete' | 'dismiss'; handleResult?: string }) =>
        http.post<boolean>('/admin/report/handle', body),
};
