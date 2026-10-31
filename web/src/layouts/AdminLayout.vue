<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useUserStore } from '@/stores/user';

/** 后台布局：侧边导航 + 顶栏（需要登录，见路由守卫） */
const router = useRouter();
const user = useUserStore();

const menus = [
    { path: '/admin/dashboard', label: '首页概览' },
    { path: '/admin/members', label: '成员管理' },
    // 考勤统计紧挨成员管理：它回答的就是「这些人这个月来了几次」
    { path: '/admin/attendance', label: '考勤统计' },
    { path: '/admin/users', label: '用户管理' },
    { path: '/admin/projects', label: '项目管理' },
    { path: '/admin/certificates', label: '证书管理' },
    { path: '/admin/knowledge', label: '知识库 (RAG)' },
    { path: '/admin/posts', label: '帖子审核' },
    { path: '/admin/post-manage', label: '帖子管理' },
    { path: '/admin/post-comments', label: '评论审核' },
    // 举报处理：只处理读者对「已公开内容」的反馈，与上面的待审队列互补
    { path: '/admin/reports', label: '举报处理' },
    { path: '/admin/quiz', label: '题库管理' },
    { path: '/admin/traffic', label: '流量分析' },
    // 操作日志放在最后：它是「出事之后」才看的台账，不该挤在日常管理入口前面
    { path: '/admin/logs', label: '操作日志' },
];

/** 窄屏标志（< 768px） */
const narrow = ref(false);

function syncNarrow() {
    narrow.value = window.innerWidth < 768;
}

onMounted(() => {
    syncNarrow();
    window.addEventListener('resize', syncNarrow);
    // 进入后台时刷新角色：被降权的账号即使还有旧 token，也应立刻失去后台入口
    void user.refreshProfile();
});

onBeforeUnmount(() => {
    window.removeEventListener('resize', syncNarrow);
});

function logout() {
    user.logout();
    router.push({ name: 'admin-login' });
}
</script>

<template>
    <el-container class="admin">
        <el-aside :width="narrow ? '132px' : '200px'" class="aside">
            <div class="brand">
                <img src="/logo.jpg" alt="润石工作室" class="brand-logo" />
                <span class="brand-text">管理后台</span>
            </div>
            <el-menu :default-active="$route.path" router :class="{ narrow }">
                <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
                    {{ item.label }}
                </el-menu-item>
            </el-menu>
        </el-aside>

        <el-container>
            <el-header class="header" :class="{ narrow }">
                <span class="muted who">已登录：{{ user.account || '管理员' }}</span>
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

/* ===== 窄屏（< 768px，由 script 里的 narrow 标志驱动）===== */

/* 菜单字号与内边距收紧，132px 的侧栏也要放得下「帖子管理」。
   narrow 类绑在 el-menu 根元素上，菜单项是组件内部节点，scoped 挡着，必须用 :deep */
.el-menu.narrow :deep(.el-menu-item) {
    font-size: 13px;
    padding-inline: 12px;
}

/* 顶栏：账号信息是纯展示，空间紧张时让位（退出按钮保留，功能不丢）*/
.header.narrow {
    padding-inline: 12px;
}

.header.narrow .who {
    display: none;
}
</style>
