import { http } from './request';
import type { PageResult } from './types';

/** 事项状态：与后端 BoardItemStatusEnum 一致 */
export type BoardStatus = 0 | 1 | 2;

export const BOARD_STATUS_TEXT: Record<BoardStatus, string> = {
    0: '待办',
    1: '进行中',
    2: '已完成',
};

/** 工作室内部事项（后端 BoardItemVO） */
export interface BoardItem {
    id: string;
    title: string;
    // Markdown 原文：渲染前必须走 renderMarkdown + sanitizeMarkdownHtml
    description?: string | null;
    status: BoardStatus;
    statusLabel?: string;
    assigneeMemberId?: string | null;
    assigneeName?: string | null;
    assigneeAvatar?: string | null;
    // yyyy-MM-dd HH:mm:ss，与后端 JsonConfig 统一格式
    deadline?: string | null;
    completedAt?: string | null;
    sortOrder?: number;
    overdue?: boolean;
    canChangeStatus?: boolean;
    createdAt?: string;
    updatedAt?: string;
}

/** 成员看板聚合（三列 + 统计） */
export interface BoardOverview {
    todo: BoardItem[];
    doing: BoardItem[];
    done: BoardItem[];
    pendingCount: number;
    overdueCount: number;
    myPendingCount: number;
    doneCount: number;
}

/** 管理端分页查询参数 */
export interface BoardQuery {
    title?: string;
    status?: BoardStatus | null;
    assigneeMemberId?: string | null;
    onlyOverdue?: boolean;
    current?: number;
    pageSize?: number;
}

export const boardApi = {
    /** 成员看板：一次拿三列 + 统计 */
    board: () => http.get<BoardOverview>('/board/item/board'),

    /** 管理端分页列表 */
    page: (params: BoardQuery) => http.get<PageResult<BoardItem>>('/board/item/page', params as Record<string, unknown>),

    /** 新增事项：requestId 为一次性幂等令牌，失败重试须复用 */
    add: (body: {
        title: string;
        description?: string;
        status?: BoardStatus;
        assigneeMemberId?: string | null;
        deadline?: string | null;
        sortOrder?: number;
        requestId?: string;
    }) => http.post<string>('/board/item/add', body),

    /** 编辑：不传的字段表示不修改 */
    update: (body: {
        id: string;
        title?: string;
        description?: string;
        deadline?: string | null;
        clearDeadline?: boolean;
        sortOrder?: number;
    }) => http.post<boolean>('/board/item/update', body),

    delete: (id: string) => http.post<boolean>('/board/item/delete', { id }),

    /** 指派 / 改派 / 清空负责人（传 null 为清空） */
    assign: (id: string, assigneeMemberId: string | null) =>
        http.post<boolean>('/board/item/assign', { id, assigneeMemberId }),

    /**
     * 流转状态。expectedStatus 传页面上看到的状态，后端按乐观锁校验，
     * 两个人同时改同一条时后者会收到「状态已变更，请刷新」而不是静默覆盖。
     */
    changeStatus: (id: string, targetStatus: BoardStatus, expectedStatus: BoardStatus) =>
        http.post<boolean>('/board/item/status', { id, targetStatus, expectedStatus }),
};
