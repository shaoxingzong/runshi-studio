<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useUserStore } from '@/stores/user';

// 后台布局：侧边导航 + 顶栏（需要登录，见路由守卫）
const router = useRouter();
const user = useUserStore();

const menus = [
    { path: '/admin/dashboard', label: '首页概览' },
    { path: '/admin/members', label: '成员管理' },
    // 用户管理 = 「给注册用户授予成员身份」的地方：注册只能得到普通用户（user），
    // 管理员在这里把角色改成 member（或 admin），或封禁账号
    { path: '/admin/users', label: '用户管理' },
    { path: '/admin/projects', label: '项目管理' },
    { path: '/admin/certificates', label: '证书管理' },
    { path: '/admin/knowledge', label: '知识库 (RAG)' },
    { path: '/admin/posts', label: '帖子审核' },
    { path: '/admin/post-manage', label: '帖子管理' },
    { path: '/admin/post-comments', label: '评论审核' },
    { path: '/admin/quiz', label: '题库管理' },
    { path: '/admin/traffic', label: '流量分析' },
];

// 窄屏标志（< 768px）
//
// 为什么用 JS 而不是纯 CSS：el-aside 的宽度是 props（渲染成内联 style），
// CSS 类选择器改不动内联样式，只能靠 :width 绑定。
//
// 为什么是「收窄」而不是「抽屉」：抽屉要把侧栏改成 fixed + 遮罩 + 汉堡按钮，
// 而后台是工具页面、使用者是管理员（用电脑的概率远高于手机），
// 为边缘场景引入一整套交互组件的代价不成比例。收窄到 132px 后
// 菜单文字仍完整可读，内容区拿到更多宽度，表格横向滚动即可读——
// 这是「保底可用」与「复杂度」之间的取舍。真要做抽屉时再加。
const narrow = ref(false);

function syncNarrow() {
    narrow.value = window.innerWidth < 768;
}

onMounted(() => {
    syncNarrow();
    window.addEventListener('resize', syncNarrow);
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
                <img src="/logo.png" alt="润石工作室" class="brand-logo" />
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

/* 菜单字号与内边距收紧：132px 的侧栏也要放得下「帖子管理」四个字
   （4 × 13px + 左右各 12px = 76px，余量充足）。
   narrow 类绑在 el-menu 根元素上（模板里的 :class），菜单项是它的子节点，
   组件样式被 scoped 挡着，必须用 :deep 才够得着 */
.el-menu.narrow :deep(.el-menu-item) {
    font-size: 13px;
    padding-inline: 12px;
}

/* 顶栏：账号信息是纯展示，空间紧张时让位（退出按钮保留，功能不丢） */
.header.narrow {
    padding-inline: 12px;
}

.header.narrow .who {
    display: none;
}
</style>
