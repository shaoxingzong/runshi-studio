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

/**
 * 帖子 / 评论接口
 *
 * 单独成文件：帖子有自己的一整套资源（帖子、评论、审核、删除），与 portal / admin 都不重合。
 *
 * 先审后发：`add` 与 `commentAdd` 只返回新 ID，内容此时是待审状态，不会出现在公开列表里，
 * 调用方必须提示「已提交，审核通过后可见」，否则用户会以为提交失败。
 */
export const postApi = {
    // ==================== C 端（列表 / 详情游客可访问）====================

    // 公开帖子分页：仅已通过，置顶优先 + 时间倒序
    list: (params: Record<string, unknown>) => http.get<PageResult<Post>>('/post/list/page', params),

    // 帖子详情：含 Markdown 正文，后端会原子自增浏览量
    detail: (id: string) => http.get<PostDetail>('/post/detail', { id }),

    // 我的发帖：含待审 / 已驳回（带理由），仅作者本人可见
    my: (params: Record<string, unknown>) => http.get<PageResult<PostAudit>>('/post/my', params),

    // 读自己「未公开」帖子的正文（改驳回稿的前置步骤）
    //
    // 公开详情只返回已通过的帖子，驳回稿的正文只能从这里读；
    // 与公开详情不同，本接口不累加浏览量（自己预览不该算一次访问）。
    myDetail: (id: string) => http.get<PostSelfDetail>('/post/my/detail', { id }),

    // 发帖（仅工作室成员）
    add: (body: { title: string; content: string; summary?: string; coverImage?: string }) =>
        http.post<string>('/post/add', body),

    // 作者修改自己被驳回的帖子（改完重新送审）
    //
    // 仅本人 + 已驳回可调：其余情况后端按「帖子不存在」返回，
    // 不暴露存在性。保存后帖子回到待审——前端必须提示「已重新提交审核」。
    updateMine: (body: {
        id: string;
        title: string;
        content: string;
        summary?: string;
        coverImage?: string;
    }) => http.post<boolean>('/post/update-mine', body),

    // 点赞帖子（需登录）
    //
    // 只能赞、不能取消：后端幂等，重复点击不会重复计数。
    // 返回值就是点赞后的最新点赞数——前端直接用它刷新界面，
    // 不要自己做 +1（否则重复点击会让数字跳两次）。
    like: (postId: string) => http.post<number>('/post/like', { postId }),

    // 某帖子的公开评论：已通过，楼中楼已由后端组装好
    commentList: (postId: string) => http.get<PostComment[]>('/post/comment/list', { postId }),

    // 发表评论 / 回复某条顶层评论（登录用户）
    commentAdd: (body: { postId: string; content: string; parentId?: string | null }) =>
        http.post<string>('/post/comment/add', body),

    // ==================== 管理端（全部要求 admin）====================

    // 帖子待审队列：仅待审，按提交时间先进先出
    pending: (params: Record<string, unknown>) =>
        http.get<PageResult<PostAudit>>('/admin/post/pending', params),

    // 批量审核帖子
    //
    // @param body.ids 待处置的帖子 ID 列表
    // @param body.action approve（通过）/ reject（驳回）
    // @param body.rejectReason 驳回理由，驳回时后端强校验必填
    audit: (body: { ids: string[]; action: 'approve' | 'reject'; rejectReason?: string }) =>
        http.post<number>('/admin/post/audit', body),

    // 评论待审队列
    commentPending: (params: Record<string, unknown>) =>
        http.get<PageResult<PostCommentAudit>>('/admin/post/comment/pending', params),

    // 批量审核评论（通过时后端会累加所属帖子的评论数）
    commentAudit: (body: { ids: string[]; action: 'approve' | 'reject'; rejectReason?: string }) =>
        http.post<number>('/admin/post/comment/audit', body),

    // 删除评论（连带删除它的所有回复）
    //
    // @returns 实际删除的条数（含回复）
    commentDelete: (id: string) => http.post<number>('/admin/post/comment/delete', { id }),

    // ==================== 管理端：帖子管理（全部要求 admin）====================

    // 帖子全量列表（三种状态均可查）
    //
    // GET + 查询参数。筛选条件不传即「不按该条件过滤」：
    // status 不传 = 查全部状态（与待审队列的关键区别）。
    adminList: (params: {
        current?: number;
        pageSize?: number;
        status?: number;
        titleKeyword?: string;
        authorKeyword?: string;
    }) => http.get<PageResult<PostAudit>>('/admin/post/list/page', params),

    // 删除帖子（级联删除该帖全部评论与点赞关系）
    //
    // @returns 实际删除的内容行数（帖子 1 + 其下评论数）
    delete: (id: string) => http.post<number>('/admin/post/delete', { id }),

    // 置顶 / 取消置顶
    //
    // 后端只允许置顶「已通过」的帖子（置顶待审帖在公开列表无意义），
    // 前端也应把其它状态的开关置灰，别让用户点了才收到报错。
    pin: (id: string, pinned: number) =>
        http.post<boolean>('/admin/post/pin', { id, pinned }),

    // 编辑帖子
    //
    // 保存后后端会把非待审的帖子重置为待审重新送审，
    // 前端必须提示「已保存，修改内容将重新送审」——否则管理员会以为
    // 改完立刻生效，跑去门户发现文章「消失」了。
    update: (body: {
        id: string;
        title: string;
        content: string;
        summary?: string;
        coverImage?: string;
    }) => http.post<boolean>('/admin/post/update', body),

    // 管理端帖子详情（含 Markdown 正文）
    //
    // 编辑弹窗回填正文专用。为什么不能调公开的 detail：
    // 它只返回「已通过」的帖子（待审 / 已驳回取不到），
    // 而且会自增浏览量——管理员每点开一次编辑，热度计数就被污染一次。
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
