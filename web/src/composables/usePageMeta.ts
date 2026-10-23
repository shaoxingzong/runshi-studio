import { type Ref, watch } from 'vue';

// 页面级 title / description 管理
//
// 为什么需要它：SPA 的路由切换不会自己改标签页标题——不处理的话，
// 所有页面在浏览器标签、书签、历史记录里都叫「润石工作室」，
// 分享链接时对方看不到内容提示，搜索引擎收录详情页也拿不到有效标题。
//
// 两种用法（对应两类页面）：
// - 静态页（列表 / 表单）：路由 meta.title 配好，
// router.afterEach 统一调用，一处接线全部生效；
// - 详情页（项目 / 帖子）：标题带业务数据，数据是异步回来的，
// 页面内传 computed 进来，数据到位后自动覆盖（先显示「项目详情」，
// 数据到了换成真实项目名——好过空着或显示Loading）。
//
// 刻意不做 SSR / 预渲染：后端没有 Node 渲染层，为此引入 vite-ssg 是架构级变更。
// 当前真实需求（分享不像话、详情页可收录）用运行时 title + 静态 OG 已覆盖，
// 真有 SEO KPI 时再评估预渲染方案。

const SITE_NAME = '润石工作室';

/** 取不到就建一个：index.html 里有静态 description，这里保证运行时覆盖一定有着落 */
function descriptionTag(): HTMLMetaElement {
    let tag = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
    if (!tag) {
        tag = document.createElement('meta');
        tag.setAttribute('name', 'description');
        document.head.appendChild(tag);
    }
    return tag;
}

/** 统一拼装规则：空标题退回站点名（首页就该只有品牌名，不带「首页 - 」后缀） */
function fullTitle(title: string): string {
    return title ? `${title} - ${SITE_NAME}` : SITE_NAME;
}

export function usePageMeta(meta: {
    // 页面标题（不带站点后缀，拼接规则见 fullTitle）；传 ref/computed 可随数据更新
    title: string | Ref<string>;
    // 页面描述（可选）；同样支持 ref/computed
    description?: string | Ref<string>;
}): void {
    const apply = () => {
        const title = typeof meta.title === 'string' ? meta.title : meta.title.value;
        document.title = fullTitle(title);
        if (meta.description) {
            const desc = typeof meta.description === 'string' ? meta.description : meta.description.value;
            if (desc) {
                descriptionTag().content = desc;
            }
        }
    };

    // 立即应用一次（切路由即刻生效，不等数据）
    apply();

    // 传入响应式源的才挂监听：详情页数据异步回来时标题自动跟进。
    // 页面内调用时监听器随组件卸载自动取消，不需要手动清理
    if (typeof meta.title !== 'string') {
        watch(meta.title, apply);
    }
    if (meta.description && typeof meta.description !== 'string') {
        watch(meta.description, apply);
    }
}
