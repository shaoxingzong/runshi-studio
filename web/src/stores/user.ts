import { computed, onScopeDispose, ref } from 'vue';
import { defineStore } from 'pinia';
import { AUTH_EXPIRED_EVENT, tokenStore } from '@/api/request';
import { userApi } from '@/api/user';
import type { Member } from '@/api/types';

const ACCOUNT_KEY = 'user_account';
const ROLE_KEY = 'user_role';
const NAME_KEY = 'user_name';
const USER_ID_KEY = 'user_id';

/** 登录态（Pinia） */
export const useUserStore = defineStore('user', () => {
    const token = ref(tokenStore.get());
    const account = ref(localStorage.getItem(ACCOUNT_KEY) || '');
    /** 当前账号 ID（用于「这篇帖子是不是我发的」这类纯展示判断；权限仍由后端判定） */
    const userId = ref(localStorage.getItem(USER_ID_KEY) || '');
    /** 注册时填写的真实姓名（展示名）；登录接口与 /user/current 都会返回 */
    const userName = ref(localStorage.getItem(NAME_KEY) || '');
    /** user / member / admin（后端原始值，判断时不要写中文或大小写变体） */
    const role = ref(localStorage.getItem(ROLE_KEY) || '');
    /** 当前账号头像（/user/current 或登录接口返回；成员档案头像优先） */
    const userAvatar = ref('');

    const isLogin = computed(() => !!token.value);

    /**
     * 是否管理员，只用于界面判断（是否显示后台入口）
     *
     * 真正的权限判定在后端 `@SaCheckRole("admin")`——前端判断可以被绕过，永远只是体验层。
     */
    const isAdmin = computed(() => role.value === 'admin');

    /** 当前用户的在队成员档案（由 `/member/mine` 填充；null 表示不是成员） */
    const memberProfile = ref<Member | null>(null);
    const isMember = computed(() => !!memberProfile.value);

    /**
     * 界面展示名：成员档案姓名 > 注册姓名 > 登录账号
     *
     * 账号（如 chenyu）是登录凭据，不该是给人看的名字；注册姓名只影响非成员场景，
     * 成为成员后以管理员维护的档案姓名为准（与帖子作者名的取数口径一致）。
     */
    const displayName = computed(() => memberProfile.value?.name || userName.value || account.value);

    /** 界面展示头像：成员档案头像 > 账号头像；均为空时由 SmartImage 使用默认头像 */
    const displayAvatar = computed(() => memberProfile.value?.avatar || userAvatar.value);

    /** 监听登录失效事件：token 过期 / 被顶下线时把内存里的登录态一并回收 */
    function onAuthExpired() {
        token.value = '';
        account.value = '';
        userId.value = '';
        userName.value = '';
        role.value = '';
        userAvatar.value = '';
        memberProfile.value = null;
        tokenStore.clear();
        localStorage.removeItem(ACCOUNT_KEY);
        localStorage.removeItem(NAME_KEY);
        localStorage.removeItem(ROLE_KEY);
        localStorage.removeItem(USER_ID_KEY);
    }

    window.addEventListener(AUTH_EXPIRED_EVENT, onAuthExpired);
    onScopeDispose(() => window.removeEventListener(AUTH_EXPIRED_EVENT, onAuthExpired));

    /** 拉取「我的成员档案」：决定要不要显示成员专属入口（发帖 / 打卡） */
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

    function setLogin(
        value: string,
        name: string,
        userRole = '',
        realName = '',
        avatar = '',
        id = '',
    ) {
        token.value = value;
        account.value = name;
        userId.value = id;
        userName.value = realName;
        role.value = userRole;
        userAvatar.value = avatar;
        tokenStore.set(value);
        localStorage.setItem(ACCOUNT_KEY, name);
        localStorage.setItem(NAME_KEY, realName);
        localStorage.setItem(ROLE_KEY, userRole);
        localStorage.setItem(USER_ID_KEY, id);
        // 登录后立刻补拉一次：布局不会重建，等不到下一次 onMounted
        void loadMemberProfile();
    }

    /** 刷新当前账号的昵称与角色（管理员改角色后不必重新登录） */
    async function refreshProfile() {
        if (!token.value) {
            return;
        }
        try {
            const current = await userApi.current();
            account.value = current.userAccount || account.value;
            userId.value = current.id || userId.value;
            userName.value = current.userName || userName.value;
            role.value = current.userRole || '';
            userAvatar.value = current.userAvatar || userAvatar.value;
            localStorage.setItem(ACCOUNT_KEY, account.value);
            localStorage.setItem(NAME_KEY, userName.value);
            localStorage.setItem(ROLE_KEY, role.value);
            localStorage.setItem(USER_ID_KEY, userId.value);
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
        userId,
        userName,
        displayName,
        userAvatar,
        displayAvatar,
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
