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
 * 令牌本身存在 localStorage（刷新不丢），这里只做一层响应式包装，
 * 让导航栏能随登录状态变化——不要在各组件里直接读 localStorage，
 * 那样登录/退出后界面不会自动更新。
 *
 * `role` 同样持久化：门户导航要显示「成员/管理员」不同的入口，
 * 后台路由守卫要判断是否 admin，刷新页面后不能因为内存丢失而误判。
 */
export const useUserStore = defineStore('user', () => {
    const token = ref(tokenStore.get());
    const account = ref(localStorage.getItem(ACCOUNT_KEY) || '');
    /** user / member / admin（后端原始值，判断时不要写中文或大小写变体） */
    const role = ref(localStorage.getItem(ROLE_KEY) || '');

    const isLogin = computed(() => !!token.value);

    /**
     * 是否管理员
     *
     * 只用于界面判断（是否显示后台入口）。真正的权限判定在后端
     * `@SaCheckRole("admin")`——前端判断可以被绕过，永远只是体验层。
     */
    const isAdmin = computed(() => role.value === 'admin');

    /**
     * 当前用户的在队成员档案（由 `/member/mine` 填充；null 表示不是成员）
     *
     * 为什么不直接用 role 判断：role 是权限标签，可能与 studio_member 档案不同步。
     * 「能不能发帖 / 打卡」由档案说了算，界面入口也就必须以它为准——
     * 否则会出现「按钮在那儿，点了却被后端拒绝」。
     */
    const memberProfile = ref<Member | null>(null);
    const isMember = computed(() => !!memberProfile.value);

    /**
     * 监听登录失效事件：token 过期 / 被顶下线时，把内存里的登录态一并回收
     *
     * 为什么必须有这一层：request.ts 遇到 A0201 只清了 localStorage，
     * 但内存里的 token / account / role 还在，界面照常显示「已登录」，
     * 而此后每个请求都会失败——用户看到的是「明明登录着却什么都不好用」，
     * 且没有任何恢复入口。只清一半比不清更糟：它制造了一个「假登录」状态。
     *
     * 用 window 事件而不是直接调 request.ts：stores/user 反过来依赖它的
     * tokenStore，直接互引会形成循环依赖（见 request.ts 里的说明）。
     *
     * onScopeDispose 保证组件卸载时解绑，否则同一监听会随每次创建重复注册。
     * 注意：Pinia 的 setup store 在首次使用时就建立了 effect scope，
     * 应用级单例只会注册一次，不会泄漏。
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
     * 失败时按「不是成员」处理而不是抛错：未登录、接口异常都属于可恢复的情况，
     * 不该让整个布局崩掉。真正的拦截在后端业务接口里。
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
        setLogin,
        logout,
    };
});
