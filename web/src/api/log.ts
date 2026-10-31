import { http } from './request';
import type { OperationActionOption, OperationLog, PageResult } from './types';

/** 操作日志接口（管理端，后端 /admin/log） */
export const logApi = {
    /** 操作日志分页（时间倒序） */
    list: (params: {
        current?: number;
        pageSize?: number;
        startTime?: string;
        endTime?: string;
        action?: string;
        operatorKeyword?: string;
        targetType?: string;
    }) => http.get<PageResult<OperationLog>>('/admin/log/list/page', params),

    /** 动作字典（下拉与标签文案） */
    actions: () => http.get<OperationActionOption[]>('/admin/log/actions'),
};
