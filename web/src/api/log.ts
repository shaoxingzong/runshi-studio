import { http } from './request';
import type { OperationActionOption, OperationLog, PageResult } from './types';

/**
 * 操作日志接口（管理端，后端 /admin/log）
 *
 * 只读：写日志由后端各业务 Service 在处置动作里完成，前端不提供「补一条日志」的能力——
 * 能补写的审计记录等于没有审计记录。
 */
export const logApi = {
    /**
     * 操作日志分页（时间倒序）
     *
     * 五个条件全部选填，不传即「不按该维度过滤」。时间格式固定
     * `YYYY-MM-DD HH:mm:ss`（与后端 @DateTimeFormat 的约定一致），
     * 不要传带 T 的 ISO 串——那会绑定失败变成系统错误。
     */
    list: (params: {
        current?: number;
        pageSize?: number;
        startTime?: string;
        endTime?: string;
        action?: string;
        operatorKeyword?: string;
        targetType?: string;
    }) => http.get<PageResult<OperationLog>>('/admin/log/list/page', params),

    /**
     * 动作字典（下拉与标签文案）
     *
     * 与后端校验同源：前端不硬编码「动作码 → 中文」，
     * 否则后端新增动作后会出现「有记录但筛不出来」的静默问题。
     */
    actions: () => http.get<OperationActionOption[]>('/admin/log/actions'),
};
