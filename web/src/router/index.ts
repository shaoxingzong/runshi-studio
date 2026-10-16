import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useUserStore } from '@/stores/user';
import { deviceType, referrerHost, trafficApi, visitorId } from '@/api/traffic';

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
            // 社区帖子：列表与详情对游客开放（后端白名单精确放行了 /post/list/page、/post/detail）
            { path: 'posts', name: 'posts', component: () => import('@/views/portal/Posts.vue') },
            // ⚠️ 顺序有讲究：new 与 my 必须排在 ':id' 之前。
            // 路由按定义顺序匹配，写反了的话 /posts/new 会被当成 id=new 的详情页
            { path: 'posts/new', name: 'post-new', component: () => import('@/views/portal/PostEdit.vue') },
            { path: 'posts/my', name: 'my-posts', component: () => import('@/views/portal/MyPosts.vue') },
            { path: 'posts/:id', name: 'post-detail', component: () => import('@/views/portal/PostDetail.vue') },
            // 成员工作台（考勤）：后端判成员身份，页面内部对非成员也会再拦一次
            { path: 'workspace', name: 'workspace', component: () => import('@/views/portal/Workspace.vue') },
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
            // 落地页：先看概览（待审积压、各项计数），再进具体管理页
            { path: '', redirect: '/admin/dashboard' },
            { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/admin/Dashboard.vue') },
            { path: 'members', name: 'admin-members', component: () => import('@/views/admin/Members.vue') },
            { path: 'users', name: 'admin-users', component: () => import('@/views/admin/Users.vue') },
            { path: 'projects', name: 'admin-projects', component: () => import('@/views/admin/Projects.vue') },
            {
                path: 'certificates',
                name: 'admin-certificates',
                component: () => import('@/views/admin/Certificates.vue'),
            },
            { path: 'knowledge', name: 'admin-knowledge', component: () => import('@/views/admin/Knowledge.vue') },
            { path: 'posts', name: 'admin-posts', component: () => import('@/views/admin/PostAudit.vue') },
            // 帖子管理（全部帖子的查阅 / 删除 / 置顶 / 代改），与上面的待审队列互补
            {
                path: 'post-manage',
                name: 'admin-post-manage',
                component: () => import('@/views/admin/PostManage.vue'),
            },
            {
                path: 'post-comments',
                name: 'admin-post-comments',
                component: () => import('@/views/admin/PostCommentAudit.vue'),
            },
            {
                path: 'traffic',
                name: 'admin-traffic',
                component: () => import('@/views/admin/Traffic.vue'),
            },
        ],
    },
    {
        // 403：权限不足。已登录但非管理员访问后台时落到这里——
        // 此前是统一跳登录页，等于对已登录的人说「你还没登录」，信息是错的
        path: '/403',
        name: 'forbidden',
        component: () => import('@/views/Forbidden.vue'),
    },
    // 404：未匹配路径。此前是静默 redirect '/'——用户输错网址被悄悄送回首页，
    // 既不知道发生了什么，也无法判断「页面没了」还是「自己打错了」。
    // 注意：真正的权限判定在后端 @SaCheckRole，本页与路由守卫都只是体验层
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFound.vue') },
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

/**
 * 页面访问埋点（门户与后台都记）
 *
 * <p><b>为什么放在 afterEach 而不是 beforeEach</b>：导航被守卫拦截时
 * （未登录跳后台登录页等）不算一次「成功的页面访问」，afterEach 只在真正到达后触发。
 *
 * <p><b>为什么用 to.path 而不是 to.fullPath</b>：query 里可能带 token
 * （分享链接、营销参数），而埋点只该记「哪个页面」。分页参数同理——
 * {@code /posts?current=2} 与 {@code /posts} 是同一个页面。
 *
 * <p><b>刻意不排除后台路径</b>：知道管理员活跃在哪些页面有运维价值，
 * 且热门页面榜里出现 /admin/* 正好说明「内部使用也被统计到了」，口径一致比看似干净更重要。
 * 若将来要区分门户/后台，应按路径前缀在统计侧拆分，而不是在埋点侧静默丢弃。
 *
 * <p><b>失败静默</b>：埋点是旁路，不 await、不抛错、不弹窗——
 * 它不该让任何一次导航变慢或变红。
 */
router.afterEach((to) => {
    void trafficApi.report({
        pagePath: to.path,
        visitorId: visitorId(),
        referrerHost: referrerHost(),
        deviceType: deviceType(),
    }).catch(() => {
        // 静默：旁路故障不影响主流程（与 RAG 检索失败降级同一条纪律）
    });
});

export default router;
