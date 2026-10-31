import { http } from './request';
import { fileApi } from './file';
import type {
    PageResult,
    Post,
    PostAdminDetail,
    PostAudit,
    PostComment,
    PostCommentAudit,
    PostDetail,
    PostSelfDetail,
} from './types';

/** 帖子 / 评论接口 */
export const postApi = {
    // C 端（列表 / 详情游客可访问）

    // 公开帖子分页：仅已通过，置顶优先 + 时间倒序
    list: (params: Record<string, unknown>) => http.get<PageResult<Post>>('/post/list/page', params),

    // 帖子详情：含 Markdown 正文，后端会原子自增浏览量
    detail: (id: string) => http.get<PostDetail>('/post/detail', { id }),

    // 我的文章：含待审 / 已驳回（带理由），仅作者本人可见
    my: (params: Record<string, unknown>) => http.get<PageResult<PostAudit>>('/post/my', params),

    myDetail: (id: string) => http.get<PostSelfDetail>('/post/my/detail', { id }),

    // 发帖（仅工作室成员）
    add: (body: { title: string; content: string; summary?: string; coverImage?: string }) =>
        http.post<string>('/post/add', body),

    // 修改自己的文章：待审 / 驳回 / 已通过均可；保存后重新送审
    updateMine: (body: {
        id: string;
        title: string;
        content: string;
        summary?: string;
        coverImage?: string;
    }) => http.post<boolean>('/post/update-mine', body),

    like: (postId: string) => http.post<number>('/post/like', { postId }),

    // 某帖子的公开评论：已通过，楼中楼已由后端组装好
    commentList: (postId: string) => http.get<PostComment[]>('/post/comment/list', { postId }),

    // 发表评论 / 回复某条顶层评论（登录用户）
    commentAdd: (body: { postId: string; content: string; parentId?: string | null }) =>
        http.post<string>('/post/comment/add', body),

    // 管理端（全部要求 admin）

    // 帖子待审队列：仅待审，按提交时间先进先出
    pending: (params: Record<string, unknown>) =>
        http.get<PageResult<PostAudit>>('/admin/post/pending', params),

    audit: (body: { ids: string[]; action: 'approve' | 'reject'; rejectReason?: string }) =>
        http.post<number>('/admin/post/audit', body),

    // 评论待审队列
    commentPending: (params: Record<string, unknown>) =>
        http.get<PageResult<PostCommentAudit>>('/admin/post/comment/pending', params),

    // 批量审核评论（通过时后端会累加所属帖子的评论数）
    commentAudit: (body: { ids: string[]; action: 'approve' | 'reject'; rejectReason?: string }) =>
        http.post<number>('/admin/post/comment/audit', body),

    commentDelete: (id: string) => http.post<number>('/admin/post/comment/delete', { id }),

    // 管理端：帖子管理（全部要求 admin）

    adminList: (params: {
        current?: number;
        pageSize?: number;
        status?: number;
        titleKeyword?: string;
        authorKeyword?: string;
    }) => http.get<PageResult<PostAudit>>('/admin/post/list/page', params),

    delete: (id: string) => http.post<number>('/admin/post/delete', { id }),

    pin: (id: string, pinned: number) =>
        http.post<boolean>('/admin/post/pin', { id, pinned }),

    update: (body: {
        id: string;
        title: string;
        content: string;
        summary?: string;
        coverImage?: string;
    }) => http.post<boolean>('/admin/post/update', body),

    adminDetail: (id: string) => http.get<PostAdminDetail>('/admin/post/detail', { id }),
};

/**
 * 上传图片（发帖时往正文里插图用）：必须传到站内，外链图会被后端拒绝，base64 又会撑大正文
 *
 * @returns 相对访问路径，如 `/uploads/2026/10/xxxx.png`
 */
export function uploadPostImage(file: File): Promise<string> {
    return fileApi.upload(file);
}
