<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';
import { notificationApi } from '@/api/notification';
import { useUserStore } from '@/stores/user';

/** 门户布局：顶部导航 + 内容区 + 页脚（匿名可访问） */
const route = useRoute();
const router = useRouter();
const user = useUserStore();

/**
 * 导航项
 *
 * 没有「团队成员」：业务规则「团队成员不对外展示」，后端三个成员接口已下线（访问 404）。
 * 成员信息唯一还会出现的地方是项目详情里的「参与成员」。
 */
const navItems = [
    { path: '/', label: '首页' },
    { path: '/projects', label: '项目案例' },
    { path: '/certificates', label: '荣誉墙' },
    { path: '/posts', label: '社区' },
    // 工作台（考勤）只对在队成员显示：后端同样只准成员签到。
    // 判定用 isMember（查 /member/mine 的档案）而不是 userRole——角色是权限标签，与档案可能不同步
    { path: '/workspace', label: '工作台', memberOnly: true },
    // 刷题同样只对在队成员显示：后端对 /quiz/** 的全部接口都做成员校验（认 studio_member 表）
    { path: '/quiz', label: '刷题', memberOnly: true },
    { path: '/ai', label: 'AI 助手' },
];

// 刷新后主动补一次「我的成员档案」
//
// 它决定「写文章」这类成员专属入口是否显示。布局只在启动时挂载一次，路由切换不再触发，
// 不自己拉一次的话，刷新完入口就消失了，要等下次登录才恢复。
onMounted(() => {
    void user.loadMemberProfile();
    // 角色只在登录时写过：刷新页面时补拉一次，管理员改过角色/封禁后入口能立刻变化
    void user.refreshProfile();
    void refreshUnread();
    // 未读轮询：60s 一次。工作室量级下这是最简单可靠的方案——
    // 上 WebSocket 长连接（心跳/断线重连/多实例）的成本远超收益
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

/**
 * 实际渲染的导航项：带 memberOnly 的项只在确认是成员后才出现
 *
 * 集中过滤而不是在模板里逐个 v-if：每加一个成员入口就多一处要记得加判断的地方。
 * 高亮用下面的 isActive 而非 router-link-active——后者是前缀匹配，`/` 会让首页在每个页面都亮。
 */
const visibleNavItems = computed(() => navItems.filter((item) => !item.memberOnly || user.isMember));

// =
/** 未读通知数（0 时 badge 隐藏）；未登录恒 0，不发请求 */
const unreadCount = ref(0);
/** 轮询定时器句柄（卸载时必须清，否则内存泄漏 + 接口打到已销毁的组件） */
let unreadTimer: number | null = null;

/**
 * 拉一次未读数
 *
 * 失败静默：铃铛是附属信息，保持上次的数字即可，下次轮询自然纠正，弹提示只会打扰阅读。
 */
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
                    <!-- 通知铃铛：登录态才出现。未读为 0 时 badge 整体隐藏（不是显示 0） -->
                    <router-link to="/notifications" class="nav-item bell" aria-label="通知">
                        <el-badge :value="unreadCount" :hidden="unreadCount <= 0" :max="99" type="danger">
                            <span class="bell-icon">🔔</span>
                        </el-badge>
                    </router-link>
                    <!-- 成员优先显示档案里的姓名：账号（如 chenyu）是登录凭据，
                     在右上角展示真实姓名才像「自己人」，也让成员身份一眼可辨 -->
                <span class="who">{{ user.memberProfile?.name || user.account }}</span>
                    <el-button link @click="logout">退出</el-button>
                </template>
                <template v-else>
                    <router-link to="/login" class="nav-item">登录</router-link>
                    <router-link to="/register" class="nav-item">注册</router-link>
                </template>
                <!-- 后台入口只对管理员显示（未登录时直接访问 /admin 会跳后台登录页） -->
                <router-link v-if="user.isAdmin" to="/admin" class="nav-item ghost">后台管理</router-link>
            </div>
        </header>

        <main>
            <router-view />
        </main>

        <footer class="site-footer">
            <div class="inner">
                <span>© {{ new Date().getFullYear() }} 润石工作室</span>
                <span class="muted"> · 项目 / 荣誉 / 社区 / 智能问答</span>
            </div>
        </footer>
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

.nav-item.ghost {
    border: 1px solid var(--border);
}

.spacer {
    flex: 1;
}

.who {
    font-size: 14px;
    color: var(--muted);
    margin-right: 4px;
}

/* 铃铛：图标用文字 emoji 而非图标库——门户没有引图标库，
   一个铃铛不值得为它增加依赖。尺寸与相邻的账号名协调 */
.bell {
    display: inline-flex;
    align-items: center;
    margin-right: 4px;
}

.bell-icon {
    font-size: 15px;
    line-height: 1;
}

/* el-badge 的圆点垂直居中于铃铛 */
.bell :deep(.el-badge__content) {
    top: 6px;
    right: 6px;
}

main {
    flex: 1;
}

.site-footer {
    border-top: 1px solid var(--border);
    background: var(--surface);
    padding: 18px 0;
    font-size: 13px;
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

    /* 导航吃掉剩余空间并横向滚动；滚动条隐藏（触摸屏本来就是滑动操作） */
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

    /* 账号名是纯展示，空间紧张时第一个让位 */
    .who {
        display: none;
    }
}

@media (max-width: 560px) {
    .logo-text {
        display: none;
    }

    .site-footer .inner {
        padding: 0 14px;
    }
}
</style>
