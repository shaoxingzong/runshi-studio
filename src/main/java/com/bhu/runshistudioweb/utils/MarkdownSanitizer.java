package com.bhu.runshistudioweb.utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 正文的白名单清洗（XSS 后端兜底）
 *
 * author: shaoshing
 *
 * <p><b>为什么需要它</b>：门户渲染链路是 marked → DOMPurify → v-html，前端已全覆盖；
 * 但后端此前<b>零输出编码</b>——正文字段原样入库、原样下发。任何非本前端的消费方
 * （爬虫展示、第三方客户端、未来的小程序）直接渲染这些字段，就是存储型 XSS。
 * 本类在<b>写入入口</b>兜底：入库的内容先过一遍白名单，任何消费方拿到都安全。
 *
 * <p><b>清洗策略与前端 DOMPurify 的关系</b>：两者对象不同，不要当成一套标准——
 * <ul>
 *     <li>前端白名单管的是 <b>marked 生成的 HTML</b>（允许 p/code/table 等标签）；</li>
 *     <li>本类管的是 <b>入库前的 Markdown 源</b>：策略是「只接受 Markdown 语法，
 *     不接受任何原始 HTML」（空前缀白名单）。它比前端更严，但不会误伤合法内容——
 *     Markdown 语法（#、*、-、[]()、|、&gt;）对 HTML 解析器是纯文本，不受影响。</li>
 * </ul>
 *
 * <p><b>两步清洗</b>：
 * <ol>
 *     <li><b>去掉所有原始 HTML</b>（{@code Jsoup.clean} + 空前缀白名单）：
 *     {@code <script>}、{@code <iframe>}、{@code <img onerror=...>}、{@code <style>}
 *     一起消灭。marked 对原始 HTML 是原样透传的（v15 起移除了内置 sanitize），
 *     这是存储型 XSS 的主要入口。</li>
 *     <li><b>中和链接 / 图片目的地里的危险 scheme</b>（{@code javascript:} 等）：
 *     Markdown 链接语法 {@code [文字](url)} 里的 url 不是 HTML，Jsoup 看不见它，
 *     需要单独处理。命中的目的地置空，链接退化成 {@code [文字]()}（空 href，不执行）。</li>
 * </ol>
 *
 * <p><b>代码块为什么原样保留</b>：围栏 / 行内代码里的内容是「要展示的代码」而非
 * markup——任何 conforming 的 Markdown 渲染器都会转义它，不会执行。
 * 而技术博客展示 HTML 片段是刚需（{@code <div>} 示例被清掉的话，帖子内容直接残缺），
 * 因此代码块整体避开清洗。
 *
 * <p><b>已知边界（如实记录，不装作完美）</b>：
 * <ul>
 *     <li>缩进代码块（4 空格）不识别——其中的 HTML 标签会被清掉，文字保留；</li>
 *     <li>行内代码用简单反引号配对识别，双反引号等罕见写法可能漏保护；</li>
 *     <li>scheme 混淆只处理大小写与内部空白 / 控制字符（如 {@code java\tscript:}），
 *     不处理 HTML 实体编码（{@code &#106;avascript:}）——那类在浏览器侧由
 *     DOMPurify 兜（它工作在已解析的 DOM 上，属性值已解码）。</li>
 * </ul>
 */
public final class MarkdownSanitizer {

    /** 空前缀白名单：一个标签都不允许——正文只接受 Markdown 语法，不接受原始 HTML */
    private static final Safelist NO_HTML_TAGS = Safelist.none();

    /**
     * 链接 / 图片的目的地整段：{@code ]( ... )}，内部不允许跨括号与换行
     *
     * <p><b>为什么要整段捕获而不是只取第一个 token</b>：{@code java\tscript:alert(1)}
     * 这类混淆把 scheme 拆在空白两侧，只取 {@code [^)\s]+} 会停在空白处拿到 {@code java}，
     * 判定必然漏。整段捕获后交给 {@link #isDangerousDestination} 先剥空白再判。
     *
     * <p>{@code {0,500}} 上限防病态回溯（目的地不该比 URL 常识长）；
     * 不允许跨换行——跨行目的地不是合法 Markdown，没必要为它冒险。
     * 图片语法 {@code ![alt](dest)} 的 {@code ](} 部分与链接相同，一个模式全覆盖。
     */
    private static final Pattern LINK_DESTINATION = Pattern.compile("\\]\\(([^)\\n]{0,500})\\)");

    /** 危险 scheme（统一转小写后比较）：会在浏览器里执行或可承载可执行内容 */
    private static final List<String> DANGEROUS_SCHEMES =
            List.of("javascript:", "vbscript:", "data:", "file:");

    /** 围栏代码块：``` 或 ~~~ 包裹的整段（含围栏行本身），跨行非贪婪配对 */
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)(```.*?```|~~~.*?~~~)");

    /** 行内代码：单反引号包裹、内部不含反引号与换行 */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");

    /**
     * 占位符边界字符：取 Unicode 私用区，正常内容不可能出现，
     * 且 Jsoup 会原样保留（纯文本，不转义不成标签）
     */
    private static final String PLACEHOLDER_START = "\uE000";

    private static final String PLACEHOLDER_END = "\uE001";

    /**
     * 第 {@code index} 个占位符
     *
     * <p>必须用 {@link String#valueOf(char)} 起头：{@code char + int} 会走算术提升
     * 变成 int，整个表达式退化成数字而不是字符串（编译期才发现不了语义错误）。
     */
    private static String placeholder(int index) {
        return PLACEHOLDER_START + index + PLACEHOLDER_END;
    }

    private MarkdownSanitizer() {
    }

    /**
     * 清洗 Markdown 正文
     *
     * @param markdown 原始正文（可为 null）
     * @return 清洗后的正文；入参为 null 或空串时原样返回
     */
    public static String sanitize(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        // ① 代码块先占位：围栏与行内代码里的内容是「展示用代码」，不是 markup，原样保留
        List<String> protectedBlocks = new ArrayList<>();
        String guarded = mask(markdown, FENCED_BLOCK, protectedBlocks);
        guarded = mask(guarded, INLINE_CODE, protectedBlocks);

        // ② 去掉所有原始 HTML（空前缀白名单）：script / iframe / onerror 一起消灭
        String cleaned = stripHtml(guarded);

        // ③ 中和链接 / 图片目的地里的危险 scheme
        cleaned = neutralizeDangerousDestinations(cleaned);

        // ④ 还原代码块
        return unmask(cleaned, protectedBlocks);
    }

    /**
     * 把匹配到的代码块换成占位符，原文存起来
     *
     * @param text    待处理文本
     * @param pattern 代码块模式（先围栏后行内，顺序不能反——围栏里可能含反引号）
     * @param saved   原文收集器（占位符按收集顺序编号）
     */
    private static String mask(String text, Pattern pattern, List<String> saved) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            saved.add(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder(saved.size() - 1)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 还原占位符
     *
     * <p>还原失败（编号不存在）时保留占位符本身而不是抛异常：
     * 清洗是兜底路径，不能因为它把发帖流程搞挂。
     */
    private static String unmask(String text, List<String> saved) {
        for (int i = 0; i < saved.size(); i++) {
            text = text.replace(placeholder(i), saved.get(i));
        }
        return text;
    }

    /**
     * 去掉所有原始 HTML（空前缀白名单）
     *
     * <p><b>必须关掉 pretty-print</b>（本方法最容易踩的坑）：Jsoup 默认开着它，
     * 会把连续空白折叠、把换行变成空格——而 Markdown 的段落 / 列表 / 表格 / 标题
     * <b>全靠换行结构</b>，折叠之后整篇排版就毁了（标题与正文粘成一行、列表项挤成一段）。
     * 关掉后文本节点原样输出，只多一层实体转义（{@code <} → {@code &lt;} 等，
     * Markdown 渲染器会解码，语义不变且反复清洗结果稳定——不会二次转义）。
     */
    private static String stripHtml(String text) {
        Document dirty = Jsoup.parse(text);
        Document cleaned = new Cleaner(NO_HTML_TAGS).clean(dirty);
        cleaned.outputSettings().prettyPrint(false);
        return cleaned.body().html();
    }

    /**
     * 中和链接 / 图片目的地中的危险 scheme
     *
     * <p>用 {@code appendReplacement} + {@code quoteReplacement} 而不是
     * {@code replaceAll(Function)}：后者对返回值里的 {@code $} 是否按组引用解释
     * 记忆不可靠，而目的地里出现 {@code $} 完全合法（URL query）——
     * 用 quoteReplacement 保证「字面替换」，不出意外。
     */
    private static String neutralizeDangerousDestinations(String text) {
        Matcher matcher = LINK_DESTINATION.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String inside = matcher.group(1);
            // 安全：整段原样重建（连空白与标题参数一起，Markdown 语义不变）；
            // 危险：整段置空，链接退化成 [文字]()，不执行
            String replacement = isDangerousDestination(inside) ? "]()" : "](" + inside + ")";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 判断目的地是否为危险 scheme
     *
     * <p>先剥掉所有空白与控制字符再判：浏览器把 {@code java\tscript:}、
     * {@code java\nscript:}、{@code  javascript:} 都当作同一个 scheme，
     * 直接 startsWith 会被这些变体绕过。大小写统一转小写（{@code JaVaScRiPt:}）。
     */
    private static boolean isDangerousDestination(String dest) {
        String compact = dest.replaceAll("[\\p{Cntrl}\\s]+", "").toLowerCase(Locale.ROOT);
        return DANGEROUS_SCHEMES.stream().anyMatch(compact::startsWith);
    }
}
