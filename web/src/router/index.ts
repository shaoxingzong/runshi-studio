import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useUserStore } from '@/stores/user';

/**
 * 路由表
 *
 * 两个布局：门户（PortalLayout，匿名可访问）与后台（AdminLayout，需登录）。
 * 所有视图用**懒加载**：门户首屏只需要首页与布局的代码，
 * 后台管理那一大坨（表格/表单/Element 组件）只有管理员点进去才会下载。
 */
const routes: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('@/layouts/PortalLayout.vue'),
        children: [
            { path: '', name: 'home', component: () => import('@/views/portal/Home.vue') },
            // ⚠️ 官网**没有**成员页：业务规则「团队成员不对外展示」，
            // 后端的 /member/list 与 /member/detail 已删除（访问会 404）。
            // 成员信息对外唯一的出口是项目详情里的「参与成员」。
            { path: 'projects', name: 'projects', component: () => import('@/views/portal/Projects.vue') },
            {
                path: 'projects/:id',
                name: 'project-detail',
                component: () => import('@/views/portal/ProjectDetail.vue'),
            },
            { path: 'certificates', name: 'certificates', component: () => import('@/views/portal/Certificates.vue') },
            { path: 'ai', name: 'ai', component: () => import('@/views/portal/AiChat.vue') },
            // C 端注册 / 登录：注册出来是普通用户（user），「成员」身份由管理员在后台授予
            { path: 'login', name: 'login', component: () => import('@/views/portal/Login.vue') },
            { path: 'register', name: 'register', component: () => import('@/views/portal/Register.vue') },
        ],
    },
    {
        // 后台**只有登录，没有注册**：管理员账号由管理方分配，不提供自助注册入口
        path: '/admin/login',
        name: 'admin-login',
        component: () => import('@/views/admin/Login.vue'),
    },
    {
        path: '/admin',
        component: () => import('@/layouts/AdminLayout.vue'),
        meta: { requiresAuth: true },
        children: [
            { path: '', redirect: '/admin/members' },
            { path: 'members', name: 'admin-members', component: () => import('@/views/admin/Members.vue') },
            { path: 'users', name: 'admin-users', component: () => import('@/views/admin/Users.vue') },
            { path: 'projects', name: 'admin-projects', component: () => import('@/views/admin/Projects.vue') },
            {
                path: 'certificates',
                name: 'admin-certificates',
                component: () => import('@/views/admin/Certificates.vue'),
            },
            { path: 'knowledge', name: 'admin-knowledge', component: () => import('@/views/admin/Knowledge.vue') },
        ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
    scrollBehavior: () => ({ top: 0 }),
});

router.beforeEach((to) => {
    const user = useUserStore();
    if (!to.meta.requiresAuth) {
        return true;
    }
    // 未登录访问后台：跳登录页并记住来源，登录后直接回跳
    if (!user.isLogin) {
        return { name: 'admin-login', query: { redirect: to.fullPath } };
    }
    // 已登录但不是管理员（例如门户注册的普通用户）：同样送回登录页。
    // 这只是体验层——真正的权限判定在后端 @SaCheckRole("admin")，前端判断永远可以被绕过
    if (!user.isAdmin) {
        return { name: 'admin-login', query: { redirect: to.fullPath } };
    }
    return true;
});

export default router;
