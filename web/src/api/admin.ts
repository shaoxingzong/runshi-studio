import { http } from './request';
import type {
    AdminCertificate,
    AdminMember,
    AdminProject,
    AdminUser,
    AttendanceCheckInRecord,
    AttendanceMonthStat,
    BatchResult,
    IngestResult,
    KnowledgeDoc,
    LoginResult,
    PageResult,
    Project,
} from './types';

/** 后台接口（除登录外全部要求 admin 角色） */
export const adminApi = {
    login: (userAccount: string, userPassword: string) =>
        http.post<LoginResult>('/user/login', { userAccount, userPassword }),

    userPage: (params: Record<string, unknown>) => http.get<PageResult<AdminUser>>('/user/list/page', params),
    userUpdate: (body: { id: string } & Record<string, unknown>) => http.post<boolean>('/user/update', body),
    userAdd: (body: {
        userAccount: string;
        userPassword?: string;
        userName?: string;
        userRole?: string;
    }) => http.post<{ id: string; initialPassword: string }>('/user/add', body),
    // 删除账号（后端为逻辑删除）
    userDelete: (id: string) => http.post<boolean>('/user/delete', { id }),
    // 重置某个用户的 AI 提问次数，返回重置前的已用次数（用于提示「已从 N 次清零」）
    userResetAiQuota: (id: string) => http.post<number>('/user/reset-ai-quota', { id }),

    memberPage: (params: Record<string, unknown>) =>
        http.get<PageResult<AdminMember>>('/member/list/page', params),
    memberAdd: (body: Partial<AdminMember>) => http.post<string>('/member/add', body),
    memberUpdate: (body: Partial<AdminMember> & { id: string }) => http.post<boolean>('/member/update', body),
    memberDelete: (id: string) => http.post<boolean>('/member/delete', { id }),

    // 项目
    projectPage: (params: Record<string, unknown>) =>
        http.get<PageResult<AdminProject>>('/project/list/page', params),
    projectAdd: (body: Partial<Project> & { leaderId: string; description: string; title: string }) =>
        http.post<string>('/project/add', body),
    projectUpdate: (body: Partial<Project> & { id: string }) => http.post<boolean>('/project/update', body),
    projectDelete: (id: string) => http.post<boolean>('/project/delete', { id }),

    // 证书
    certificatePage: (params: Record<string, unknown>) =>
        http.get<PageResult<AdminCertificate>>('/certificate/list/page', params),
    certificateAdd: (body: Partial<AdminCertificate> & { title: string; awardLevel: string; awardType: string }) =>
        http.post<string>('/certificate/add', body),
    certificateUpdate: (body: Partial<AdminCertificate> & { id: string }) =>
        http.post<boolean>('/certificate/update', body),
    certificateDelete: (id: string) => http.post<boolean>('/certificate/delete', { id }),

    attendanceSummary: (params: { month?: string }) =>
        http.get<AttendanceMonthStat[]>('/admin/attendance/summary', params),
    // 逐条记录：管理端需要看到每次签到的时间与来源 IP（成员端接口不返回 IP）
    attendanceRecords: (params: { userId: string; month?: string }) =>
        http.get<AttendanceCheckInRecord[]>('/admin/attendance/records', params),

    docPage: (body: Record<string, unknown>) => http.post<PageResult<KnowledgeDoc>>('/knowledge/doc/list/page', body),
    docSync: (sourceType: string, sourceId: string) =>
        http.long<IngestResult>('/knowledge/doc/sync', { sourceType, sourceId }),
    // 全量同步：幂等，内容未变的会跳过（零 Embedding 调用）
    docSyncAll: () => http.long<BatchResult>('/knowledge/doc/sync-all', {}),
    // 强制重建向量：应用重启后向量丢失时用它恢复（sync-all 此时会全部跳过，救不回来）
    docReindexAll: () => http.long<BatchResult>('/knowledge/doc/reindex-all', {}),
    docRebuild: (id: string) => http.long<IngestResult>('/knowledge/doc/rebuild', { id }),
    docDelete: (id: string) => http.post<boolean>('/knowledge/doc/delete', { id }),
    docManual: (title: string, content: string) =>
        http.long<IngestResult>('/knowledge/doc/manual', { title, content }),
};
