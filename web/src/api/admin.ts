import { http } from './request';
import type {
    AdminCertificate,
    AdminMember,
    AdminProject,
    AdminUser,
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

    // ===== 用户（「成员」身份就是在这里授予的）=====
    // 注册只能产生普通用户（user）；管理员在这里把某个账号的角色改成 member / admin，
    // 或封禁（userStatus=1）。后端 /user/list/page、/user/update 均要求 admin。
    userPage: (params: Record<string, unknown>) => http.get<PageResult<AdminUser>>('/user/list/page', params),
    // 入参用宽松类型：除了 AdminUser 的字段，还可能要传 userPassword（重置密码）；
    // 后端是「部分更新」语义——为 null 的字段不参与 UPDATE（见 UserUpdateRequest 的 javadoc）
    userUpdate: (body: { id: string } & Record<string, unknown>) => http.post<boolean>('/user/update', body),
    // 管理员直接建账号（不必让当事人去前台自助注册；前台注册只能得到普通 user 角色）。
    // userPassword 可省略：后端留空时生成随机初始密码（DTO 里是 @Size 选填）
    // ⚠️ 返回的不是 id 而是 { id, initialPassword }：随机口令只随创建响应出现一次，
    // 后端不存明文、查不回来——拿到后必须立即展示给管理员转交本人（见 Users.vue 的密码结果框）
    userAdd: (body: {
        userAccount: string;
        userPassword?: string;
        userName?: string;
        userRole?: string;
    }) => http.post<{ id: string; initialPassword: string }>('/user/add', body),
    // 删除账号（后端为逻辑删除）
    userDelete: (id: string) => http.post<boolean>('/user/delete', { id }),

    // ===== 成员 =====
    // ⚠️ 注意是 GET + 查询参数（后端的对象绑定），不是 POST。
    // 写成 POST 会得到 405 Method Not Allowed——这类错误在浏览器控制台才看得见。
    // 成员分页
    //
    // ⚠️ 返回的是 MemberVO（含 userId / sortOrder 等内部字段），
    // 不是 C 端脱敏的 Member。用错类型就看不到
    // 「这条档案绑定了哪个登录账号」——而绑定关系正是「把注册用户转为成员」的关键信息。
    memberPage: (params: Record<string, unknown>) =>
        http.get<PageResult<AdminMember>>('/member/list/page', params),
    // userId 可传：新增成员档案时顺便绑定登录账号（后端 MemberAddRequest.userId 是可选字段）。
    // 「后台把注册用户转为成员」就是靠它——不需要另开接口。
    // 入参同样按 MemberVO 的口径（AdminMember）：新增时可直接带 userId 完成绑定，
    // 编辑时 userId 传 null 表示「不修改绑定」（后端 MemberUpdateRequest 的既有语义）
    memberAdd: (body: Partial<AdminMember>) => http.post<string>('/member/add', body),
    memberUpdate: (body: Partial<AdminMember> & { id: string }) => http.post<boolean>('/member/update', body),
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
        http.get<PageResult<AdminCertificate>>('/certificate/list/page', params),
    certificateAdd: (body: Partial<AdminCertificate> & { title: string; awardLevel: string; awardType: string }) =>
        http.post<string>('/certificate/add', body),
    certificateUpdate: (body: Partial<AdminCertificate> & { id: string }) =>
        http.post<boolean>('/certificate/update', body),
    certificateDelete: (id: string) => http.post<boolean>('/certificate/delete', { id }),

    // ===== 知识库 =====
    docPage: (body: Record<string, unknown>) => http.post<PageResult<KnowledgeDoc>>('/knowledge/doc/list/page', body),
    docSync: (sourceType: string, sourceId: string) =>
        http.post<IngestResult>('/knowledge/doc/sync', { sourceType, sourceId: Number(sourceId) }),
    // 全量同步：幂等，内容未变的会跳过（零 Embedding 调用）
    docSyncAll: () => http.post<BatchResult>('/knowledge/doc/sync-all', {}),
    // 强制重建向量：应用重启后向量丢失时用它恢复（sync-all 此时会全部跳过，救不回来）
    docReindexAll: () => http.post<BatchResult>('/knowledge/doc/reindex-all', {}),
    docRebuild: (id: string) => http.post<IngestResult>('/knowledge/doc/rebuild', { id }),
    docDelete: (id: string) => http.post<boolean>('/knowledge/doc/delete', { id }),
    // 手工录入文档
    //
    // 每次调用都新建一篇（不幂等），title ≤128、content ≤20000（后端 @Size 校验）。
    // ⚠️ 后端会真实调用 Embedding，长文耗时可能超过请求层的 30 秒超时——
    // 界面上要提示「录入中请勿关闭」，别让人以为卡死了。
    docManual: (title: string, content: string) =>
        http.post<IngestResult>('/knowledge/doc/manual', { title, content }),
};
