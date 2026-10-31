package com.bhu.runshistudioweb.utils;

import com.bhu.runshistudioweb.constant.QuizConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 选项标签工具：规范化、校验、比较
 *
 * @author shaoshing
 */
public final class QuizLabelUtils {

    /**
     * 把用户输入的标签串整理成「大写 + 去重 + 升序 + 无分隔」的标准形态
     *
     * @param raw 原始标签串，可为 null / 空串；含分隔符、小写字母、非法字符都能接受
     * @return 规范化后的标签串（升序、无分隔）；入参为 null、空串或不含 A–F 字符时返回空串
     */
    public static String normalize(String raw) {
        // 两种「空」走同一条出口，调用方只需判 isEmpty
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // TreeSet 同时完成「去重」与「升序」，避免再排一次
        TreeSet<Character> labels = new TreeSet<>();
        // 先转大写再筛选：顺序反了会把小写字母当非法字符丢掉
        String upper = raw.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            // 只留 A–F：分隔符、空白、其它字母都在这里被静默过滤
            if (c >= QuizConstant.LABEL_FIRST && c <= QuizConstant.LABEL_LAST) {
                labels.add(c);
            }
        }
        StringBuilder sb = new StringBuilder(labels.size());
        for (Character c : labels) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 校验标签串是否合法，返回第一个出问题的字符
     *
     * 两条规则：字符必须落在 A–F 区间，且不能重复。
     *
     * @param raw 原始标签串，可为 null；允许含分隔符与小写字母
     * @return 第一个问题字符（用于拼进错误提示）；完全合法时返回 null，调用方必须用 != null 判断「有错」
     */
    public static Character findInvalidLabel(String raw) {
        // 空 = 「还没选」，不是「选错了」
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // 与 normalize 同一套大小写规则，避免两处结论打架
        String upper = raw.toUpperCase(Locale.ROOT);
        java.util.Set<Character> seen = new java.util.HashSet<>();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            // 分隔符与空白直接跳过：它们不是「非法字符」，只是格式
            if (c == ',' || c == '，' || c == ' ' || c == '、') {
                continue;
            }
            // 越界优先报：这类错误的提示最明确，也最该排在重复之前
            if (c < QuizConstant.LABEL_FIRST || c > QuizConstant.LABEL_LAST) {
                return c;
            }
            // add 返回 false 说明前面已经出现过，重复必须拦（normalize 会把它吞掉）
            if (!seen.add(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 把已规范化的标签串拆成字符列表（保持升序）
     *
     * @param normalized 已规范化的标签串，可为 null
     * @return 字符列表；空串或 null 返回空列表（不是 null），调用方可直接遍历
     */
    public static List<Character> toList(String normalized) {
        List<Character> labels = new ArrayList<>();
        // null 与空串同义：都表示「没有标签」
        if (normalized == null) {
            return labels;
        }
        // 逐字符展开，保持原有的升序
        for (int i = 0; i < normalized.length(); i++) {
            labels.add(normalized.charAt(i));
        }
        return labels;
    }

    /**
     * 把已规范化的标签串拆成字符串列表，供 VO 直接输出给前端
     *
     * @param normalized 已规范化的标签串，可为 null
     * @return 字符串列表；空串或 null 返回空列表（不是 null）
     */
    public static List<String> toLabelList(String normalized) {
        List<String> labels = new ArrayList<>();
        // 与 toList 保持同一套「空值同义」约定
        if (normalized == null) {
            return labels;
        }
        // 在这里一次性完成 Character → String 的转换，别让输出形态散落到调用方
        for (int i = 0; i < normalized.length(); i++) {
            labels.add(String.valueOf(normalized.charAt(i)));
        }
        return labels;
    }

    /**
     * 按选项数量生成「应该出现」的标签序列：A、B、C……
     *
     * @param optionCount 选项数量；传 0 或负数时返回空列表
     * @return 从 A 开始的连续标签列表，长度等于 optionCount
     */
    public static List<String> sequentialLabels(int optionCount) {
        List<String> labels = new ArrayList<>();
        // 以 LABEL_FIRST 为起点按偏移生成，避免各处硬编码 'A'
        for (int i = 0; i < optionCount; i++) {
            labels.add(String.valueOf((char) (QuizConstant.LABEL_FIRST + i)));
        }
        return labels;
    }

    private QuizLabelUtils() {
    }
}
