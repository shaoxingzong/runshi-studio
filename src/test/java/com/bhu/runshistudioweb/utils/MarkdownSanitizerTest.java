package com.bhu.runshistudioweb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown 正文白名单清洗单测（纯函数，不起容器）
 *
 * 锁定三类行为：该清的必须清（原始 HTML / 危险 scheme，含常见混淆变体）、
 * 该留的必须留（Markdown 语法、代码块里的 HTML 示例、站内图片）、
 * 边界不炸（null / 空串 / 纯代码块）。
 */
class MarkdownSanitizerTest {

    // ==================== 原始 HTML：该清的全部清掉 ====================

    @Test
    @DisplayName("script 标签连内容一起清除")
    void scriptTagRemovedWithContent() {
        String result = MarkdownSanitizer.sanitize("你好<script>alert('xss')</script>世界");

        assertFalse(result.contains("script"), "script 字样都不该残留");
        assertFalse(result.contains("alert"));
        assertTrue(result.contains("你好"));
        assertTrue(result.contains("世界"));
    }

    @Test
    @DisplayName("img onerror 事件属性被清除（整个标签移除，不留 onerror）")
    void imgOnerrorRemoved() {
        String result = MarkdownSanitizer.sanitize("看图<img src=x onerror=alert(1)>结束");

        assertFalse(result.contains("onerror"));
        assertFalse(result.toLowerCase().contains("<img"));
        assertTrue(result.contains("看图"));
        assertTrue(result.contains("结束"));
    }

    @Test
    @DisplayName("iframe / style / 带 onclick 的 div 一并清除")
    void otherDangerousTagsRemoved() {
        String result = MarkdownSanitizer.sanitize(
                "a<iframe src=\"https://evil.example\"></iframe>b"
                        + "<style>body{display:none}</style>"
                        + "<div onclick=\"steal()\">c</div>");

        assertFalse(result.toLowerCase().contains("iframe"));
        assertFalse(result.toLowerCase().contains("style"));
        assertFalse(result.contains("onclick"));
        assertFalse(result.contains("evil.example"));
        // 标签内的文字保留（div 的 c），这是「去标签不去内容」的预期行为
        assertTrue(result.contains("a"));
        assertTrue(result.contains("c"));
    }

    // ==================== 危险 scheme：链接目的地中和 ====================

    @Test
    @DisplayName("javascript: 链接目的地被置空，退化成 [文字]()")
    void javascriptLinkNeutralized() {
        String result = MarkdownSanitizer.sanitize("点[这里](javascript:alert(1))看看");

        assertFalse(result.toLowerCase().contains("javascript:"));
        assertFalse(result.contains("alert"));
        assertTrue(result.contains("[这里]()"), "应退化成空目的地链接");
        assertTrue(result.contains("看看"));
    }

    @Test
    @DisplayName("图片里的 javascript: 目的地同样被中和")
    void javascriptImageNeutralized() {
        String result = MarkdownSanitizer.sanitize("![封面](javascript:alert(1))");

        assertFalse(result.toLowerCase().contains("javascript:"));
        assertTrue(result.contains("![封面]()"));
    }

    @Test
    @DisplayName("scheme 混淆变体全部识别：大小写、内部 tab/换行、前导空白")
    void schemeObfuscationVariantsNeutralized() {
        assertFalse(MarkdownSanitizer.sanitize("[a](JaVaScRiPt:alert(1))").toLowerCase().contains("javascript:"));
        assertFalse(MarkdownSanitizer.sanitize("[a](java\tscript:alert(1))").toLowerCase().contains("script:"));
        assertFalse(MarkdownSanitizer.sanitize("[a](   javascript:alert(1))").toLowerCase().contains("javascript:"));
        assertFalse(MarkdownSanitizer.sanitize("[a](vbscript:msgbox(1))").toLowerCase().contains("vbscript:"));
        assertFalse(MarkdownSanitizer.sanitize("[a](data:text/html,<script>alert(1)</script>)").toLowerCase().contains("data:"));
        assertFalse(MarkdownSanitizer.sanitize("[a](file:///etc/passwd)").toLowerCase().contains("file:"));
    }

    @Test
    @DisplayName("安全目的地原样保留：http/https/站内 uploads/相对路径/邮件")
    void safeDestinationsPreserved() {
        assertEquals("[官网](https://runshi.example)",
                MarkdownSanitizer.sanitize("[官网](https://runshi.example)"));
        assertEquals("[站内图](/uploads/2026/10/a.png)",
                MarkdownSanitizer.sanitize("[站内图](/uploads/2026/10/a.png)"));
        assertEquals("[邮件](mailto:hi@runshi.example)",
                MarkdownSanitizer.sanitize("[邮件](mailto:hi@runshi.example)"));
        assertEquals("[相对](./detail/page)",
                MarkdownSanitizer.sanitize("[相对](./detail/page)"));
    }

    // ==================== 正常内容：该留的全部保留 ====================

    @Test
    @DisplayName("Markdown 语法（标题/粗体/列表/表格/引用）不受影响")
    void markdownSyntaxPreserved() {
        String md = "# 标题\n\n**粗体** 与 *斜体*\n\n- 列表项\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n> 引用";
        // 唯一允许的差异：Jsoup 会把 > 转义成 &gt;。Markdown 渲染器会解码实体，
        // 显示效果一致（引号还是引号）；且反复清洗结果稳定，不会二次转义
        String expected = md.replace(">", "&gt;");
        String result = MarkdownSanitizer.sanitize(md);

        assertEquals(expected, result, "合法 Markdown 的结构与文字必须保留");
    }

    @Test
    @DisplayName("换行结构保留：Jsoup pretty-print 必须关闭（段落/列表/表格全靠换行）")
    void newlinesPreserved() {
        // 这条是回归锁：Jsoup 默认开 pretty-print，会把换行折叠成空格，
        // 表现是「标题与正文粘成一行、列表项挤成一段」——排版整体被毁
        String md = "第一段\n\n第二段\n\n- 甲\n- 乙\n\n# 标题";
        assertEquals(md, MarkdownSanitizer.sanitize(md));
    }

    @Test
    @DisplayName("围栏代码块里的 HTML 示例原样保留（技术博客刚需）")
    void fencedHtmlExamplePreserved() {
        String md = "# Vue 模板\n\n```html\n<div id=\"app\">{{ message }}</div>\n```\n\n如上所示。";
        String result = MarkdownSanitizer.sanitize(md);

        assertEquals(md, result, "围栏内的 HTML 是展示内容，必须原样保留");
    }

    @Test
    @DisplayName("行内代码里的 HTML 标签保留")
    void inlineHtmlCodePreserved() {
        String result = MarkdownSanitizer.sanitize("用 `<div>` 包裹内容");

        assertTrue(result.contains("`<div>`"), "行内代码内容应原样保留");
    }

    @Test
    @DisplayName("围栏外的 script 仍被清除（占位符不影响围栏外清洗）")
    void scriptOutsideFenceStillRemoved() {
        String result = MarkdownSanitizer.sanitize(
                "```js\nconst a = 1;\n```\n\n<script>alert(1)</script>\n\n```js\nconst b = 2;\n```");

        assertFalse(result.contains("script>"));
        assertFalse(result.contains("alert"));
        assertTrue(result.contains("const a = 1;"), "第一段代码块内容保留");
        assertTrue(result.contains("const b = 2;"), "第二段代码块内容保留");
    }

    // ==================== 边界：不炸 ====================

    @Test
    @DisplayName("null 原样返回，空串原样返回")
    void nullAndEmptyPassThrough() {
        assertNull(MarkdownSanitizer.sanitize(null));
        assertSame("", MarkdownSanitizer.sanitize(""));
    }

    @Test
    @DisplayName("纯代码块内容原样返回")
    void pureCodeBlockPreserved() {
        String md = "```\n<html><body>hi</body></html>\n```";
        assertEquals(md, MarkdownSanitizer.sanitize(md));
    }

    @Test
    @DisplayName("普通文本的小于号被转义（5 < 6 → 5 &lt; 6，渲染等价）")
    void lessThanEscaped() {
        String result = MarkdownSanitizer.sanitize("当 a < b 时成立");
        assertFalse(result.contains("< b"), "裸 < 不应保留（可能被当成标签）");
        assertTrue(result.contains("&lt;") || result.contains("&amp;lt;"));
    }
}
