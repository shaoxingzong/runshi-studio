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
 * @author shaoshing
 */
public final class MarkdownSanitizer {

    /** 空前缀白名单：一个标签都不允许——正文只接受 Markdown 语法，不接受原始 HTML */
    private static final Safelist NO_HTML_TAGS = Safelist.none();

    /** 链接 / 图片的目的地整段：]( ... )，内部不允许跨括号与换行 */
    private static final Pattern LINK_DESTINATION = Pattern.compile("\\]\\(([^)\\n]{0,500})\\)");

    /** 危险 scheme（统一转小写后比较）：会在浏览器里执行或可承载可执行内容 */
    private static final List<String> DANGEROUS_SCHEMES =
            List.of("javascript:", "vbscript:", "data:", "file:");

    /** 围栏代码块：``` 或 ~~~ 包裹的整段（含围栏行本身），跨行非贪婪配对 */
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)(```.*?```|~~~.*?~~~)");

    /** 行内代码：单反引号包裹、内部不含反引号与换行 */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");

    /** 占位符边界字符：取 Unicode 私用区，正常内容不可能出现，且 Jsoup 会原样保留（纯文本，不转义不成标签） */
    private static final String PLACEHOLDER_START = "\uE000";

    private static final String PLACEHOLDER_END = "\uE001";

    /** 第 index 个占位符 */
    private static String placeholder(int index) {
        return PLACEHOLDER_START + index + PLACEHOLDER_END;
    }

    private MarkdownSanitizer() {
    }

    /**
     * 清洗一段 Markdown 正文，去掉其中所有原始 HTML 与危险链接
     *
     * @param markdown 原始正文，允许为 null 或空串（此时原样返回，不抛异常）
     * @return 清洗后的正文；注意清洗会改变内容（删掉原始 HTML），调用方不要用它做「内容有没有变」的判断
     */
    public static String sanitize(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        // 代码块先占位：围栏与行内代码里的内容是「展示用代码」，不是 markup，原样保留
        List<String> protectedBlocks = new ArrayList<>();
        String guarded = mask(markdown, FENCED_BLOCK, protectedBlocks);
        guarded = mask(guarded, INLINE_CODE, protectedBlocks);

        // 去掉所有原始 HTML（空前缀白名单）：script / iframe / onerror 一起消灭
        String cleaned = stripHtml(guarded);

        // 中和链接 / 图片目的地里的危险 scheme
        cleaned = neutralizeDangerousDestinations(cleaned);

        // 还原代码块
        return unmask(cleaned, protectedBlocks);
    }

    /** 把匹配到的代码块换成占位符，原文存起来 */
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

    /** 还原占位符 */
    private static String unmask(String text, List<String> saved) {
        for (int i = 0; i < saved.size(); i++) {
            text = text.replace(placeholder(i), saved.get(i));
        }
        return text;
    }

    /** 去掉所有原始 HTML（空前缀白名单） */
    private static String stripHtml(String text) {
        Document dirty = Jsoup.parse(text);
        Document cleaned = new Cleaner(NO_HTML_TAGS).clean(dirty);
        cleaned.outputSettings().prettyPrint(false);
        return cleaned.body().html();
    }

    /** 中和链接 / 图片目的地中的危险 scheme */
    private static String neutralizeDangerousDestinations(String text) {
        Matcher matcher = LINK_DESTINATION.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String inside = matcher.group(1);
            String replacement = isDangerousDestination(inside) ? "]()" : "](" + inside + ")";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 判断目的地是否为危险 scheme */
    private static boolean isDangerousDestination(String dest) {
        String compact = dest.replaceAll("[\\p{Cntrl}\\s]+", "").toLowerCase(Locale.ROOT);
        return DANGEROUS_SCHEMES.stream().anyMatch(compact::startsWith);
    }
}
