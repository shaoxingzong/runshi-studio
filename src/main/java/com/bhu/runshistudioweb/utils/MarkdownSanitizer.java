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
 * 前端渲染链路是 marked → DOMPurify → v-html，已全覆盖；但后端此前零输出编码，
 * 正文字段原样入库、原样下发。任何非本前端的消费方（爬虫展示、第三方客户端）
 * 直接渲染这些字段就是存储型 XSS，所以写入入口要兜一道。
 *
 * 对象是「入库前的 Markdown 源」而非渲染后的 HTML：策略是空前缀白名单，
 * 一个标签都不放行。这比前端 DOMPurify 更严，但不会误伤——Markdown 的
 * #、*、-、[]()、|、> 对 HTML 解析器都是纯文本。
 *
 * @author shaoshing
 */
public final class MarkdownSanitizer {

    /** 空前缀白名单：一个标签都不允许——正文只接受 Markdown 语法，不接受原始 HTML */
    private static final Safelist NO_HTML_TAGS = Safelist.none();

    /**
     * 链接 / 图片的目的地整段：]( ... )，内部不允许跨括号与换行
     *
     * 必须整段捕获而不是只取第一个 token：java\tscript:alert(1) 这类混淆把 scheme
     * 拆在空白两侧，只取 [^)\s]+ 会停在空白处拿到 java，判定必然漏。
     *
     * {0,500} 上限防病态回溯；不允许跨换行——跨行目的地不是合法 Markdown。
     * 图片语法 ![alt](dest) 的 ]( 部分与链接相同，一个模式全覆盖。
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
     * 第 index 个占位符
     *
     * 必须用 String#valueOf 起头：char + int 会走算术提升变成 int，
     * 整个表达式退化成数字而不是字符串。
     */
    private static String placeholder(int index) {
        return PLACEHOLDER_START + index + PLACEHOLDER_END;
    }

    private MarkdownSanitizer() {
    }

    /**
     * 清洗一段 Markdown 正文，去掉其中所有原始 HTML 与危险链接
     *
     * 顺序是「先保护代码块、再清 HTML、再中和链接、最后还原」，不能调整：
     * 代码块里的 HTML 片段是技术博客的刚需（会被渲染器转义而非执行），
     * 整体过一遍清洗会把帖子内容删残；链接中和必须排在清 HTML 之后，
     * 否则被删标签里藏着的链接语法会被当成真实链接处理。
     *
     * @param markdown 原始正文，允许为 null 或空串（此时原样返回，不抛异常）
     * @return 清洗后的正文；注意清洗会改变内容（删掉原始 HTML），
     * 调用方不要用它做「内容有没有变」的判断
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
     * @param text 待处理文本
     * @param pattern 代码块模式（先围栏后行内，顺序不能反——围栏里可能含反引号）
     * @param saved 原文收集器（占位符按收集顺序编号）
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
     * 还原失败（编号不存在）时保留占位符本身而不是抛异常：
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
     * 必须关掉 pretty-print：Jsoup 默认开着它，会把连续空白折叠、把换行变成空格，
     * 而 Markdown 的段落 / 列表 / 表格 / 标题全靠换行结构，折叠之后整篇排版就毁了。
     * 关掉后文本节点原样输出，只多一层实体转义（Markdown 渲染器会解码，语义不变）。
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
     * 用 appendReplacement + quoteReplacement 而不是 replaceAll(Function)：
     * 后者对返回值里的 $ 是否按组引用解释记忆不可靠，而目的地里出现 $ 完全合法（URL query），
     * quoteReplacement 保证字面替换。
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
     * 先剥掉所有空白与控制字符再判：浏览器把 java\tscript:、java\nscript:、javascript:
     * 都当作同一个 scheme，直接 startsWith 会被这些变体绕过。大小写统一转小写。
     */
    private static boolean isDangerousDestination(String dest) {
        String compact = dest.replaceAll("[\\p{Cntrl}\\s]+", "").toLowerCase(Locale.ROOT);
        return DANGEROUS_SCHEMES.stream().anyMatch(compact::startsWith);
    }
}
