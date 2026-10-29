import { computed, onScopeDispose, ref } from 'vue';
import { defineStore } from 'pinia';
import { AUTH_EXPIRED_EVENT, tokenStore } from '@/api/request';
import { userApi } from '@/api/user';
import type { Member } from '@/api/types';

const ACCOUNT_KEY = 'user_account';
const ROLE_KEY = 'user_role';

/**
 * 登录态（Pinia）
 *
 * 令牌存在 localStorage（刷新不丢），这里只做响应式包装——组件不要直接读 localStorage，
 * 否则登录/退出后界面不会自动更新。role 同样持久化，刷新后路由守卫才不会误判。
 */
export const useUserStore = defineStore('user', () => {
    const token = ref(tokenStore.get());
    const account = ref(localStorage.getItem(ACCOUNT_KEY) || '');
    /** user / member / admin（后端原始值，判断时不要写中文或大小写变体） */
    const role = ref(localStorage.getItem(ROLE_KEY) || '');

    const isLogin = computed(() => !!token.value);

    /**
     * 是否管理员，只用于界面判断（是否显示后台入口）
     *
     * 真正的权限判定在后端 `@SaCheckRole("admin")`——前端判断可以被绕过，永远只是体验层。
     */
    const isAdmin = computed(() => role.value === 'admin');

    /**
     * 当前用户的在队成员档案（由 `/member/mine` 填充；null 表示不是成员）
     *
     * 不用 role 判断：role 是权限标签，可能与 studio_member 档案不同步。
     * 「能不能发帖 / 打卡」由档案说了算，入口以它为准，否则会出现「按钮在那儿，点了却被拒绝」。
     */
    const memberProfile = ref<Member | null>(null);
    const isMember = computed(() => !!memberProfile.value);

    /**
     * 监听登录失效事件：token 过期 / 被顶下线时把内存里的登录态一并回收
     *
     * request.ts 遇到 A0201 只清了 localStorage，内存里的 token / role 还在，界面照常显示
     * 「已登录」而此后每个请求都失败——只清一半比不清更糟。
     */
    function onAuthExpired() {
        token.value = '';
        account.value = '';
        role.value = '';
        memberProfile.value = null;
        tokenStore.clear();
        localStorage.removeItem(ACCOUNT_KEY);
        localStorage.removeItem(ROLE_KEY);
    }

    window.addEventListener(AUTH_EXPIRED_EVENT, onAuthExpired);
    onScopeDispose(() => window.removeEventListener(AUTH_EXPIRED_EVENT, onAuthExpired));

    /**
     * 拉取「我的成员档案」：决定要不要显示成员专属入口（发帖 / 打卡）
     *
     * 失败按「不是成员」处理：未登录、接口异常都可恢复，不该让整个布局崩掉，真正的拦截在后端。
     */
    async function loadMemberProfile() {
        if (!token.value) {
            memberProfile.value = null;
            return;
        }
        try {
            memberProfile.value = await userApi.mineMember();
        } catch {
            memberProfile.value = null;
        }
    }

    function setLogin(value: string, name: string, userRole = '') {
        token.value = value;
        account.value = name;
        role.value = userRole;
        tokenStore.set(value);
        localStorage.setItem(ACCOUNT_KEY, name);
        localStorage.setItem(ROLE_KEY, userRole);
        // 登录后立刻补拉一次：布局不会重建，等不到下一次 onMounted
        void loadMemberProfile();
    }

    /**
     * 刷新当前账号的昵称与角色（管理员改角色后不必重新登录）
     *
     * 为什么需要：role 只在登录那一刻写过。管理员把某人从 member 改成 admin（或反过来）
     * 之后，对方的界面要等重新登录才更新——后台入口该出现的没出现，该消失的还在那儿。
     * 页面挂载时拉一次 /user/current（轻量、只读）即可。
     *
     * 失败静默：角色刷新是体验优化，不能因为一次网络抖动打断用户；
     * 真正的 A0201 由请求层统一处理（会清掉登录态）。
     */
    async function refreshProfile() {
        if (!token.value) {
            return;
        }
        try {
            const current = await userApi.current();
            account.value = current.userAccount || account.value;
            role.value = current.userRole || '';
            localStorage.setItem(ACCOUNT_KEY, account.value);
            localStorage.setItem(ROLE_KEY, role.value);
        } catch {
            // 静默：见方法注释
        }
    }

    function logout() {
        onAuthExpired();
    }

    return {
        token,
        account,
        role,
        isLogin,
        isAdmin,
        memberProfile,
        isMember,
        loadMemberProfile,
        refreshProfile,
        setLogin,
        logout,
    };
});
