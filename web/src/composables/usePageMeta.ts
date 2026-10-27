import { type Ref, watch } from 'vue';

// 页面级 title / description 管理
//
// SPA 切路由不会改标签页标题，不处理的话所有页面在标签、书签、历史里都叫「润石工作室」。
// 静态页由路由 meta.title + afterEach 统一接线；详情页的标题依赖异步数据，页面内传 computed，
// 数据到位后自动覆盖。
//
// 刻意不做 SSR / 预渲染：后端没有 Node 渲染层，为此引入 vite-ssg 是架构级变更。

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

/**
 * 声明当前页面的标题与描述（组件卸载时恢复）
 *
 * @param meta title 与 description 都支持传 ref/computed，数据到位后自动更新
 */
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
