import { Marked, type RendererObject } from 'marked';

/**
 * Markdown 渲染增强（帖子详情 / 项目详情共用）
 *
 * author: shaoshing
 *
 * <p><b>为什么抽成共享工具</b>：两个详情页的渲染链相同（marked → DOMPurify → v-html），
 * 此前「代码块复制、懒加载、目录」这类增强如果各写一份，改一处漏一处的风险实在太高
 * （项目里已经有够多的「两边必须一致」的约定了，没必要再添一个）。
 *
 * <p><b>提供三样增强</b>：
 * <ol>
 *     <li><b>代码块一键复制</b>：{@link bindCodeCopy} 用事件委托（{@code v-html}
 *     里挂不了 Vue 事件，委托是唯一干净的做法），按钮标记由 {@link renderMarkdown}
 *     的自定义 renderer 生成；</li>
 *     <li><b>长文目录</b>：{@link renderMarkdown} 单次渲染时顺带抽取 h2/h3 并注入
 *     {@code id}——抽取与注入是同一次遍历，不存在「目录里的 id 和标题上的 id 对不上」；</li>
 *     <li><b>图片懒加载</b>：renderer 输出 {@code loading="lazy"}，长文首屏不被图片拖慢。</li>
 * </ol>
 *
 * <p><b>与 DOMPurify 的配合</b>（页面侧责任）：本工具输出的 {@code div.code-block}、
 * {@code button.copy-btn}、标题 {@code id}、图片 {@code loading/decoding}
 * 都在页面已有的白名单之外，页面必须同步放行这些标签与属性，否则会被洗掉、
 * 增强静默失效（表现：复制按钮不见了但没人报错）。
 */

/** 目录项：只收 h2/h3（h1 是文章标题本身，收进来等于重复） */
export interface TocItem {
    /** 标题锚点 id（已去重，可直接拼进 URL hash） */
    id: string;
    /** 标题纯文本（目录展示用） */
    text: string;
    level: 2 | 3;
}

export interface MarkdownResult {
    /** 清洗前的 HTML（页面仍需过自己的 DOMPurify 白名单） */
    html: string;
    /** 目录（按文档顺序；不足 3 项时页面不应展示，避免噪音） */
    toc: TocItem[];
}

/** 少于这个数就不值得展示目录——两行字的文章配个目录框很滑稽 */
export const TOC_MIN_ITEMS = 3;

/**
 * DOMPurify 白名单（帖子 / 项目详情共用，故提在这里做单一事实来源）
 *
 * <p><b>为什么出口集中</b>：两个页面的清洗配置必须一模一样——
 * 它们消费的是同一个 renderer 的输出。各写一份的后果是「给项目页加了新属性、
 * 帖子页忘了加」，那种不一致在测试里很难发现，在线上表现为奇偶不同的怪 bug。
 * 页面侧只保留自己的策略差异（如帖子页额外的 img 站内限制）。
 *
 * <p>相比早期版本多出的 {@code div/button/id/type/aria-label/loading/decoding}
 * 全部是本工具增强输出的载体，少任何一个，对应的增强会被静默洗掉。
 */
export const MARKDOWN_ALLOWED_TAGS = [
    'p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'strong', 'em', 'del',
    'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span',
    // ↓ 本工具增强输出的载体：代码块包装与复制按钮
    'div', 'button',
];

export const MARKDOWN_ALLOWED_ATTR = [
    'href', 'src', 'alt', 'title', 'target', 'rel', 'class',
    // ↓ 标题锚点 / 按钮属性 / 图片懒加载
    'id', 'type', 'aria-label', 'loading', 'decoding',
];

function escapeHtml(text: string): string {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/** 属性值转义：引号必须处理（否则 href="x" onmouseover="..." 这类注入能破窗而出） */
function escapeAttr(text: string): string {
    return escapeHtml(text).replace(/'/g, '&#39;');
}

/** 剥掉内联 HTML 的标签，拿纯文本（目录展示与 slug 都用它） */
function plainText(html: string): string {
    return html
        .replace(/<[^>]+>/g, '')
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .trim();
}

/**
 * 标题转锚点 id：中文保留（技术文章的标题大量中文，转拼音既不准确也没必要），
 * 空格转连字符，其余标点清掉；空了兜底 {@code section}（纯符号标题是合法输入）
 */
function slugify(text: string): string {
    return (
        text
            .toLowerCase()
            .replace(/\s+/g, '-')
            .replace(/[^\u4e00-\u9fa5a-z0-9-]/g, '')
            .replace(/-{2,}/g, '-')
            .replace(/^-+|-+$/g, '') || 'section'
    );
}

/**
 * 渲染 Markdown，顺带产出目录
 *
 * <p><b>为什么每次渲染 new 一个 Marked 实例</b>：renderer 需要闭包承载「本次渲染」的
 * 目录数组与去重计数，用模块级单例就要在并发渲染间共享可变状态（文章切换快的场景）。
 * Marked 实例很轻，文章级渲染完全感知不到开销。
 */
export function renderMarkdown(source: string): MarkdownResult {
    const toc: TocItem[] = [];
    /** 同名标题的id去重：第二节也叫「使用方法」时，第二个变成 使用方法-1 */
    const slugCounts = new Map<string, number>();

    const uniqueSlug = (base: string): string => {
        const seen = slugCounts.get(base) ?? 0;
        slugCounts.set(base, seen + 1);
        return seen === 0 ? base : `${base}-${seen}`;
    };

    const renderer: RendererObject = {
        heading({ tokens, depth }) {
            const inlineHtml = this.parser.parseInline(tokens);
            const text = plainText(inlineHtml);
            const id = uniqueSlug(slugify(text));
            if (depth === 2 || depth === 3) {
                toc.push({ id, text, level: depth });
            }
            return `<h${depth} id="${id}">${inlineHtml}</h${depth}>`;
        },
        code({ text, lang }) {
            // 复制按钮放在 pre 外面（包一层 div）：按钮自己绝不能进 pre，
            // 否则用户「全选复制」时会把它一起抄走
            const langClass = lang ? ` class="language-${escapeAttr(lang)}"` : '';
            return (
                '<div class="code-block">' +
                `<button type="button" class="copy-btn" aria-label="复制代码">复制</button>` +
                `<pre><code${langClass}>${escapeHtml(text)}</code></pre>` +
                '</div>'
            );
        },
        image({ href, title, text }) {
            const titleAttr = title ? ` title="${escapeAttr(title)}"` : '';
            // loading/decoding 是性能属性，不影响内容语义；站内/外链的判定在页面的清洗钩子里
            return `<img src="${escapeAttr(href ?? '')}" alt="${escapeAttr(text ?? '')}"${titleAttr} loading="lazy" decoding="async" />`;
        },
    };

    const marked = new Marked({ gfm: true, breaks: false });
    marked.use({ renderer });
    const html = marked.parse(source) as string;

    return { html, toc };
}

/**
 * 给代码块绑「一键复制」（事件委托，返回解绑函数）
 *
 * <p><b>为什么用委托而不是给每个按钮挂监听</b>：HTML 是 {@code v-html} 出来的，
 * Vue 的 {@code @click} 够不着里面的节点； delegated 在容器上绑一次，
 * 文章重新渲染（切文章、重试加载）也不会叠加监听器。
 *
 * @param container 正文容器（.markdown-body 元素）
 */
export function bindCodeCopy(container: HTMLElement): () => void {
    const onClick = (event: MouseEvent) => {
        const target = event.target as HTMLElement | null;
        const button = target?.closest('.copy-btn');
        // closest 可能命中容器外同名节点（理论情况），挡一道
        if (!button || !container.contains(button)) {
            return;
        }
        const code = button.closest('.code-block')?.querySelector('code');
        if (!code) {
            return;
        }
        void copyText(code.textContent ?? '').then((ok) => {
            button.textContent = ok ? '已复制' : '复制失败';
            button.classList.toggle('is-error', !ok);
            // 1.5 秒后还原：一直挂着「已复制」会让下一个用户不知道还能再点
            window.setTimeout(() => {
                button.textContent = '复制';
                button.classList.remove('is-error');
            }, 1500);
        });
    };

    container.addEventListener('click', onClick);
    return () => container.removeEventListener('click', onClick);
}

/** clipboard API 在非安全上下文（http）下整体不可用，此时让调用方提示手动复制 */
async function copyText(text: string): Promise<boolean> {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        return false;
    }
}

/**
 * 目录滚动高亮（IntersectionObserver，返回断开函数）
 *
 * <p><b>为什么不用 scroll 事件</b>：滚动回调高频，每次回调读标题位置会强制重排，
 * 长文掉帧肉眼可见；IntersectionObserver 把「谁在视野里」的判断交给浏览器合成线程。
 *
 * <p>多选一时取<b>文档顺序中第一个可见</b>的标题——用户从上往下读，
 * 「当前章节」应该是视野里最靠上的那个，而不是最后进入视野的那个。
 *
 * @param container 正文容器
 * @param onActive  当前章节 id 变化回调
 */
export function observeTocHighlight(
    container: HTMLElement,
    onActive: (id: string) => void,
): () => void {
    const headings = Array.from(container.querySelectorAll<HTMLElement>('h2[id], h3[id]'));
    if (headings.length === 0) {
        return () => {};
    }

    const visible = new Set<string>();
    const observer = new IntersectionObserver(
        (entries) => {
            for (const entry of entries) {
                if (entry.isIntersecting) {
                    visible.add(entry.target.id);
                } else {
                    visible.delete(entry.target.id);
                }
            }
            const current = headings.find((heading) => visible.has(heading.id));
            if (current) {
                onActive(current.id);
            }
        },
        {
            // 上边距让出吸顶导航的高度；下边距 40% 的语义是
            // 「标题进入视口上 40% 区域才算当前章节」——贴边即亮会过于敏感
            rootMargin: '-80px 0px -60% 0px',
            threshold: 0,
        },
    );

    headings.forEach((heading) => observer.observe(heading));
    return () => observer.disconnect();
}

/**
 * 平滑滚动到指定标题
 *
 * <p>尊重系统的「减少动态效果」偏好（{@code prefers-reduced-motion}）——
 * 对动效敏感的用户直接跳转，不播动画。偏移由 CSS 的 {@code scroll-margin-top}
 * 处理（吸顶导航高度），JS 只管行为。
 */
export function scrollToHeading(id: string): void {
    const target = document.getElementById(id);
    if (!target) {
        return;
    }
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' });
}
