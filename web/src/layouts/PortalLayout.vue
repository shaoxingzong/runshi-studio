<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowDown, Bell, Document, Setting, SwitchButton, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';
import { notificationApi } from '@/api/notification';
import MemberProfileDialog from '@/components/MemberProfileDialog.vue';
import SmartImage from '@/components/SmartImage.vue';
import { useUserStore } from '@/stores/user';

/** 门户布局：顶部导航 + 内容区 + 页脚（匿名可访问） */
const route = useRoute();
const router = useRouter();
const user = useUserStore();

/** 页脚版权年份：只取当前年，跨年刷新即可自动更新 */
const currentYear = new Date().getFullYear();
/** ICP 备案号：线上展示与工信部备案系统查询入口 */
const ICP_NUMBER = '豫ICP备2026054568号';
const ICP_LINK = 'https://beian.miit.gov.cn/';

/** 导航项 */
const navItems = [
    { path: '/', label: '首页' },
    { path: '/projects', label: '项目' },
    { path: '/posts', label: '社区' },
    { path: '/certificates', label: '荣誉墙' },
    { path: '/workspace', label: '打卡', memberOnly: true },
    // 刷题同样只对在队成员显示：后端对 /quiz/** 的全部接口都做成员校验（认 studio_member 表）
    { path: '/quiz', label: '刷题', memberOnly: true },
    { path: '/ai', label: 'AI 助手' },
];
onMounted(() => {
    void user.loadMemberProfile();
    // 角色只在登录时写过：刷新页面时补拉一次，管理员改过角色/封禁后入口能立刻变化
    void user.refreshProfile();
    void refreshUnread();
    unreadTimer = window.setInterval(() => {
        if (user.isLogin) {
            void refreshUnread();
        }
    }, 60_000);
    // 通知页标记已读后广播本事件：铃铛立刻刷新，不等下一次轮询
    window.addEventListener('notification:read', onNotificationRead);
});

onBeforeUnmount(() => {
    if (unreadTimer !== null) {
        window.clearInterval(unreadTimer);
        unreadTimer = null;
    }
    window.removeEventListener('notification:read', onNotificationRead);
});

/** 退出登录：先让服务端清会话，再清本地状态 */
async function logout() {
    try {
        await userApi.logout();
    } catch (e) {
        // 注销失败（令牌已过期等）也必须清掉本地状态，否则界面会永远停在「已登录」
        console.warn('注销失败，仅清理本地登录态', e);
    }
    user.logout();
    ElMessage.success('已退出登录');
    await router.push('/');
}

/** 实际渲染的导航项：带 memberOnly 的项只在确认是成员后才出现 */
const visibleNavItems = computed(() => navItems.filter((item) => !item.memberOnly || user.isMember));
/** 未读通知数（0 时 badge 隐藏）；未登录恒 0，不发请求 */
const unreadCount = ref(0);
/** 成员资料编辑弹窗：从账号菜单直接打开，与打卡页共用同一个组件 */
const profileVisible = ref(false);
/** 轮询定时器句柄（卸载时必须清，否则内存泄漏 + 接口打到已销毁的组件） */
let unreadTimer: number | null = null;

/** 拉一次未读数 */
async function refreshUnread() {
    if (!user.isLogin) {
        unreadCount.value = 0;
        return;
    }
    try {
        unreadCount.value = await notificationApi.unreadCount();
    } catch {
        // 静默：见上方注释
    }
}

/** 通知页「已读」广播的处理器：立刻刷新铃铛（与轮询同一方法，无第二套逻辑） */
function onNotificationRead() {
    void refreshUnread();
}

function isActive(path: string): boolean {
    const current = route.path;
    return path === '/' ? current === '/' : current === path || current.startsWith(`${path}/`);
}

/** 账号下拉的菜单动作：资料编辑就地弹窗，其余走路由 */
async function onAccountCommand(command: string) {
    switch (command) {
        case 'profile':
            // 打开前先拉一次档案：刚登录 / 管理员改过资料时，本地 store 可能还是旧的
            await user.loadMemberProfile();
            profileVisible.value = true;
            break;
        case 'posts':
            await router.push('/posts/my');
            break;
        case 'admin':
            await router.push('/admin');
            break;
        case 'logout':
            await logout();
            break;
    }
}
</script>

<template>
    <div class="portal">
        <header class="site-header">
            <div class="inner">
                <router-link to="/" class="logo">
                    <img src="/logo.jpg" alt="润石工作室" class="logo-img" />
                    <span class="logo-text">润石工作室</span>
                </router-link>
                <nav class="nav">
                    <router-link
                        v-for="item in visibleNavItems"
                        :key="item.path"
                        :to="item.path"
                        class="nav-item"
                        :class="{ active: isActive(item.path) }"
                    >
                        {{ item.label }}
                    </router-link>
                </nav>
                <span class="spacer" />
                <template v-if="user.isLogin">
                    <!-- 通知铃铛：登录态才出现。未读为 0 时 badge 整体隐藏（不是显示 0）-->
                    <router-link to="/notifications" class="nav-item bell" aria-label="通知">
                        <el-badge :value="unreadCount" :hidden="unreadCount <= 0" :max="99" type="danger">
                            <Bell class="bell-icon" aria-hidden="true" />
                        </el-badge>
                    </router-link>
                    <!-- 账号菜单：头像 + 展示名作为触发器，资料/我的文章/后台/退出都收进下拉 -->
                    <el-dropdown trigger="click" placement="bottom-end" @command="onAccountCommand">
                        <button type="button" class="account-trigger" aria-label="账号菜单">
                            <span class="account-avatar">
                                <SmartImage
                                    :src="user.displayAvatar"
                                    :alt="user.displayName"
                                    :seed="user.account"
                                />
                            </span>
                            <span class="account-name">{{ user.displayName }}</span>
                            <el-icon class="account-caret"><ArrowDown /></el-icon>
                        </button>
                        <template #dropdown>
                            <el-dropdown-menu>
                                <el-dropdown-item v-if="user.isMember" command="profile" :icon="User">
                                    编辑我的资料
                                </el-dropdown-item>
                                <el-dropdown-item v-if="user.isMember" command="posts" :icon="Document">
                                    我的文章
                                </el-dropdown-item>
                                <el-dropdown-item v-if="user.isAdmin" command="admin" :icon="Setting" divided>
                                    后台管理
                                </el-dropdown-item>
                                <el-dropdown-item command="logout" :icon="SwitchButton" divided>
                                    退出登录
                                </el-dropdown-item>
                            </el-dropdown-menu>
                        </template>
                    </el-dropdown>
                </template>
                <template v-else>
                    <router-link to="/login" class="nav-item">登录</router-link>
                    <router-link to="/register" class="nav-item">注册</router-link>
                </template>
            </div>
        </header>

        <main>
            <router-view />
        </main>

        <footer class="site-footer">
            <div class="inner footer-inner">
                <span>© {{ currentYear }} 润石工作室</span>
                <span class="muted">· 项目 / 荣誉 / 社区 / 智能问答</span>
                <a :href="ICP_LINK" class="icp" target="_blank" rel="noopener noreferrer">{{ ICP_NUMBER }}</a>
            </div>
        </footer>

        <!-- 资料编辑弹窗挂布局层：账号菜单和打卡页共用，避免每个入口各写一份表单 -->
        <MemberProfileDialog v-model="profileVisible" />
    </div>
</template>

<style scoped>
.portal {
    display: flex;
    flex-direction: column;
    min-height: 100vh;
}

.site-header {
    position: sticky;
    top: 0;
    z-index: 100;
    background: rgb(255 255 255 / 90%);
    backdrop-filter: blur(8px);
    border-bottom: 1px solid var(--border);
}

.inner {
    max-width: 1180px;
    margin: 0 auto;
    padding: 0 20px;
    display: flex;
    align-items: center;
    gap: 4px;
    height: 62px;
}

.logo {
    display: inline-flex;
    align-items: center;
    gap: 9px;
    font-size: 18px;
    font-weight: 700;
    margin-right: 18px;
    color: var(--text);
}

.logo-img {
    width: 34px;
    height: 34px;
    object-fit: contain;
}

.logo:hover {
    text-decoration: none;
}

.nav {
    display: flex;
    gap: 2px;
}

.nav-item {
    padding: 8px 14px;
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: 15px;
}

.nav-item:hover {
    background: var(--brand-soft);
    text-decoration: none;
}

.nav-item.active {
    color: var(--brand);
    font-weight: 600;
    background: var(--brand-soft);
}

.spacer {
    flex: 1;
}

/* 账号菜单：头像 + 展示名 + 下拉箭头，资料/我的文章/后台/退出都收在菜单里 */
.account-trigger {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    height: 36px;
    padding: 2px 8px 2px 4px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--text-secondary);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
    transition: background 0.2s ease, color 0.2s ease;
}

.account-trigger:hover {
    background: var(--brand-soft);
    color: var(--brand);
}

.account-trigger:focus-visible {
    outline: 2px solid var(--brand);
    outline-offset: 2px;
}

.account-avatar {
    display: inline-flex;
    flex-shrink: 0;
    width: 28px;
    height: 28px;
    border-radius: 50%;
    overflow: hidden;
    /* 细边框让浅色兜底头像与白色头部背景分得开 */
    box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
}

.account-avatar :deep(.img-fallback__img),
.account-avatar :deep(.img-fallback__box) {
    width: 100%;
    height: 100%;
}

.account-avatar :deep(.img-fallback__box) {
    font-size: 12px;
    font-weight: 700;
}

.account-name {
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.account-caret {
    width: 12px;
    height: 12px;
    color: var(--muted);
}

.account-trigger:hover .account-caret {
    color: var(--brand);
}

/* 铃铛：与全站其他图标一致用线性图标（@element-plus/icons-vue 已是现有依赖，
   不再用 emoji——emoji 在不同系统里字形差异很大，也压不住导航的字重）*/
.bell {
    display: inline-flex;
    align-items: center;
    margin-right: 4px;
}

.bell-icon {
    width: 16px;
    height: 16px;
    color: var(--muted);
}

.bell:hover .bell-icon {
    color: var(--brand);
}

/* el-badge 的圆点垂直居中于铃铛 */
.bell :deep(.el-badge__content) {
    top: 6px;
    right: 6px;
}

main {
    flex: 1;
}

/* ===== 页脚 ===== */
.site-footer {
    border-top: 1px solid var(--border);
    background: var(--surface);
    padding: 18px 0;
    font-size: 13px;
}

/* 保留原来的页脚高度，同时允许备案号在窄屏换行 */
.site-footer .footer-inner {
    height: auto;
    min-height: 62px;
    flex-wrap: wrap;
    gap: 6px 12px;
}

.icp {
    color: var(--muted);
}

.icp:hover {
    color: var(--brand);
    text-decoration: none;
}

/* 小屏适配：头部横排约 830px，窄于此就会挤压换行。
   策略不是藏起来而是重新分配：导航横向滚动、账号名让位、超窄屏收起 logo 文字 */
@media (max-width: 860px) {
    .inner {
        gap: 0;
        padding: 0 14px;
    }

    .logo {
        margin-right: 10px;
        font-size: 16px;
        flex-shrink: 0;
    }

    .logo-img {
        width: 28px;
        height: 28px;
    }

    /* 导航吃掉剩余空间并横向滚动；滚动条隐藏（触摸屏本来就是滑动操作）*/
    .nav {
        flex: 1;
        min-width: 0;
        overflow-x: auto;
        scrollbar-width: none;
        -webkit-overflow-scrolling: touch;
    }

    .nav::-webkit-scrollbar {
        display: none;
    }

    /* 导航项不参与压缩：压缩会让文字换行，反而更难看 */
    .nav-item {
        flex-shrink: 0;
        padding: 8px 10px;
        font-size: 14px;
        white-space: nowrap;
    }

    /* 账号名是纯展示，空间紧张时第一个让位；头像仍保留，账号菜单始终可点 */
    .account-name,
    .account-caret {
        display: none;
    }

    .account-trigger {
        gap: 0;
        padding: 2px 4px;
        flex-shrink: 0;
    }

    .account-avatar {
        width: 26px;
        height: 26px;
    }
}

@media (max-width: 560px) {
    .logo-text {
        display: none;
    }

    /* 只收窄左右内边距；上下的页脚留白由 .site-footer 自己维护 */
    .site-footer .inner {
        padding-left: 14px;
        padding-right: 14px;
    }
}
</style>
