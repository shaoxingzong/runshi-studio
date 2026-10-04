import { http } from './request';
import type {
    AdminProject,
    AdminUser,
    BatchResult,
    Certificate,
    IngestResult,
    KnowledgeDoc,
    LoginResult,
    Member,
    PageResult,
    Project,
} from './types';

/** 后台接口（除登录外全部要求 admin 角色） */
export const adminApi = {
    login: (userAccount: string, userPassword: string) =>
        http.post<LoginResult>('/user/login', { userAccount, userPassword }),

    // ===== 用户（「成员」身份就是在这里授予的） =====
    // 注册只能产生普通用户（user）；管理员在这里把某个账号的角色改成 member / admin，
    // 或封禁（userStatus=1）。后端 /user/list/page、/user/update 均要求 admin。
    userPage: (params: Record<string, unknown>) => http.get<PageResult<AdminUser>>('/user/list/page', params),
    // 入参用宽松类型：除了 AdminUser 的字段，还可能要传 userPassword（重置密码）；
    // 后端是「部分更新」语义——为 null 的字段不参与 UPDATE（见 UserUpdateRequest 的 javadoc）
    userUpdate: (body: { id: string } & Record<string, unknown>) => http.post<boolean>('/user/update', body),

    // ===== 成员 =====
    // ⚠️ 注意是 GET + 查询参数（后端的对象绑定），不是 POST。
    // 写成 POST 会得到 405 Method Not Allowed——这类错误在浏览器控制台才看得见。
    memberPage: (params: Record<string, unknown>) => http.get<PageResult<Member>>('/member/list/page', params),
    // userId 可传：新增成员档案时**顺便绑定登录账号**（后端 MemberAddRequest.userId 是可选字段）。
    // 「后台把注册用户转为成员」就是靠它——不需要另开接口。
    memberAdd: (body: Partial<Member> & { userId?: string }) => http.post<string>('/member/add', body),
    memberUpdate: (body: Partial<Member> & { id: string }) => http.post<boolean>('/member/update', body),
    memberDelete: (id: string) => http.post<boolean>('/member/delete', { id }),

    // ===== 项目 =====
    projectPage: (params: Record<string, unknown>) =>
        http.get<PageResult<AdminProject>>('/project/list/page', params),
    projectAdd: (body: Partial<Project> & { leaderId: string; description: string; title: string }) =>
        http.post<string>('/project/add', body),
    projectUpdate: (body: Partial<Project> & { id: string }) => http.post<boolean>('/project/update', body),
    projectDelete: (id: string) => http.post<boolean>('/project/delete', { id }),

    // ===== 证书 =====
    certificatePage: (params: Record<string, unknown>) =>
        http.get<PageResult<Certificate>>('/certificate/list/page', params),
    certificateAdd: (body: Partial<Certificate> & { title: string; awardLevel: string; awardType: string }) =>
        http.post<string>('/certificate/add', body),
    certificateUpdate: (body: Partial<Certificate> & { id: string }) =>
        http.post<boolean>('/certificate/update', body),
    certificateDelete: (id: string) => http.post<boolean>('/certificate/delete', { id }),

    // ===== 知识库 =====
    docPage: (body: Record<string, unknown>) => http.post<PageResult<KnowledgeDoc>>('/knowledge/doc/list/page', body),
    docSync: (sourceType: string, sourceId: string) =>
        http.post<IngestResult>('/knowledge/doc/sync', { sourceType, sourceId: Number(sourceId) }),
    /** 全量同步：幂等，内容未变的会跳过（零 Embedding 调用） */
    docSyncAll: () => http.post<BatchResult>('/knowledge/doc/sync-all', {}),
    /** 强制重建向量：应用重启后向量丢失时用它恢复（sync-all 此时会全部跳过，救不回来） */
    docReindexAll: () => http.post<BatchResult>('/knowledge/doc/reindex-all', {}),
    docRebuild: (id: string) => http.post<IngestResult>('/knowledge/doc/rebuild', { id }),
    docDelete: (id: string) => http.post<boolean>('/knowledge/doc/delete', { id }),
};
