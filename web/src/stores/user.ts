import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { tokenStore } from '@/api/request';
import { userApi } from '@/api/user';
import type { Member } from '@/api/types';

const ACCOUNT_KEY = 'user_account';
const ROLE_KEY = 'user_role';

// 登录态（Pinia）
//
// 令牌本身存在 localStorage（刷新不丢），这里只做一层响应式包装，
// 让导航栏能随登录状态变化——不要在各组件里直接读 localStorage，
// 那样登录/退出后界面不会自动更新。
//
// `role` 同样持久化：门户导航要显示「成员/管理员」不同的入口，
// 后台路由守卫要判断是否 admin，刷新页面后不能因为内存丢失而误判。
export const useUserStore = defineStore('user', () => {
    const token = ref(tokenStore.get());
    const account = ref(localStorage.getItem(ACCOUNT_KEY) || '');
    // user / member / admin（后端原始值，判断时不要写中文或大小写变体）
    const role = ref(localStorage.getItem(ROLE_KEY) || '');

    const isLogin = computed(() => !!token.value);

    // 是否管理员
    //
    // 只用于界面判断（是否显示后台入口）。真正的权限判定在后端
    // `@SaCheckRole("admin")`——前端判断可以被绕过，永远只是体验层。
    const isAdmin = computed(() => role.value === 'admin');

    // 当前用户的在队成员档案（由 `/member/mine` 填充；null 表示不是成员）
    //
    // 为什么不直接用 role 判断：role 是权限标签，可能与 studio_member 档案不同步。
    // 「能不能发帖 / 打卡」由档案说了算，界面入口也就必须以它为准——
    // 否则会出现「按钮在那儿，点了却被后端拒绝」。
    const memberProfile = ref<Member | null>(null);
    const isMember = computed(() => !!memberProfile.value);

    // 拉取「我的成员档案」：决定要不要显示成员专属入口（发帖 / 打卡）
    //
    // 失败时按「不是成员」处理而不是抛错：未登录、接口异常都属于可恢复的情况，
    // 不该让整个布局崩掉。真正的拦截在后端业务接口里。
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
        token.value = '';
        account.value = '';
        role.value = '';
        memberProfile.value = null;
        tokenStore.clear();
        localStorage.removeItem(ACCOUNT_KEY);
        localStorage.removeItem(ROLE_KEY);
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
