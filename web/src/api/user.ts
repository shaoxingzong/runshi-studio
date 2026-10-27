import { http } from './request';
import type { LoginResult, Member } from './types';

/**
 * C 端用户接口（门户使用）
 *
 * 门户和后台调的是同一套后端接口（`/user/login`），区别只在页面：门户提供注册 + 登录，
 * 后台只有登录（管理员账号由管理方分配）。注册出来只能是 `user`，「成员」身份由管理员在后台改。
 */
export const userApi = {
    // 注册
    //
    // @returns 新用户 ID（字符串形式的雪花 ID）
    register: (userAccount: string, userPassword: string, checkPassword: string) =>
        http.post<string>('/user/register', { userAccount, userPassword, checkPassword }),

    // 登录：返回脱敏用户信息 + token（token 存 localStorage，由请求层自动带上）
    login: (userAccount: string, userPassword: string) =>
        http.post<LoginResult>('/user/login', { userAccount, userPassword }),

    // 当前登录用户：刷新页面后用它恢复登录态（比只信 localStorage 里的账号名可靠）
    current: () => http.get<LoginResult>('/user/current'),

    // 我的成员档案：在队则返回档案，不是成员返回 null
    //
    // 判断「我是不是在队成员」必须走这个接口，而不是看 LoginResult#userRole：
    // 角色是权限标签，可能与 studio_member 档案不同步，
    // 只按角色判断会出现「发帖按钮在那儿，点了却被后端拒绝」。
    mineMember: () => http.get<Member | null>('/member/mine'),

    // 注销：清服务端会话与 Cookie
    //
    // 静默：令牌可能已经过期（后端会返回 A0201），此时本地状态照样要清。
    // 若让请求层弹一条「未登录」，用户看到的顺序是「红色错误 → 已退出登录」，
    // 会误以为退出失败——而实际上退出流程已经完整走完了。
    logout: () => http.post<boolean>('/user/logout', {}, { silent: true }),
};
