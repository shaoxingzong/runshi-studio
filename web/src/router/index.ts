import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useUserStore } from '@/stores/user';
import { deviceType, referrerHost, trafficApi, visitorId } from '@/api/traffic';
import { usePageMeta } from '@/composables/usePageMeta';

/** 路由表 */
const routes: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('@/layouts/PortalLayout.vue'),
        children: [
            // 首页不配 meta.title：标题退回纯站点名（「首页 - 润石工作室」是冗余信息）
            { path: '', name: 'home', component: () => import('@/views/portal/Home.vue') },
            {
                path: 'projects',
                name: 'projects',
                component: () => import('@/views/portal/Projects.vue'),
                meta: { title: '项目' },
            },
            {
                path: 'projects/:id',
                name: 'project-detail',
                component: () => import('@/views/portal/ProjectDetail.vue'),
                // 详情页标题由页面内用真实项目名二次覆盖（数据异步回来，见 usePageMeta）
                meta: { title: '项目详情' },
            },
            {
                path: 'certificates',
                name: 'certificates',
                component: () => import('@/views/portal/Certificates.vue'),
                meta: { title: '荣誉墙' },
            },
            { path: 'ai', name: 'ai', component: () => import('@/views/portal/AiChat.vue'), meta: { title: 'AI 助手' } },
            // 社区帖子：列表与详情对游客开放（后端白名单精确放行了 /post/list/page、/post/detail）
            {
                path: 'posts',
                name: 'posts',
                component: () => import('@/views/portal/Posts.vue'),
                meta: { title: '社区' },
            },
            {
                path: 'posts/new',
                name: 'post-new',
                component: () => import('@/views/portal/PostEdit.vue'),
                meta: { title: '发表帖子' },
            },
            {
                path: 'posts/my',
                name: 'my-posts',
                component: () => import('@/views/portal/MyPosts.vue'),
                meta: { title: '我的文章' },
            },
            {
                path: 'posts/:id',
                name: 'post-detail',
                component: () => import('@/views/portal/PostDetail.vue'),
                // 同项目详情：真实标题由页面内覆盖
                meta: { title: '帖子详情' },
            },
            // 成员工作台（考勤）：后端判成员身份，页面内部对非成员也会再拦一次
            {
                path: 'workspace',
                name: 'workspace',
                component: () => import('@/views/portal/Workspace.vue'),
                meta: { title: '打卡' },
            },
            {
                path: 'quiz',
                name: 'quiz',
                component: () => import('@/views/quiz/QuizHome.vue'),
                meta: { title: '刷题' },
            },
            {
                path: 'quiz/play',
                name: 'quiz-play',
                component: () => import('@/views/quiz/QuizPlay.vue'),
                meta: { title: '答题' },
            },
            // C 端注册 / 登录：注册出来是普通用户（user），「成员」身份由管理员在后台授予
            {
                path: 'login',
                name: 'login',
                component: () => import('@/views/portal/Login.vue'),
                meta: { title: '登录' },
            },
            {
                path: 'register',
                name: 'register',
                component: () => import('@/views/portal/Register.vue'),
                meta: { title: '注册' },
            },
            {
                path: 'notifications',
                name: 'notifications',
                component: () => import('@/views/Notifications.vue'),
                meta: { title: '通知' },
            },
        ],
    },
    {
        // 后台只有登录，没有注册：管理员账号由管理方分配，不提供自助注册入口
        path: '/admin/login',
        name: 'admin-login',
        component: () => import('@/views/admin/Login.vue'),
        meta: { title: '后台登录' },
    },
    {
        path: '/admin',
        component: () => import('@/layouts/AdminLayout.vue'),
        meta: { requiresAuth: true, title: '管理后台' },
        children: [
            // 落地页：先看概览（待审积压、各项计数），再进具体管理页
            { path: '', redirect: '/admin/dashboard' },
            {
                path: 'dashboard',
                name: 'admin-dashboard',
                component: () => import('@/views/admin/Dashboard.vue'),
                meta: { title: '首页概览' },
            },
            {
                path: 'members',
                name: 'admin-members',
                component: () => import('@/views/admin/Members.vue'),
                meta: { title: '成员管理' },
            },
            // 考勤统计：按月查看全员的签到天数与次数，读的仍是成员签到产生的原始记录
            {
                path: 'attendance',
                name: 'admin-attendance',
                component: () => import('@/views/admin/Attendance.vue'),
                meta: { title: '考勤统计' },
            },
            {
                path: 'users',
                name: 'admin-users',
                component: () => import('@/views/admin/Users.vue'),
                meta: { title: '用户管理' },
            },
            {
                path: 'projects',
                name: 'admin-projects',
                component: () => import('@/views/admin/Projects.vue'),
                meta: { title: '项目管理' },
            },
            {
                path: 'certificates',
                name: 'admin-certificates',
                component: () => import('@/views/admin/Certificates.vue'),
                meta: { title: '证书管理' },
            },
            {
                path: 'knowledge',
                name: 'admin-knowledge',
                component: () => import('@/views/admin/Knowledge.vue'),
                meta: { title: '知识库' },
            },
            {
                path: 'posts',
                name: 'admin-posts',
                component: () => import('@/views/admin/PostAudit.vue'),
                meta: { title: '帖子审核' },
            },
            // 帖子管理（全部帖子的查阅 / 删除 / 置顶 / 代改），与上面的待审队列互补
            {
                path: 'post-manage',
                name: 'admin-post-manage',
                component: () => import('@/views/admin/PostManage.vue'),
                meta: { title: '帖子管理' },
            },
            {
                path: 'post-comments',
                name: 'admin-post-comments',
                component: () => import('@/views/admin/PostCommentAudit.vue'),
                meta: { title: '评论审核' },
            },
            // 举报处理：读者对已公开内容的反馈入口，与审核队列是两条独立的治理链路
            {
                path: 'reports',
                name: 'admin-reports',
                component: () => import('@/views/admin/Reports.vue'),
                meta: { title: '举报处理' },
            },
            {
                path: 'traffic',
                name: 'admin-traffic',
                component: () => import('@/views/admin/Traffic.vue'),
                meta: { title: '流量分析' },
            },
            {
                path: 'quiz',
                name: 'admin-quiz',
                component: () => import('@/views/admin/QuizQuestions.vue'),
                meta: { title: '题库管理' },
            },
            // 操作日志：管理员每一次处置的留痕台账，内容出事故时的追溯入口
            {
                path: 'logs',
                name: 'admin-logs',
                component: () => import('@/views/admin/OperationLogs.vue'),
                meta: { title: '操作日志' },
            },
        ],
    },
    {
        path: '/403',
        name: 'forbidden',
        component: () => import('@/views/Forbidden.vue'),
        meta: { title: '无权访问' },
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFound.vue'),
        meta: { title: '页面不存在' },
    },
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
    if (!user.isAdmin) {
        return { name: 'forbidden' };
    }
    return true;
});
router.afterEach((to) => {
    usePageMeta({ title: (to.meta.title as string) ?? '' });

    try {
        void trafficApi
            .report({
                pagePath: to.path,
                visitorId: visitorId(),
                referrerHost: referrerHost(),
                deviceType: deviceType(),
            })
            .catch(() => {
                // 静默：旁路故障不影响主流程（与 RAG 检索失败降级同一条纪律）
            });
    } catch {
        // 参数构造阶段失败（存储被禁用、安全上下文缺失等）同样吞掉
    }
});

export default router;
