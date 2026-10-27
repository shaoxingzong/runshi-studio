import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useUserStore } from '@/stores/user';
import { deviceType, referrerHost, trafficApi, visitorId } from '@/api/traffic';
import { usePageMeta } from '@/composables/usePageMeta';

/**
 * 路由表
 *
 * 两个布局：门户（PortalLayout，匿名可访问）与后台（AdminLayout，需登录）。
 * 所有视图用懒加载：门户首屏只需要首页与布局的代码，
 * 后台管理那一大坨（表格/表单/Element 组件）只有管理员点进去才会下载。
 */
const routes: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('@/layouts/PortalLayout.vue'),
        children: [
            // 首页不配 meta.title：标题退回纯站点名（「首页 - 润石工作室」是冗余信息）
            { path: '', name: 'home', component: () => import('@/views/portal/Home.vue') },
            // 官网没有成员页：业务规则「团队成员不对外展示」，
            // 这里不注册 /members 路由（后端也没有对外成员接口）。
            // 成员信息对外唯一的出口是项目详情里的「参与成员」。
            {
                path: 'projects',
                name: 'projects',
                component: () => import('@/views/portal/Projects.vue'),
                meta: { title: '项目案例' },
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
            // 顺序有讲究：new 与 my 必须排在 ':id' 之前。
            // 路由按定义顺序匹配，写反了的话 /posts/new 会被当成 id=new 的详情页
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
                meta: { title: '我的发帖' },
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
                meta: { title: '成员工作台' },
            },
            // 刷题：同样只对在队成员开放（后端查 studio_member 表判定）。
            // 两条都挂在 /quiz 下，`quiz/play` 会被导航的 isActive（startsWith）算作同一入口的高亮，
            // 因此不需要像 /posts/:id 那样特意调整顺序（这里没有动态段）
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
            // 通知中心：要求登录（后端 /notification/** 全部校验登录态，
            // 未登录访问得到 A0201，由请求层统一处理——本路由不再加一层守卫，
            // 与「权限判定在后端」的全局约定一致）
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
        ],
    },
    {
        // 403：权限不足。已登录但非管理员访问后台时落到这里——
        // 不能统一跳登录页，那等于对已登录的人说「你还没登录」，信息是错的
        path: '/403',
        name: 'forbidden',
        component: () => import('@/views/Forbidden.vue'),
        meta: { title: '无权访问' },
    },
    // 404：未匹配路径。兜底不能写成静默 redirect '/'——用户输错网址被悄悄送回首页，
    // 既不知道发生了什么，也无法判断「页面没了」还是「自己打错了」。
    // 注意：真正的权限判定在后端 @SaCheckRole，本页与路由守卫都只是体验层
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
    // 已登录但不是管理员（例如门户注册的普通用户）：跳 403 页。
    // 这只是体验层——真正的权限判定在后端 @SaCheckRole("admin")，前端判断永远可以被绕过
    if (!user.isAdmin) {
        return { name: 'forbidden' };
    }
    return true;
});

// 页面访问埋点（门户与后台都记）
//
// 放 afterEach 而不是 beforeEach：被守卫拦截的导航不算一次成功的访问。
// 用 to.path 而不是 fullPath：query 里可能带 token，分页参数也属于同一个页面。
// 不排除后台路径：知道管理员活跃在哪有运维价值，要区分就在统计侧按前缀拆，而不是在埋点侧丢弃。
//
// 失败静默：埋点是旁路，不 await、不抛错、不弹窗。标签页标题也在这里统一更新。
router.afterEach((to) => {
    usePageMeta({ title: (to.meta.title as string) ?? '' });

    // 整段 try/catch：参数构造里要读 localStorage、取 crypto 随机数，
    // 任何一步抛错都会让 afterEach 异常冒到全局错误处理器——
    // 表现就是「点每一次跳转都弹页面显示异常」。埋点是纯旁路，绝不参与主流程。
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
