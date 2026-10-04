import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { tokenStore } from '@/api/request';

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
     * 只用于**界面**判断（是否显示后台入口）。真正的权限判定在后端
     * `@SaCheckRole("admin")`——前端判断可以被绕过，永远只是体验层。
     */
    const isAdmin = computed(() => role.value === 'admin');

    function setLogin(value: string, name: string, userRole = '') {
        token.value = value;
        account.value = name;
        role.value = userRole;
        tokenStore.set(value);
        localStorage.setItem(ACCOUNT_KEY, name);
        localStorage.setItem(ROLE_KEY, userRole);
    }

    function logout() {
        token.value = '';
        account.value = '';
        role.value = '';
        tokenStore.clear();
        localStorage.removeItem(ACCOUNT_KEY);
        localStorage.removeItem(ROLE_KEY);
    }

    return { token, account, role, isLogin, isAdmin, setLogin, logout };
});
