package com.bhu.runshistudioweb.utils;

import com.bhu.runshistudioweb.constant.QuizConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 选项标签工具：规范化、校验、比较
 *
 * 为什么需要「规范化」这一层：标签是用户/管理员输入的字符串，
 * 同一次选择可以有多种写法—— "AC"、"ca"、"C,A"、" A C "。
 * 如果判分直接比较原始字符串，选对了也会被判错，而且这种错误极难复现
 * （用户说他选的是 A 和 C，日志里存的是 "C,A"）。
 * 统一在入口处归一成「大写 + 去重 + 升序 + 无分隔」，判分就退化成一次
 * equals——简单到不可能出错。
 *
 * 为什么升序而不是保留输入顺序：多选的正确项是集合，
 * 集合没有顺序。归一成升序后，「所选」与「正确项」的比较可以直接用字符串相等，
 * 不必写集合运算，也不存在「顺序不同算错」的坑。
 *
 * 本类是纯函数集合，不依赖 Spring、不碰数据库，因此可以被
 * QuizLabelUtilsTest 用极低成本覆盖全部边界。
 *
 * @author shaoshing
 */
public final class QuizLabelUtils {

    /**
     * 规范化标签串
     *
     * 处理顺序：去空白 → 转大写 → 只保留 A–F 范围内的字母 → 去重 → 升序。
     *
     * 非法字符会被静默丢弃，这是刻意的：本方法只负责「整理」，
     * 是否合法由 validateLabels 判断。若在整理阶段就抛异常，
     * 调用方拿不到「整理后剩下什么」，也就无法给出「你输入的 Z 不是有效选项」这类精确提示。
     *
     * @param raw 原始标签串，可为 null / 空
     * @return 规范化后的标签串（升序、无分隔）；入参为空或没有任何合法字符时返回空串
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // TreeSet 同时完成「去重」与「升序」，避免再排一次
        TreeSet<Character> labels = new TreeSet<>();
        String upper = raw.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
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
     * 校验标签串是否合法，返回第一个不合法字符
     *
     * 两条规则：字符必须落在 A–F，且不能有重复。
     * 重复之所以要拦："AA" 会被 normalize 静默折叠成 "A"，
     * 用户以为选了两个、实际只选了一个——这种「输入被悄悄改写」比直接报错更难排查。
     *
     * @param raw 原始标签串，可为 null
     * @return 第一个问题字符（用于拼提示）；合法时返回 null
     */
    public static Character findInvalidLabel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        java.util.Set<Character> seen = new java.util.HashSet<>();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            // 分隔符与空白直接跳过：它们不是「非法字符」，只是格式
            if (c == ',' || c == '，' || c == ' ' || c == '、') {
                continue;
            }
            if (c < QuizConstant.LABEL_FIRST || c > QuizConstant.LABEL_LAST) {
                return c;
            }
            if (!seen.add(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 标签串转字符列表（升序）
     *
     * @param normalized 已规范化的标签串
     * @return 字符列表；空串返回空列表，不返回 null
     */
    public static List<Character> toList(String normalized) {
        List<Character> labels = new ArrayList<>();
        if (normalized == null) {
            return labels;
        }
        for (int i = 0; i < normalized.length(); i++) {
            labels.add(normalized.charAt(i));
        }
        return labels;
    }

    /**
     * 标签串转 List<String>（升序），供 VO 直接输出
     *
     * @param normalized 已规范化的标签串
     * @return 字符串列表；空串返回空列表
     */
    public static List<String> toLabelList(String normalized) {
        List<String> labels = new ArrayList<>();
        if (normalized == null) {
            return labels;
        }
        for (int i = 0; i < normalized.length(); i++) {
            labels.add(String.valueOf(normalized.charAt(i)));
        }
        return labels;
    }

    /**
     * 按「正确项集合」推出选项标签序列（A、B、C……）
     *
     * 用于把「选项 JSON」与「正确标签」对齐：选项的 label 必须从 A 开始连续，
     * 这条规则由 QuizQuestionServiceImpl 在写入时校验，
     * 本方法只负责生成「应该是哪几个标签」。
     *
     * @param optionCount 选项数量
     * @return 从 A 开始的连续标签列表；optionCount <= 0 时返回空列表
     */
    public static List<String> sequentialLabels(int optionCount) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < optionCount; i++) {
            labels.add(String.valueOf((char) (QuizConstant.LABEL_FIRST + i)));
        }
        return labels;
    }

    private QuizLabelUtils() {
    }
}
