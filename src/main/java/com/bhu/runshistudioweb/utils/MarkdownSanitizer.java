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
 * 为什么需要它：门户渲染链路是 marked → DOMPurify → v-html，前端已全覆盖；
 * 但后端此前零输出编码——正文字段原样入库、原样下发。任何非本前端的消费方
 * （爬虫展示、第三方客户端、未来的小程序）直接渲染这些字段，就是存储型 XSS。
 * 本类在写入入口兜底：入库的内容先过一遍白名单，任何消费方拿到都安全。
 *
 * 清洗策略与前端 DOMPurify 的关系：两者对象不同，不要当成一套标准——
 * - 前端白名单管的是 marked 生成的 HTML（允许 p/code/table 等标签）；
 * - 本类管的是 入库前的 Markdown 源：策略是「只接受 Markdown 语法，
 * 不接受任何原始 HTML」（空前缀白名单）。它比前端更严，但不会误伤合法内容——
 * Markdown 语法（#、*、-、[]()、|、>）对 HTML 解析器是纯文本，不受影响。
 *
 * 两步清洗：
 * - 去掉所有原始 HTML（ Jsoup.clean + 空前缀白名单）：
 * script、iframe、onerror 等标签与事件属性一起消灭。marked 对原始 HTML 是原样透传的
 * （v15 起移除了内置 sanitize），这是存储型 XSS 的主要入口。
 * - 中和链接 / 图片目的地里的危险 scheme（ javascript: 等）：
 * Markdown 链接语法 [文字](url) 里的 url 不是 HTML，Jsoup 看不见它，
 * 需要单独处理。命中的目的地置空，链接退化成 [文字]()（空 href，不执行）。
 *
 * 代码块为什么原样保留：围栏 / 行内代码里的内容是「要展示的代码」而非
 * markup——任何 conforming 的 Markdown 渲染器都会转义它，不会执行。
 * 而技术博客展示 HTML 片段是刚需（HTML 示例被清掉的话，帖子内容直接残缺），
 * 因此代码块整体避开清洗。
 *
 * 已知边界（如实记录，不装作完美）：
 * - 缩进代码块（4 空格）不识别——其中的 HTML 标签会被清掉，文字保留；
 * - 行内代码用简单反引号配对识别，双反引号等罕见写法可能漏保护；
 * - scheme 混淆只处理大小写与内部空白 / 控制字符（如 java\tscript:），
 * 不处理 HTML 实体编码（ &#106;avascript:）——那类在浏览器侧由
 * DOMPurify 兜（它工作在已解析的 DOM 上，属性值已解码）。
 *
 * @author shaoshing
 */
public final class MarkdownSanitizer {

    /** 空前缀白名单：一个标签都不允许——正文只接受 Markdown 语法，不接受原始 HTML */
    private static final Safelist NO_HTML_TAGS = Safelist.none();

    /**
     * 链接 / 图片的目的地整段：]( ... )，内部不允许跨括号与换行
     *
     * 为什么要整段捕获而不是只取第一个 token：java\tscript:alert(1)
     * 这类混淆把 scheme 拆在空白两侧，只取 [^)\s]+ 会停在空白处拿到 java，
     * 判定必然漏。整段捕获后交给 isDangerousDestination 先剥空白再判。
     *
     * {0,500} 上限防病态回溯（目的地不该比 URL 常识长）；
     * 不允许跨换行——跨行目的地不是合法 Markdown，没必要为它冒险。
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
     * 必须用 String#valueOf 起头：char + int 会走算术提升
     * 变成 int，整个表达式退化成数字而不是字符串（编译期才发现不了语义错误）。
     */
    private static String placeholder(int index) {
        return PLACEHOLDER_START + index + PLACEHOLDER_END;
    }

    private MarkdownSanitizer() {
    }

    /**
     * 清洗一段 Markdown 正文，去掉其中所有原始 HTML 与危险链接
     *
     * <p>为什么这样设计：清洗策略选的是「空前缀白名单」——一个 HTML 标签都不允许，
     * 而不是维护一份「script、iframe、onerror……不许出现」的黑名单。黑名单的根本问题
     * 在于它必须穷举：浏览器支持的标签与事件属性有几百个，还有各种畸形嵌套、
     * 大小写变体、编码变形，漏掉任何一个就是一次存储型 XSS（恶意脚本被存进库，
     * 之后每个看到这段内容的用户都会执行它）。白名单反过来，默认全部拒绝、
     * 只放行明确认可的东西，漏判的后果是「少显示一点」而不是「被注入」。
     * 这里放行的东西是「零个标签」：正文只接受 Markdown 语法，
     * 而 Markdown 的 #、*、-、[]()、|、> 对 HTML 解析器来说都是纯文本，不受影响。
     *
     * <p>为什么代码块要先摘出来再放回去：围栏代码块（三个反引号包裹）和行内代码里
     * 的内容是「要展示的代码」而不是标记，任何合规的 Markdown 渲染器都会转义它、
     * 不会执行。技术博客展示一段 HTML 片段是刚需，如果整体过一遍清洗，
     * 那些标签会被当成危险内容删掉，帖子内容直接残缺。所以顺序是
     * 「先保护、再清洗、最后还原」，不能反过来。
     *
     * <p>执行步骤（与代码里的 ①②③④ 标注一一对应）：
     * <ol>
     *     <li>把围栏代码块与行内代码替换成占位符，原文另存——必须先做，
     *     否则下一步的 HTML 清洗会把代码里的标签一起删掉；先围栏后行内也不能反，
     *     围栏里可能含反引号，先匹配行内会把围栏内容切碎；</li>
     *     <li>去掉所有原始 HTML。这一步排在链接处理之前，因为被清掉的标签里
     *     可能藏着链接语法，先清 HTML 才能保证后面看到的是真实的链接；</li>
     *     <li>中和链接与图片目的地里的危险 scheme。这一步必须单独做：
     *     Markdown 的 [文字](javascript:...) 在 HTML 解析器眼里只是普通文本，
     *     上一步根本看不见它；</li>
     *     <li>把占位符还原成代码原文，得到最终结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里清洗的是「入库前的 Markdown 源」，不是渲染后的 HTML，
     * 因此它与前端 DOMPurify 的白名单不是一套标准，不要以为两边可以互相替代——
     * 前端的对象是 marked 生成的 HTML（允许 p/code/table 等标签），
     * 后端的对象是 Markdown 源（一个标签都不允许）。后端这道是为了保护
     * 「不经过本前端的消费方」（爬虫展示、第三方客户端、未来的小程序），
     * 它们拿到这些字段直接渲染时同样是存储型 XSS。
     *
     * @param markdown 原始正文，允许为 null 或空串（此时原样返回，不抛异常）
     * @return 清洗后的正文；入参为 null 或空串时原样返回。注意清洗会改变内容
     * （删掉原始 HTML），调用方不要用它做「内容有没有变」的判断
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
     * 必须关掉 pretty-print（本方法最容易踩的坑）：Jsoup 默认开着它，
     * 会把连续空白折叠、把换行变成空格——而 Markdown 的段落 / 列表 / 表格 / 标题
     * 全靠换行结构，折叠之后整篇排版就毁了（标题与正文粘成一行、列表项挤成一段）。
     * 关掉后文本节点原样输出，只多一层实体转义（ < → < 等，
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
     * 用 appendReplacement + quoteReplacement 而不是
     * replaceAll(Function)：后者对返回值里的 $ 是否按组引用解释
     * 记忆不可靠，而目的地里出现 $ 完全合法（URL query）——
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
     * 先剥掉所有空白与控制字符再判：浏览器把 java\tscript:、
     * java\nscript:、javascript: 都当作同一个 scheme，
     * 直接 startsWith 会被这些变体绕过。大小写统一转小写（ JaVaScRiPt:）。
     */
    private static boolean isDangerousDestination(String dest) {
        String compact = dest.replaceAll("[\\p{Cntrl}\\s]+", "").toLowerCase(Locale.ROOT);
        return DANGEROUS_SCHEMES.stream().anyMatch(compact::startsWith);
    }
}
