import { type Ref, watch } from 'vue';

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

    if (typeof meta.title !== 'string') {
        watch(meta.title, apply);
    }
    if (meta.description && typeof meta.description !== 'string') {
        watch(meta.description, apply);
    }
}
