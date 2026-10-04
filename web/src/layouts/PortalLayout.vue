<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { userApi } from '@/api/user';
import { useUserStore } from '@/stores/user';

/** 门户布局：顶部导航 + 内容区 + 页脚（匿名可访问） */
const route = useRoute();
const router = useRouter();
const user = useUserStore();

/**
 * 导航项
 *
 * ⚠️ **没有「团队成员」**：业务规则「团队成员不对外展示」——
 * 官网成员页与后端三个成员接口（/member/list、/member/detail、/member/certificate/list）
 * 已一并下线（接口直接删除，访问得到 404）。
 * 成员信息唯一还会出现的地方是项目详情里的「参与成员」（产品确认保留）。
 */
const navItems = [
    { path: '/', label: '首页' },
    { path: '/projects', label: '项目案例' },
    { path: '/certificates', label: '荣誉墙' },
    { path: '/ai', label: 'AI 助手' },
];

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
 * 自定义高亮判断，<b>不用</b> router-link 自带的 router-link-active。
 *
 * 原因：那是「前缀匹配」——`/` 是所有路径的前缀，于是首页会在每个页面都高亮
 * （实测：进入「团队成员」时首页与团队成员同时亮）。
 * 这里显式区分：首页要精确相等，其余允许 `/members` 匹配 `/members/{id}` 这类子路径。
 */
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
                    <img src="/logo.png" alt="润石工作室" class="logo-img" />
                    润石工作室
                </router-link>
                <nav class="nav">
                    <router-link
                        v-for="item in navItems"
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
                    <span class="who">{{ user.account }}</span>
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
                <span class="muted"> · 成员 / 项目 / 荣誉 / 智能问答</span>
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

main {
    flex: 1;
}

.site-footer {
    border-top: 1px solid var(--border);
    background: var(--surface);
    padding: 18px 0;
    font-size: 13px;
}
</style>
