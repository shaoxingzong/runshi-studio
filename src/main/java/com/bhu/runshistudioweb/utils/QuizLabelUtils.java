package com.bhu.runshistudioweb.utils;

import com.bhu.runshistudioweb.constant.QuizConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 选项标签工具：规范化、校验、比较
 *
 * 标签是用户 / 管理员输入的字符串，同一次选择可以有 "AC"、"ca"、"C,A"、" A C " 多种写法。
 * 直接比较原始字符串会让「选对了却判错」，且极难复现。统一归一成「大写 + 去重 + 升序 + 无分隔」后，
 * 判分就退化成一次 equals。
 *
 * 升序而不是保留输入顺序：多选的正确项是集合，集合没有顺序。归一成升序后不必写集合运算，
 * 也不存在「顺序不同算错」的坑。
 *
 * @author shaoshing
 */
public final class QuizLabelUtils {

    /**
     * 把用户输入的标签串整理成「大写 + 去重 + 升序 + 无分隔」的标准形态
     *
     * 非法字符静默丢弃而不是抛异常：本方法只负责整理，合法性由
     * {@link #findInvalidLabel(String)} 判定。若整理阶段就抛错，调用方拿不到
     * 「整理之后剩下什么」，也就无法给出「你输入的 Z 不是有效选项」这类精确提示。
     *
     * @param raw 原始标签串，可为 null / 空串；含分隔符、小写字母、非法字符都能接受
     * @return 规范化后的标签串（升序、无分隔）；入参为 null、空串或不含任何 A–F 字符时
     *         返回空串，调用方必须用 isEmpty 判断「有没有选」，不要与 null 比较
     */
    public static String normalize(String raw) {
        // ① 两种「空」走同一条出口，调用方只需判 isEmpty
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // ③ TreeSet 同时完成「去重」与「升序」，避免再排一次
        TreeSet<Character> labels = new TreeSet<>();
        // ② 先转大写再筛选：顺序反了会把小写字母当非法字符丢掉
        String upper = raw.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            // 只留 A–F：分隔符、空白、其它字母都在这里被静默过滤
            if (c >= QuizConstant.LABEL_FIRST && c <= QuizConstant.LABEL_LAST) {
                labels.add(c);
            }
        }
        StringBuilder sb = new StringBuilder(labels.size());
        // ④ 迭代顺序即升序，拼出来的串天然是「无分隔 + 有序」
        for (Character c : labels) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 校验标签串是否合法，返回第一个出问题的字符
     *
     * 宽容的 {@link #normalize(String)} 对判分有利，但对「录题」和「作答校验」有害：
     * 输入被悄悄改写后，用户以为自己选了两个、系统只收到一个。所以合法性判断单独成一个方法，
     * 由调用方在 normalize 之前调用，报错时还能把具体是哪个字符还回去拼提示。
     *
     * 两条规则：字符必须落在 A–F 区间，且不能重复。
     *
     * @param raw 原始标签串，可为 null；允许含分隔符与小写字母
     * @return 第一个问题字符（用于拼进错误提示）；完全合法时返回 null，
     *         调用方必须用 != null 判断「有错」
     */
    public static Character findInvalidLabel(String raw) {
        // ① 空 = 「还没选」，不是「选错了」
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // ② 与 normalize 同一套大小写规则，避免两处结论打架
        String upper = raw.toUpperCase(Locale.ROOT);
        java.util.Set<Character> seen = new java.util.HashSet<>();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            // ③ 分隔符与空白直接跳过：它们不是「非法字符」，只是格式
            if (c == ',' || c == '，' || c == ' ' || c == '、') {
                continue;
            }
            // ④ 越界优先报：这类错误的提示最明确，也最该排在重复之前
            if (c < QuizConstant.LABEL_FIRST || c > QuizConstant.LABEL_LAST) {
                return c;
            }
            // ⑤ add 返回 false 说明前面已经出现过，重复必须拦（normalize 会把它吞掉）
            if (!seen.add(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 把已规范化的标签串拆成字符列表（保持升序）
     *
     * 调用方（判分前「所选标签是否都属于本题选项」的包含校验）需要一个能调 containsAll 的集合。
     * char[] 没有集合语义，Set 又丢掉顺序，列表是唯一同时满足「可比较」「有顺序」的形态。
     *
     * 本方法不做规范化：误传原始输入 "C,A" 会被拆成逗号、A、C 三个元素，
     * 随后的包含校验就会把逗号当成一个选项标签。调用方必须先 normalize。
     *
     * @param normalized 已规范化的标签串，可为 null
     * @return 字符列表；空串或 null 返回空列表（不是 null），调用方可直接遍历
     */
    public static List<Character> toList(String normalized) {
        List<Character> labels = new ArrayList<>();
        // ② null 与空串同义：都表示「没有标签」
        if (normalized == null) {
            return labels;
        }
        // ③ 逐字符展开，保持原有的升序
        for (int i = 0; i < normalized.length(); i++) {
            labels.add(normalized.charAt(i));
        }
        return labels;
    }

    /**
     * 把已规范化的标签串拆成字符串列表，供 VO 直接输出给前端
     *
     * 接口契约里把标签定成字符串数组：List&lt;Character&gt; 经 Jackson 序列化后是
     * ["A","C"] 还是 ["A", 67] 取决于版本与配置，前端不该为此写兼容分支。
     * 因此内部计算用 Character，对外输出用 String，转换点只有这一处。
     *
     * @param normalized 已规范化的标签串，可为 null
     * @return 字符串列表；空串或 null 返回空列表（不是 null）
     */
    public static List<String> toLabelList(String normalized) {
        List<String> labels = new ArrayList<>();
        // ② 与 toList 保持同一套「空值同义」约定
        if (normalized == null) {
            return labels;
        }
        // ③ 在这里一次性完成 Character → String 的转换，别让输出形态散落到调用方
        for (int i = 0; i < normalized.length(); i++) {
            labels.add(String.valueOf(normalized.charAt(i)));
        }
        return labels;
    }

    /**
     * 按选项数量生成「应该出现」的标签序列：A、B、C……
     *
     * 「标签必须从 A 开始连续」这条规则在录题与解析两处都要用。生成一份标准答案，
     * 调用方拿它和实际标签列表做一次 equals 即可，规则只有一个定义。
     *
     * 入参为 0 或负数时返回空列表，不抛异常也不返回 null：「选项不能为空」
     * 必须由调用方自己先拦，本方法不承担这个职责。
     *
     * @param optionCount 选项数量；传 0 或负数时返回空列表
     * @return 从 A 开始的连续标签列表，长度等于 optionCount
     */
    public static List<String> sequentialLabels(int optionCount) {
        List<String> labels = new ArrayList<>();
        // ① 以 LABEL_FIRST 为起点按偏移生成，避免各处硬编码 'A'
        for (int i = 0; i < optionCount; i++) {
            labels.add(String.valueOf((char) (QuizConstant.LABEL_FIRST + i)));
        }
        return labels;
    }

    private QuizLabelUtils() {
    }
}
