import { http } from './request';
import type { LoginResult } from './types';

/**
 * C 端用户接口（门户使用）
 *
 * 与「后台登录」的关系：两者调的是**同一套后端接口**（`/user/login`），
 * 区别只在页面，不在接口：
 * - 门户：**注册 + 登录**——注册出来是普通用户（`user`），可聊天、有配额；
 * - 后台：**只有登录，不提供注册**——管理员账号不自己注册，由管理方分配。
 *
 * 「成员」身份也不在这里产生：注册只能拿到 `user`，
 * 管理员在后台「用户管理」里把某个账号的角色改成 `member`（或更高）。
 */
export const userApi = {
    /**
     * 注册
     *
     * @returns 新用户 ID（字符串形式的雪花 ID）
     */
    register: (userAccount: string, userPassword: string, checkPassword: string) =>
        http.post<string>('/user/register', { userAccount, userPassword, checkPassword }),

    /** 登录：返回脱敏用户信息 + token（token 存 localStorage，由请求层自动带上） */
    login: (userAccount: string, userPassword: string) =>
        http.post<LoginResult>('/user/login', { userAccount, userPassword }),

    /** 当前登录用户：刷新页面后用它恢复登录态（比只信 localStorage 里的账号名可靠） */
    current: () => http.get<LoginResult>('/user/current'),

    /** 注销：清服务端会话与 Cookie */
    logout: () => http.post<boolean>('/user/logout', {}),
};
