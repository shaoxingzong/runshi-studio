import { describe, expect, it } from 'vitest';
import { renderMarkdown, sanitizeMarkdownHtml } from './markdown';

// 渲染 + 清洗全链路测试（jsdom 环境，DOMPurify 需要真实 DOM）
//
// 这个测试补的是页面接线的盲区：renderMarkdown 的单测只验证
// 「renderer 输出了什么」，而页面在它之后还有一道 DOMPurify 白名单清洗。
// 两边任何一处没对齐（renderer 输出了 div/button/id，白名单没放行），
// 增强就会被静默洗掉——复制按钮不见了、目录锚点失效了，但没有任何报错。
// 这类回归单测 renderer 或单测 sanitize 都发现不了，必须测链路。
//
// 同时反向守住安全底线：白名单为增强放行了 div/button/id 等，
// 不能顺手把 `<script>`、`onerror=` 也放进来。

// 直接调生产实现（sanitizeMarkdownHtml）而不是在测试里复刻一份：
// 复刻版测的是「测试自己写得对不对」，页面接错线它照样绿。
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
        // 项目页策略（管理员录入）：外链图片本身允许，保留 src；
        // 但 onerror 必须被剥掉——「允许外链」绝不等于「允许执行脚本」
        expect(clean).toContain('<img src="https://evil.example.com/x.png">');
    });

    it('帖子策略：外链图片被剥 src（防追踪像素），站内图保留', () => {
        const { html } = renderMarkdown(ARTICLE);
        const clean = sanitize(html, { restrictImages: true });

        expect(clean).toContain('src="/uploads/2026/10/cover.png"');
        expect(clean).not.toContain('evil.example.com');
    });
});

// 升级 DOMPurify 的直接理由：旧版本存在可绕过白名单的 mXSS 漏洞。
// 这几条用例钉的是「绕过手法即使换了新花样，也只能得到一段无害文本」。
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
