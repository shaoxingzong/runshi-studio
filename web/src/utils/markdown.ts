import DOMPurify from 'dompurify';
import { Marked, type RendererObject } from 'marked';

/** 目录项：只收 h2/h3（h1 是文章标题本身，收进来等于重复） */
export interface TocItem {
    // 标题锚点 id（已去重，可直接拼进 URL hash）
    id: string;
    // 标题纯文本（目录展示用）
    text: string;
    level: 2 | 3;
}

/** 渲染结果：正文 HTML + 目录 */
export interface MarkdownResult {
    // 清洗前的 HTML（页面仍需过自己的 DOMPurify 白名单）
    html: string;
    // 目录（按文档顺序；不足 3 项时页面不应展示，避免噪音）
    toc: TocItem[];
}

/** 少于这个数就不值得展示目录——两行字的文章配个目录框很滑稽 */
export const TOC_MIN_ITEMS = 3;

/** DOMPurify 白名单（帖子 / 项目详情共用，单一事实来源） */
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

/** 清洗选项：唯一的策略差异是「图片能不能是外链」 */
export interface SanitizeOptions {
    /** 只允许站内 /uploads/ 图片（帖子正文与审核预览用） */
    restrictImages?: boolean;
}

/** 剥掉非站内图片的 src（命名函数，removeHook 才能精确摘掉自己这一个） */
function dropExternalImages(node: Element): void {
    if (node.tagName !== 'IMG') {
        return;
    }
    const src = node.getAttribute('src') || '';
    // 只放行站内上传目录；相对路径以外的（含 data:、http:）一律剥掉 src
    if (!src.startsWith('/uploads/')) {
        node.removeAttribute('src');
    }
}

/**
 * 清洗 renderMarkdown 的产物（帖子详情 / 项目详情 / 审核预览共用的唯一实现）
 *
 * @param html renderMarkdown 的输出（不可信：来源可能是发帖用户）
 * @param options restrictImages=true 时只保留站内图片
 * @returns 已按白名单清洗的 HTML，可直接交给 v-html
 */
export function sanitizeMarkdownHtml(html: string, options: SanitizeOptions = {}): string {
    const hooked = options.restrictImages === true;
    if (hooked) {
        DOMPurify.addHook('afterSanitizeAttributes', dropExternalImages);
    }
    try {
        return DOMPurify.sanitize(html, {
            ALLOWED_TAGS: MARKDOWN_ALLOWED_TAGS,
            ALLOWED_ATTR: MARKDOWN_ALLOWED_ATTR,
        }) as string;
    } finally {
        if (hooked) {
            DOMPurify.removeHook('afterSanitizeAttributes', dropExternalImages);
        }
    }
}

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
 * 标题转锚点 id：中文保留（转拼音既不准确也没必要），空格转连字符，其余标点清掉；
 * 空了兜底 section（纯符号标题是合法输入）
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
 * 每次渲染 new 一个 Marked：renderer 需要闭包承载本次的目录数组与去重计数，
 * 用模块级单例就要在并发渲染（快速切文章）间共享可变状态。Marked 实例很轻，开销感知不到。
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
            /**
             * 复制按钮放在 pre 外面（包一层 div）：按钮自己绝不能进 pre，
             * 否则用户「全选复制」时会把它一起抄走
             */
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
 * 正文是 v-html 出来的，Vue 的 @click 够不着里面的节点；在容器上委托一次，重新渲染也不会叠加。
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
 * 不用 scroll 事件：回调高频，每次读标题位置都强制重排，长文掉帧肉眼可见。
 * 多选一时取文档顺序中最靠上的可见标题——用户从上往下读，当前章节应是最上面那个。
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
 * 尊重系统的「减少动态效果」偏好（prefers-reduced-motion）。偏移由 CSS 的 scroll-margin-top
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
