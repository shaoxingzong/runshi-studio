import { describe, expect, it } from 'vitest';
import { renderMarkdown, sanitizeMarkdownHtml } from './markdown';
const sanitize = sanitizeMarkdownHtml;

const ARTICLE = [
    '# 文章标题',
    '## 快速开始',
    '正文一段。',
    '```java',
    'int a = 1;',
    '```',
    '### 安装依赖',
    '![封面](/uploads/2026/10/cover.png)',
    '<script>alert(1)</script>',
    '<img src="https://evil.example.com/x.png" onerror="alert(2)">',
    '## 常见问题',
].join('\n\n');

describe('render + sanitize 全链路', () => {
    it('增强全部存活：复制按钮、标题锚点、图片懒加载都不被洗掉', () => {
        const { html, toc } = renderMarkdown(ARTICLE);
        const clean = sanitize(html);

        expect(clean).toContain('class="copy-btn"');
        expect(clean).toContain('<h2 id="快速开始">');
        expect(clean).toContain('loading="lazy"');
        // 目录锚点与标题 id 同源——清洗后依然对得上
        expect(toc.map((item) => item.id)).toContain('快速开始');
    });

    it('安全底线不放松：script 与事件属性仍被剥掉（项目页允许外链图，但不允许事件属性）', () => {
        const { html } = renderMarkdown(ARTICLE);
        const clean = sanitize(html);

        expect(clean).not.toContain('<script>alert(1)</script>');
        expect(clean).not.toContain('onerror=');
        expect(clean).toContain('<img src="https://evil.example.com/x.png">');
    });

    it('帖子策略：外链图片被剥 src（防追踪像素），站内图保留', () => {
        const { html } = renderMarkdown(ARTICLE);
        const clean = sanitize(html, { restrictImages: true });

        expect(clean).toContain('src="/uploads/2026/10/cover.png"');
        expect(clean).not.toContain('evil.example.com');
    });
});
describe('sanitizeMarkdownHtml 的 mXSS 绕过回归', () => {
    it('命名空间混淆（math/mglyph/style）夹带的 onerror 不能存活', () => {
        const payload =
            '<math><mtext><mglyph><style><!--</style><img title="--><img src=x onerror=alert(1)>">';
        const clean = sanitizeMarkdownHtml(payload).toLowerCase();

        expect(clean).not.toContain('onerror');
        expect(clean).not.toContain('<math');
        expect(clean).not.toContain('<style');
    });

    it('svg/style 夹带的脚本与事件属性被整体剥掉，正常内容保留', () => {
        const payload = '<svg><style><img src=x onerror=alert(1)></style></svg><p>正文</p>';
        const clean = sanitizeMarkdownHtml(payload);

        expect(clean).toContain('<p>正文</p>');
        expect(clean.toLowerCase()).not.toContain('onerror');
        expect(clean.toLowerCase()).not.toContain('<svg');
    });

    it('javascript: 协议链接被剥掉 href', () => {
        const clean = sanitizeMarkdownHtml('<a href="javascript:alert(1)">点我</a>').toLowerCase();

        expect(clean).not.toContain('javascript:');
        expect(clean).toContain('点我');
    });

    it('restrictImages 用完即摘钩子：下一次渲染不会误剥外链图', () => {
        sanitizeMarkdownHtml('<img src="https://cdn.example.com/a.png">', { restrictImages: true });
        const clean = sanitizeMarkdownHtml('<img src="https://cdn.example.com/a.png">');

        expect(clean).toContain('src="https://cdn.example.com/a.png"');
    });
});
