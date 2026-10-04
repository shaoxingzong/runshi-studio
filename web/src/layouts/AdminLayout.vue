<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useUserStore } from '@/stores/user';

/** 后台布局：侧边导航 + 顶栏（需要登录，见路由守卫） */
const router = useRouter();
const user = useUserStore();

const menus = [
    { path: '/admin/members', label: '成员管理' },
    // 用户管理 = 「给注册用户授予成员身份」的地方：注册只能得到普通用户（user），
    // 管理员在这里把角色改成 member（或 admin），或封禁账号
    { path: '/admin/users', label: '用户管理' },
    { path: '/admin/projects', label: '项目管理' },
    { path: '/admin/certificates', label: '证书管理' },
    { path: '/admin/knowledge', label: '知识库 (RAG)' },
];

function logout() {
    user.logout();
    router.push({ name: 'admin-login' });
}
</script>

<template>
    <el-container class="admin">
        <el-aside width="200px" class="aside">
            <div class="brand">
                <img src="/logo.png" alt="润石工作室" class="brand-logo" />
                管理后台
            </div>
            <el-menu :default-active="$route.path" router>
                <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
                    {{ item.label }}
                </el-menu-item>
            </el-menu>
        </el-aside>

        <el-container>
            <el-header class="header">
                <span class="muted">已登录：{{ user.account || '管理员' }}</span>
                <span class="spacer" />
                <el-button link @click="$router.push('/')">返回官网</el-button>
                <el-button link type="danger" @click="logout">退出登录</el-button>
            </el-header>

            <el-main>
                <router-view />
            </el-main>
        </el-container>
    </el-container>
</template>

<style scoped>
.admin {
    min-height: 100vh;
}

.aside {
    background: var(--surface);
    border-right: 1px solid var(--border);
}

.brand {
    height: 60px;
    display: flex;
    align-items: center;
    padding-left: 20px;
    font-weight: 700;
    font-size: 16px;
    border-bottom: 1px solid var(--border);
}

.brand-logo {
    width: 28px;
    height: 28px;
    margin-right: 9px;
}

.header {
    display: flex;
    align-items: center;
    gap: 12px;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
}

.spacer {
    flex: 1;
}
</style>
