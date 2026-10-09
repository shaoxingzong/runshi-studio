package com.bhu.runshistudioweb.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * AC 自动机（Aho-Corasick）：一次扫描在文本里找出所有出现的敏感词
 *
 * author: shaoshing
 *
 * <p><b>为什么不用逐词 {@code text.contains(word)}</b>：那是 O(词库规模 × 文本长度)，
 * 词库上千条时每条内容都要做上千次扫描。AC 自动机把词库预先编成一棵带 fail 指针的 Trie，
 * 之后无论词库多大，扫描都是 <b>O(文本长度)</b>——这是多模式匹配该用的结构。
 *
 * <p><b>为什么不用 Hutool 的 {@code cn.hutool.dfa.WordTree}</b>：
 * <ul>
 *     <li>它是 DFA/Trie，<b>没有 fail 指针</b>，失配时要靠回溯重新扫，不是真正的 AC；</li>
 *     <li>更实际的问题：它只在 {@code hutool-all} 里，而本项目引的是 {@code hutool-crypto}
 *     （带出 {@code hutool-core}），classpath 上根本没有这个包——为了它把依赖换成全量 Hutool，
 *     等于为一个百来行的功能拖进整个工具集，与 pom 里那段注释的选择相悖。</li>
 * </ul>
 *
 * <p><b>本类的分工</b>：只做算法，<b>不碰文件与配置</b>。词库的读取、构建时机与热更新由
 * {@code manager/SensitiveWordManager} 负责。
 * 这和 {@code IpUtils} / {@code ClientIpManager} 的关系是同一套路——
 * 纯算法能被单测直接覆盖，不该被生命周期绑住。
 *
 * <p><b>变体处理</b>：匹配前先把文本归一化（全角转半角、大写转小写、跳过干扰符），
 * 所以 {@code 敏*感*词}、{@code 敏 感 词}、{@code ＭＬＧＢ} 这类写法都能命中。
 * 归一化会改变字符位置，因此内部维护一张「归一化后位置 → 原文位置」的映射表，
 * 返回的 {@link Hit} 区间是<b>原文</b>的索引，可直接用于高亮或定位。
 *
 * <p><b>线程安全</b>：树建好后不再修改，{@link #match} 只做只读遍历 + 创建局部结果，
 * 因此可以被多个线程并发调用。
 */
public final class AcAutomaton {

    /**
     * 默认干扰符：这些字符会被直接跳过再匹配
     *
     * <p>覆盖的是最常见的规避写法——在词中间插符号或空格。
     * 注意<b>不含</b>字母数字：跳过它们会把正常英文单词撕碎，造成大量误命中。
     */
    private static final Set<Character> DEFAULT_STOP_CHARS = Set.of(
            ' ', '\t', '*', '-', '_', '.', ',', '~', '+', '·', '|', '/');

    /** Trie 节点 */
    private static final class Node {
        /** 字符转移表（goto 表） */
        final Map<Character, Node> next = new HashMap<>();
        /** 失配指针，root 的 fail 指向自己，简化回溯时的判空 */
        Node fail;
        /**
         * 在此节点结束的词（可多个：词库里可能存在前缀关系的词）
         *
         * <p>构建 fail 指针时会把 fail 链上的 output <b>合并进来</b>，
         * 这样匹配时只看当前节点即可，不必沿 fail 链向上回溯——把每次命中的代价压成 O(1)。
         */
        final List<Word> output = new ArrayList<>();
    }

    /**
     * 词库中的一个词
     *
     * @param normalized 归一化后的形式，用于建树与计算命中长度
     * @param original   词库里的原始写法，用于对外报告（用户看得懂的形式）
     */
    private record Word(String normalized, String original) {
    }

    /**
     * 一次命中
     *
     * @param start 在<b>原文</b>中的起始下标（含）
     * @param end   在<b>原文</b>中的结束下标（不含），即 {@code text[start, end)}
     * @param word  命中的词（词库原始写法）
     */
    public record Hit(int start, int end, String word) {
    }

    private final Node root = new Node();
    private final Set<Character> stopChars;

    private AcAutomaton(Set<Character> stopChars) {
        this.stopChars = stopChars;
        this.root.fail = root;
    }

    /**
     * 按词库构建自动机（使用默认干扰符集合）
     *
     * @param words 词库，可为 null（构建出一个永远不命中的空自动机）
     * @return 构建完成的自动机
     */
    public static AcAutomaton build(Collection<String> words) {
        return build(words, DEFAULT_STOP_CHARS);
    }

    /**
     * 按词库构建自动机
     *
     * @param words     词库，可为 null
     * @param stopChars 干扰符集合（在归一化阶段被跳过），传 null 表示不跳过任何字符
     * @return 构建完成的自动机
     */
    public static AcAutomaton build(Collection<String> words, Set<Character> stopChars) {
        AcAutomaton automaton = new AcAutomaton(stopChars == null ? Set.of() : stopChars);
        if (words != null) {
            for (String word : words) {
                automaton.insert(word);
            }
        }
        automaton.buildFailPointers();
        return automaton;
    }

    /**
     * 判断文本中是否含有任意敏感词（不需要位置时用这个，语义更直接）
     *
     * @param text 待检查文本，可为 null
     * @return 命中返回 true
     */
    public boolean contains(String text) {
        return !match(text).isEmpty();
    }

    /**
     * 找出文本中所有命中的敏感词
     *
     * @param text 待检查文本，可为 null 或空
     * @return 命中列表（按在文本中出现的结束位置递增）；无命中返回空列表，不返回 null
     */
    public List<Hit> match(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // ① 归一化，同时记下每个字符在原文中的位置
        StringBuilder normalized = new StringBuilder(text.length());
        int[] indexMap = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = normalizeChar(text.charAt(i));
            if (stopChars.contains(c)) {
                continue;
            }
            normalized.append(c);
            indexMap[normalized.length() - 1] = i;
        }
        if (normalized.length() == 0) {
            return List.of();
        }

        // ② 单趟扫描：失配就跳 fail，命中就收集当前节点的 output
        List<Hit> hits = new ArrayList<>();
        Node current = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            while (current != root && !current.next.containsKey(c)) {
                current = current.fail;
            }
            current = current.next.getOrDefault(c, root);

            if (!current.output.isEmpty()) {
                for (Word word : current.output) {
                    int startInNormalized = i - word.normalized().length() + 1;
                    if (startInNormalized < 0) {
                        continue;
                    }
                    // 映射回原文：起点取归一化后首字符的原文位置，终点取末字符的原文位置 +1
                    hits.add(new Hit(indexMap[startInNormalized], indexMap[i] + 1, word.original()));
                }
            }
        }
        return hits;
    }

    /**
     * 插入一个词
     *
     * <p>词本身也要归一化：这样词库写「敏感」就能命中正文里的「敏*感」。
     * 存入 output 的是<b>原始写法</b>，对外报告时用户看到的仍是词库里的样子。
     */
    private void insert(String rawWord) {
        if (rawWord == null) {
            return;
        }
        String normalized = normalizePhrase(rawWord);
        if (normalized.isEmpty()) {
            return;
        }
        Node current = root;
        for (char c : normalized.toCharArray()) {
            current = current.next.computeIfAbsent(c, key -> new Node());
        }
        current.output.add(new Word(normalized, rawWord.trim()));
    }

    /**
     * BFS 构建 fail 指针，并顺带把 fail 链上的 output 合并到各节点
     *
     * <p>合并这一步是性能关键：不做的话，每次命中都要沿 fail 链一路向上收集，
     * 链长与词长相关；合并后匹配时只看当前节点即可。
     */
    private void buildFailPointers() {
        Queue<Node> queue = new ArrayDeque<>();
        // 第一层的 fail 直接指向 root
        for (Node child : root.next.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.next.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();
                // 从父节点的 fail 开始找一条同样能接受 c 的路径
                Node failTo = current.fail;
                while (failTo != root && !failTo.next.containsKey(c)) {
                    failTo = failTo.fail;
                }
                child.fail = failTo.next.getOrDefault(c, root);
                child.output.addAll(child.fail.output);
                queue.add(child);
            }
        }
    }

    /**
     * 整串归一化：逐字符处理并去掉干扰符
     */
    private String normalizePhrase(String phrase) {
        StringBuilder builder = new StringBuilder(phrase.length());
        for (int i = 0; i < phrase.length(); i++) {
            char c = normalizeChar(phrase.charAt(i));
            if (!stopChars.contains(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * 单字符归一化：全角转半角 + 大写转小写
     *
     * <p>全角区段 {@code ！(FF01) ~ ～(FF5E)} 与 ASCII 的 {@code ! ~ ~} 相差固定值 0xFEE0，
     * 直接减去即可；全角空格（3000）单独处理，它会先变成半角空格、
     * 再由干扰符集合决定是否跳过。
     */
    private static char normalizeChar(char c) {
        // 全角区段 ！（FF01）~ ～（FF5E）与 ASCII 的 ! ~ ~ 相差固定值 0xFEE0
        char normalized = (c >= '\uFF01' && c <= '\uFF5E') ? (char) (c - 0xFEE0) : c;
        // 全角空格（3000）不在上面那个区段内，单独处理
        if (normalized == '\u3000') {
            normalized = ' ';
        }
        // 转小写必须在全角处理**之后**再做：
        // 全角大写字母（如 Ｍ）只转半角得到的是 'M'，若不再转小写，
        // 就与词库里的小写形式对不上——早期实现正是把 toLowerCase 写在全角分支之后
        // 又用 return 提前跳出，导致 ＭＬＧＢ 命中不了 mlgb（由 AcAutomatonTest 守住）
        return Character.toLowerCase(normalized);
    }
}
