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
 * 本类是纯函数集合（同样的入参永远得到同样的结果，不读也不改任何外部状态），
 * 因此不依赖 Spring、不碰数据库，可以被单元测试用极低成本覆盖全部边界。
 *
 * @author shaoshing
 */
public final class QuizLabelUtils {

    /**
     * 把用户输入的标签串整理成「大写 + 去重 + 升序 + 无分隔」的标准形态
     *
     * <p>为什么这样设计：判分、存库、比对三处必须看到同一个形态的标签串。若各写一套解析，
     * 「选对了却判错」这种问题会以「用户说他选的是 A 和 C、库里存的是 C,A」的形式出现，
     * 且几乎无法复现。把整理动作收敛成一个纯函数之后，判分退化为一次字符串 equals——
     * 比集合运算少一层出错空间，也不存在「顺序不同就算错」的坑。
     * 另一个取舍是「非法字符静默丢弃」而不是抛异常：本方法只负责整理，合法性由
     * findInvalidLabel 判定；若整理阶段就抛错，调用方拿不到「整理之后剩下什么」，
     * 也就无法给出「你输入的 Z 不是有效选项」这类精确提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空值直接返回空串：null 与空串在本方法里语义相同（都是「什么都没选」），
     *     调用方随后统一用 isEmpty 判断，不必为两种「空」写两个分支；</li>
     *     <li>② 先整体转大写再逐字符筛选：转大写必须发生在筛选之前，否则小写 a–f 会被
     *     当成非法字符丢掉，用户点了 A 却得到「没选任何项」；</li>
     *     <li>③ 用 TreeSet 收集 A–F 范围内的字符，一次完成去重与升序，
     *     省掉「先收集到 List、再 distinct、再 sort」三段容易漏掉其一的代码；</li>
     *     <li>④ 按 TreeSet 的迭代顺序拼成无分隔字符串，逗号与空格在这一步被彻底丢弃，
     *     于是 "C, A" 与 "AC" 得到同一个结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：Locale.ROOT 不能省。在土耳其语环境下 "i".toUpperCase() 会变成带点的
     * "İ"，用默认 Locale 会让同一个输入在不同服务器上整理出不同结果。另外本方法会把重复
     * 标签折叠掉（"AA" 变成 "A"），所以「用户以为选了两个、实际只剩一个」必须在
     * findInvalidLabel 里拦下，只靠本方法发现不了。
     *
     * @param raw 原始标签串，可为 null / 空串；含分隔符、小写字母、非法字符都能接受，
     *            传 null 不会抛异常
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
     * <p>为什么这样设计：normalize 是「宽容」的——它会静默丢字符、静默去重。宽容对判分有利
     * （用户怎么输都能对上），但对「录题」和「作答校验」有害：输入被悄悄改写后，用户以为
     * 自己选了两个、系统只收到一个，这种偏差比直接报错难查得多。所以合法性判断单独成一个
     * 方法，由调用方在 normalize 之前调用，报错时还能把具体是哪个字符还回去拼提示。
     * 两条规则是：字符必须落在 A–F 区间，且不能重复。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空值视为合法（返回 null）：空串在「还没选」的语义下不算错误，
     *     「必须选点什么」由调用方另外判断，两件事不该混在一个方法里；</li>
     *     <li>② 先转大写再逐字符检查：与 normalize 保持同一套大小写规则，
     *     否则 "a" 会在这里被判非法、在 normalize 里又被接受，两处结论互相矛盾；</li>
     *     <li>③ 跳过逗号、中文逗号、空格、顿号：它们是格式而非内容，
     *     如果算非法，"A,C" 这种最常见的写法反而过不了校验；</li>
     *     <li>④ 越界字符立即返回：这是「输入了 Z 之类不存在的选项」，
     *     越早返回越能让提示指向第一个真正的问题字符；</li>
     *     <li>⑤ 用 Set 记录见过的字符，add 返回 false 即重复：重复必须拦下，
     *     因为 normalize 会把 "AA" 折叠成 "A"，用户会以为选了两个。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的是 Character 而不是 boolean，调用方必须判 null 而不是判 true。
     * 另一个反直觉点是「空串合法」——把「非空」也塞进来会让录题时「正确答案为空」的提示
     * 变成「包含非法字符：null」，管理员根本不知道要改什么。
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
     * <p>为什么这样设计：Java 的 String 没有现成的「拆成字符集合」操作，而调用方
     * （判分前「所选标签是否都属于本题选项」的包含校验）需要一个能调 containsAll 的集合。
     * 直接返回 char[] 不行——char[] 没有集合语义；返回 Set 又会丢掉顺序，
     * 而顺序在这里恰好是「升序」这一约定的证据。列表是唯一同时满足「可比较」「有顺序」的形态。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先建好空列表：保证任何分支都返回非 null，调用方不必判空；</li>
     *     <li>② null 直接返回空列表：null 与空串在本类中同义，都是「没有标签」；</li>
     *     <li>③ 逐下标取字符并加入列表：这里不排序也不去重，因为入参约定已经是
     *     normalize 的产物，重复排一次只会掩盖「调用方传了未规范化的串」这个错误。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不做规范化。若误把用户原始输入传进来，"C,A" 会被拆成
     * 逗号、A、C 三个元素，随后的包含校验就会把逗号当成一个选项标签。调用方必须
     * 先调 normalize 再调本方法。
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
     * <p>为什么这样设计：toList 返回的是 List&lt;Character&gt;，Jackson 序列化后是
     * ["A","C"] 还是 ["A", 67] 取决于版本与配置，前端拿到什么形状并不确定。
     * 接口契约里把标签定成字符串数组之后，前端就不必关心字符与码点的区别，
     * 也不必为「有时候是字符串、有时候是数字」写兼容分支。因此本类特意保留两个
     * 形态不同的方法：内部计算用 Character，对外输出用 String，转换点只有这一处。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先建空列表：保证返回非 null；</li>
     *     <li>② null 直接返回空列表，与 toList 保持同一套「空值同义」约定；</li>
     *     <li>③ 逐字符转成 String 再放入列表：用 String.valueOf 而不是 char + ""，
     *     避免编译器在拼接时引入 StringConcatFactory 的额外开销，也让意图更直白。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在调用方自己写 stream().map(String::valueOf) 再收集——
     * 那样「输出形态」就又散落回各处了，将来接口改成小写或带分隔符时漏改一处，
     * 前端就会看到两种格式混在同一个响应里。
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
     * <p>为什么这样设计：选项标签必须从 A 开始连续，这条规则在录题（新增 / 修改 / 导入）
     * 和解析（导入文本）两个地方都要用。与其在每处写一段 for 循环拼字符，不如在这里
     * 生成一份「标准答案」，调用方拿它和实际标签列表做一次 equals 即可。这样规则只有
     * 一个定义，改「最多支持几个选项」时不会出现某处还按旧上限生成的错位。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 从 LABEL_FIRST（即 'A'）开始按偏移量生成：用常量而不是硬编码 'A'，
     *     是为了让「标签从哪个字母开始」这件事只有一个定义；</li>
     *     <li>② 循环次数就是选项数量，因此返回列表的长度恒等于 optionCount；
     *     调用方可以直接用长度或 equals 做校验，不必再数一遍。</li>
     * </ol>
     *
     * <p>容易踩的坑：入参为 0 或负数时返回空列表，不会抛异常也不会返回 null。
     * 调用方若拿它去校验一个空选项列表，得到的是「空列表 equals 空列表」即通过——
     * 「选项不能为空」必须由调用方自己先拦，本方法不承担这个职责。
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
