import { http } from './request';
import type { LoginResult, Member } from './types';

/** C 端用户接口（门户使用） */
export const userApi = {
    // 姓名用于站内展示；账号只作为登录凭据
    register: (userAccount: string, userName: string, userPassword: string, checkPassword: string) =>
        http.post<string>('/user/register', { userAccount, userName, userPassword, checkPassword }),

    // 登录：返回脱敏用户信息 + token（token 存 localStorage，由请求层自动带上）
    login: (userAccount: string, userPassword: string) =>
        http.post<LoginResult>('/user/login', { userAccount, userPassword }),

    // 当前登录用户：刷新页面后用它恢复登录态（比只信 localStorage 里的账号名可靠）
    current: () => http.get<LoginResult>('/user/current'),

    mineMember: () => http.get<Member | null>('/member/mine'),

    // 成员自助编辑本人档案：只开放展示字段，账号/届别/职务/状态不在请求体内
    updateMyMember: (body: Partial<Pick<Member, 'name' | 'avatar' | 'major' | 'direction' | 'githubUrl' | 'summary'>>) =>
        http.post<Member>('/member/mine/update', body),

    logout: () => http.post<boolean>('/user/logout', {}, { silent: true }),
};
