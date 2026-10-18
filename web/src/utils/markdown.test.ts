import { describe, expect, it } from 'vitest';
import { renderMarkdown } from './markdown';

/**
 * Markdown 渲染增强工具单测（纯字符串进、字符串出，不依赖 DOM）
 *
 * author: shaoshing
 *
 * <p>锁四类输出契约——它们任何一处回归，用户侧的表现都是「增强静默消失」：
 * <ol>
 *     <li>目录只收 h2/h3（h1 是文章标题，收进来等于目录重复一项）；</li>
 *     <li>标题 {@code id} 与目录 {@code id} 必须一致（锚点跳转的命门）；</li>
 *     <li>代码块外包复制按钮、按钮在 {@code pre} 外面（否则全选复制会抄走按钮）；</li>
 *     <li>图片带懒加载属性。</li>
 * </ol>
 */
describe('renderMarkdown：目录抽取', () => {
    it('只收 h2/h3，按文档顺序，带纯文本与层级', () => {
        const { toc } = renderMarkdown(
            ['# 文章标题', '## 快速开始', '### 安装依赖', '正文', '## 常见问题'].join('\n\n'),
        );

        expect(toc).toEqual([
            { id: '快速开始', text: '快速开始', level: 2 },
            { id: '安装依赖', text: '安装依赖', level: 3 },
            { id: '常见问题', text: '常见问题', level: 2 },
        ]);
    });

    it('标题 id 与目录 id 一致（锚点跳转的前提）', () => {
        const { html, toc } = renderMarkdown('## 快速开始\n\n正文');

        expect(html).toContain('<h2 id="快速开始">');
        expect(toc[0].id).toBe('快速开始');
    });

    it('同名标题自动去重（第二节「使用方法」→ 使用方法-1）', () => {
        const { html, toc } = renderMarkdown('## 使用方法\n\n## 使用方法');

        expect(toc.map((item) => item.id)).toEqual(['使用方法', '使用方法-1']);
        expect(html).toContain('<h2 id="使用方法">');
        expect(html).toContain('<h2 id="使用方法-1">');
    });

    it('英文标题转小写连字符；纯符号标题兜底 section', () => {
        expect(renderMarkdown('## Hello World').toc[0].id).toBe('hello-world');
        expect(renderMarkdown('## ！！！').toc[0].id).toBe('section');
    });
});

describe('renderMarkdown：代码块', () => {
    it('外包复制按钮，按钮在 pre 之外（全选复制不会抄走它）', () => {
        const { html } = renderMarkdown('```java\nint a = 1;\n```');

        expect(html).toContain('<div class="code-block">');
        expect(html).toContain('<button type="button" class="copy-btn" aria-label="复制代码">复制</button>');
        expect(html).toContain('<pre><code class="language-java">int a = 1;</code></pre>');
        // 按钮必须是 pre 的兄弟而非后代
        const preIndex = html.indexOf('<pre>');
        const buttonIndex = html.indexOf('copy-btn');
        expect(buttonIndex).toBeLessThan(preIndex);
    });

    it('代码内容转义：正文里的 <script> 不会变成真标签', () => {
        const { html } = renderMarkdown('```html\n<script>alert(1)</script>\n```');

        expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
        expect(html).not.toContain('<script>alert(1)</script>');
    });

    it('无语言代码块不拼 language- 类', () => {
        const { html } = renderMarkdown('```\nplain\n```');

        expect(html).toContain('<pre><code>plain</code></pre>');
    });
});

describe('renderMarkdown：图片', () => {
    it('输出懒加载与异步解码属性', () => {
        const { html } = renderMarkdown('![封面](/uploads/2026/10/cover.png)');

        expect(html).toContain('src="/uploads/2026/10/cover.png"');
        expect(html).toContain('alt="封面"');
        expect(html).toContain('loading="lazy"');
        expect(html).toContain('decoding="async"');
    });

    it('alt 文本转义（防止从 alt 破窗）', () => {
        const { html } = renderMarkdown('![a" onerror="alert(1)](/uploads/x.png)');

        expect(html).not.toContain('onerror="alert(1)"');
        expect(html).toContain('&quot;');
    });
});
