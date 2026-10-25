package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.constant.QuizConstant;
import com.bhu.runshistudioweb.model.dto.quiz.QuizOptionItem;
import com.bhu.runshistudioweb.model.enums.QuizQuestionTypeEnum;
import com.bhu.runshistudioweb.utils.QuizLabelUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题目批量导入的文本解析器（纯函数，不碰数据库）
 *
 * 为什么是「粘贴文本」而不是 Excel / 文件上传：
 * 题库的来源几乎都是从文档、网页、PDF 里复制一段题目，粘贴比
 * 「下载模板 → 填 → 上传」少两步；而且文本解析的失败信息可以精确到行号，
 * 比「文件第 3 个 sheet 第 12 行有问题」直观得多。
 *
 * 支持的格式（一条题一个块，块之间空行分隔；空行不是必需的，
 * 遇到下一个「题型行」也会开新块）：
 * 【单选】物质的唯一特性是？
 * A. 运动性
 * B. 客观实在性
 * C. 可知性
 * D. 矛盾性
 * 答案：B
 * 解析：客观实在性是物质的唯一特性；运动是物质的根本属性。
 *
 * 【多选】下列属于唯物辩证法基本规律的有？
 * A. 对立统一规律*
 * B. 质量互变规律*
 * C. 否定之否定规律*
 * D. 因果律
 *
 * 两种答案写法，同时存在时以「答案：」为准：
 * - 行尾 *：贴真题时最常见；
 * - 独立 答案：ABD 行：从教材/题库导出时最常见。
 * 为什么以「答案：」为准：它是显式声明，而 * 可能只是题干里的强调符号
 * （虽然我们只在选项行里认它）。显式优先于隐式，是这类解析的通用纪律。
 *
 * 解析器只负责「语法」，不负责「语义校验」：
 * 「单选题有 2 个正确项」这类跨字段规则由 QuizQuestionServiceImpl 校验，
 * 因为新增/修改/导入三条入口必须共用同一套规则。这里只保证
 * 「能解析出来的字段是合法的」（标签连续、选项数在范围内、有正确答案）。
 *
 * 本类不依赖 Spring、不写库，因此单元测试可以用极低成本覆盖全部边界。
 *
 * @author shaoshing
 */
public final class QuizImportParser {

    /**
     * 题型行：【单选】/ [多选]，允许行首空白
     *
     * 同时认全角方括号与半角方括号：从不同来源复制过来时两者都会出现。
     */
    private static final Pattern TYPE_LINE = Pattern.compile(
            "^\\s*[【\\[]\\s*(单选|多选|单项选择题|多项选择题)\\s*[】\\]]\\s*(.*)$");

    /**
     * 选项行：A. / A、/ A． / A: / A）
     *
     * 捕获三段：标签、正文、行尾星号（星号表示「这是正确项」）。
     * 分隔符全角半角都认——这是粘贴文本最常见的差异来源。
     */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s*([A-Fa-f])\\s*[.、．:：)）]\\s*(.+?)\\s*(\\*)?\\s*$");

    /** 答案行： 答案：ABD / 正确答案: A */
    private static final Pattern ANSWER_LINE = Pattern.compile(
            "^\\s*(?:正确)?答案\\s*[:：]\\s*([A-Fa-f,\\s，、]+)\\s*$");

    /** 解析行： 解析：……（可多行，后续行追加到解析里） */
    private static final Pattern ANALYSIS_LINE = Pattern.compile(
            "^\\s*(?:解析|分析|解答)\\s*[:：]\\s*(.*)$");

    /**
     * 一次解析的完整产物：成功解析出的题目 + 逐行错误，两者一起返回
     *
     * <p>为什么这样设计：做成「一个对象装两个列表」，而不是「返回题目列表、出错就抛异常」。
     * 因为对导入这个场景来说，「部分块有错」是正常输入而不是异常——管理员从文档里
     * 粘贴一大段题，本来就可能夹杂几行格式不对的内容。若用抛异常表达，
     * 调用方一次只能看到第一个错：改掉、再导、再撞下一个，十处错误就要来回十次。
     * 用返回值把「成功」与「失败」一起带出来，管理员能一次看完所有问题，
     * 改完再导一遍即可。
     * 另一层考虑是：解析器的职责被刻意限制在「语法」这一层，它不知道数据库、不认识实体，
     * 因此它没有资格决定「有错就整批拒绝」——那是一条业务规则，属于导入服务。
     * 本对象只是把事实如实带出去，判断留给调用方。
     * 同时它做成 record（不可变的数据载体），两个列表在构造后不再被替换，
     * 调用方可以放心把它在方法之间传递，不必担心被谁改了引用。
     *
     * <p>执行步骤（record 没有方法体，这里的「步骤」是构造本对象时实际发生的事）：
     * <ol>
     *     <li>① 按声明顺序接收两个列表，并<b>原样保存引用</b>，不做防御性拷贝——
     *     这意味着调用方后续对同一个列表追加元素，本对象看到的内容也会跟着变。
     *     解析器正是利用这一点：它在循环里持续往两个列表追加，最后才一次性包成本对象；</li>
     *     <li>② 两个分量都不做非空校验，因此传入 null 不会被拦下，而是在调用
     *     {@link #hasError()} 或遍历时抛空指针——这是刻意的：解析器永远传非空列表，
     *     加一层兜底只会掩盖「调用方传错了」这个真正的问题。</li>
     * </ol>
     *
     * <p>容易踩的坑：两个列表可能<b>同时非空</b>——一部分块解析成功、另一部分失败时
     * 就是这样。因此绝不能拿「questions 是不是空的」来判断「有没有错」，
     * 那是两件不同的事；判断必须走 {@link #hasError()}。
     * 反过来也一样：errors 为空也不代表「一定能落库」，因为这里只做了语法校验，
     * 「单选题却给了 2 个正确项」这类跨字段规则要等导入服务再校验一遍。
     * 另一个坑是记录里的对象本身可变（ParsedQuestion 里的选项列表没有做不可变包装），
     * 所以「record 不可变」只保证引用不变，不保证内容不被改。
     *
     * @param questions 成功解析出的题目，按原文出现顺序排列；不会为 null，
     *                  但可能为空列表（整段文本全是坏行时）
     * @param errors 逐行错误，行号对应粘贴文本的物理行（1 基）；不会为 null，
     *               但可能为空列表（整段文本全部合法时）
     */
    public record ParseResult(List<ParsedQuestion> questions, List<LineError> errors) {

        /**
         * 本批解析里是否有任何错误
         *
         * <p>为什么这样设计：调用方（导入服务）需要区分「全部合法可以落库」与
         * 「至少有一条错，整批都不该落库」这两种情况。把判断封装成一个方法，
         * 而不是让调用方各自写 !errors().isEmpty()，是为了让「有错就整批不落库」
         * 这条规则只有一个表达方式——将来若改成「允许跳过坏行」，
         * 只需要改这里，不必去找散落各处的 isEmpty 判断。
         *
         * <p>执行步骤：
         * <ol>
         *     <li>① 直接看错误列表是否非空：错误在解析阶段与校验阶段都会被追加，
         *     因此这个判断同时覆盖「语法错」与「字段错」两类。</li>
         * </ol>
         *
         * <p>容易踩的坑：questions 非空不代表没有错误——一部分块成功、一部分失败时，
         * 两个列表会同时非空。因此绝不能拿「有没有解析出题目」来代替本方法。
         *
         * @return true 表示至少有一条错误，此时调用方不应落库
         */
        public boolean hasError() {
            return !errors.isEmpty();
        }
    }

    /**
     * 单条解析出的题目（尚未落库）
     *
     * <p>为什么这样设计：它只承载「从文本里读出来的东西」，不含 id、状态、难度、
     * 题序这些需要由导入接口补齐的字段。把「解析产物」与「待入库实体」分开，
     * 是为了让解析器完全不认识数据库：它连题目表长什么样都不知道，
     * 因此可以脱离 Spring 与数据库单独测试。
     *
     * <p>容易踩的坑：correctLabels 在这里还没有做「单选恰好 1 个」这类语义校验，
     * 只保证「非空且都在选项范围内」。调用方必须再走一遍与新增共用的校验，
     * 不能直接把本对象转成实体入库。
     *
     * @param type 题型（由题型行决定，非 null）
     * @param stem 题干（多行已用空格拼成一行）
     * @param options 选项列表，标签从 A 开始连续
     * @param correctLabels 正确答案标签串（来自「答案：」行或行尾星号，可能未排序）
     * @param analysis 解析文本，可为 null（没有解析行时）
     * @param startLine 该题块在原文里的起始行号（1 基），用于把错误定位到行
     */
    public record ParsedQuestion(QuizQuestionTypeEnum type, String stem, List<QuizOptionItem> options,
                                 String correctLabels, String analysis, int startLine) {
    }

    /**
     * 单条错误：行号 + 原因
     *
     * <p>为什么这样设计：只给「有 3 条错误」对管理员没有帮助，他需要知道「第几行、
     * 错在哪」。把行号与原因绑成一个不可变对象，前端就能逐条渲染成
     * 「第 12 行：缺少正确答案」，管理员照着改即可，不必自己在整段文本里找。
     *
     * <p>容易踩的坑：行号为 0 表示「错误不属于某一行」（例如整批题量超限），
     * 前端渲染时要把 0 单独处理，否则会显示「第 0 行」这种让人困惑的文案。
     *
     * @param line 出错的行号（1 基）；0 表示整批级别的错误，不指向具体行
     * @param message 面向管理员的错误原因，已是可直接展示的中文文案
     */
    public record LineError(int line, String message) {
    }

    /**
     * 解析粘贴进来的题目文本，拆成「可落库的题目」与「逐行错误」
     *
     * <p>为什么这样设计：解析器的职责边界被刻意划在「语法」这一层——
     * 它只保证「能解析出来的字段是合法的」（标签连续、选项数在范围内、有正确答案），
     * 而「单选题有 2 个正确项」这类跨字段语义规则交给 QuizQuestionServiceImpl 校验。
     * 因为新增、修改、导入三条入口必须共用同一套语义规则，如果解析器也做一遍，
     * 规则就有两份，迟早出现「导入能进、编辑不让改」。
     * 另一个取舍是用返回值表达「部分块有错」而不是抛异常：坏行是正常输入，
     * 一次性把所有错误还给管理员，他才不用改一个错再撞下一个。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空文本直接给空结果：null 与纯空白都表示「没粘东西」，
     *     交给调用方提示，解析器不把它当成错误；</li>
     *     <li>② 按行切分并逐行处理，每行先 strip：行首的缩进常来自复制粘贴，
     *     不先去掉的话题型行与选项行的正则都会匹配失败；</li>
     *     <li>③ 遇到空行就结算当前块：空行是块之间最自然的分隔符，
     *     先 flush 再继续，保证上一块不会把下一块的内容吸进来；</li>
     *     <li>④ 遇到题型行先结算上一块、再开新块：这一步必须在选项行判断之前，
     *     否则「【单选】」开头的行会被当成无法识别的行；</li>
     *     <li>⑤ 没有题型行却有内容时报错，并造一个占位块：占位块的作用是让后续的
     *     选项行有地方可放，避免同一段内容每一行都报一次「缺少题型行」；</li>
     *     <li>⑥ 依次尝试选项行、答案行、解析行、解析续行、题干续行：顺序不能换——
     *     答案行与解析行的正则都以「答案/解析」开头，若先试选项行，它们不会被误匹配，
     *     但若先试「其它行」分支，它们就会全被当成题干；</li>
     *     <li>⑦ 循环结束后再结算一次：最后一块后面没有空行，不会触发③的 flush。</li>
     * </ol>
     *
     * <p>容易踩的坑：块之间没有空行时，靠「下一个题型行」来分隔，因此题干里若
     * 出现形如「【单选】」的文字，会被误判成新题的开头。这类内容只能靠管理员改写，
     * 解析器无法区分「题型标记」与「题干里的引用」。另外答案行以「答案：」为准、
     * 行尾星号次之——显式声明优先于隐式标记，因为星号也可能只是题干里的强调符号。
     *
     * @param text 待解析的整段文本，可为 null；行数不限，但单批题量由调用方另行限制
     * @return 解析结果，永不为 null；questions 与 errors 都不会是 null，
     *         两者可能同时非空（一部分块成功、一部分失败）
     */
    public static ParseResult parse(String text) {
        List<ParsedQuestion> questions = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        // ① 没粘内容不算错，交给调用方提示
        if (text == null || text.isBlank()) {
            return new ParseResult(questions, errors);
        }

        // ② 按行切分；下面的「行号」一律是 1 基的真实行号，方便管理员对着编辑器改
        List<String> lines = text.lines().toList();
        Builder current = null;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String raw = lines.get(i);
            // 先去缩进：复制粘贴带来的行首空白会让正则全部失配
            String line = raw.strip();

            // ③ 空行：结束当前块（但不报错）
            if (line.isEmpty()) {
                current = flush(current, questions, errors);
                continue;
            }

            // ④ 题型行：开新块（如果上一个是未完成的块，先结算它）
            Matcher typeMatcher = TYPE_LINE.matcher(line);
            if (typeMatcher.matches()) {
                current = flush(current, questions, errors);
                current = new Builder(lineNo, typeNameToType(typeMatcher.group(1)));
                // 题型行后面可能直接跟题干（【单选】物质的唯一特性是？）
                String rest = typeMatcher.group(2).strip();
                if (!rest.isEmpty()) {
                    current.stemLines.add(rest);
                }
                continue;
            }

            // ⑤ 没有题型行就开始内容：报错，但继续往下解析（尽量多报几个错）
            if (current == null) {
                errors.add(new LineError(lineNo, "题目缺少题型行（应写【单选】或【多选】）"));
                // 造一个占位块，避免后续每一行都报同一个错
                current = new Builder(lineNo, null);
                current.stemLines.add(line);
                continue;
            }

            // ⑥ 选项行
            Matcher optionMatcher = OPTION_LINE.matcher(line);
            if (optionMatcher.matches()) {
                String label = optionMatcher.group(1).toUpperCase();
                String content = optionMatcher.group(2).strip();
                boolean correct = optionMatcher.group(3) != null;
                current.addOption(label, content, correct);
                continue;
            }

            // ⑥ 答案行
            Matcher answerMatcher = ANSWER_LINE.matcher(line);
            if (answerMatcher.matches()) {
                // 显式答案先规范化再存：后面 flush 时直接与选项标签比较
                current.explicitAnswer = QuizLabelUtils.normalize(answerMatcher.group(1));
                continue;
            }

            // ⑥ 解析行：后续的非空行都追加进解析（解析可以换行）
            Matcher analysisMatcher = ANALYSIS_LINE.matcher(line);
            if (analysisMatcher.matches()) {
                current.analysisLines.add(analysisMatcher.group(1).strip());
                continue;
            }
            // 解析续行：只有「已经开始解析」且「已经有选项」时才认，
            // 否则「A. xxx」之后的一行普通文字会被误吞进解析
            if (!current.analysisLines.isEmpty() && !current.options.isEmpty()) {
                // 已经在解析段里：这一行是解析的续行
                current.analysisLines.add(line);
                continue;
            }

            // ⑥ 其它行：还没遇到选项时算题干（题干可以换行），已经遇到选项就算无法识别
            if (current.options.isEmpty()) {
                current.stemLines.add(line);
            } else {
                // 选项已出现又冒出无法识别的行：多半是漏了「答案：」前缀，必须报出来
                errors.add(new LineError(lineNo, "无法识别的行（应为选项、答案或解析）"));
            }
        }
        // ⑦ 最后一块后面没有空行，必须在这里补一次结算
        flush(current, questions, errors);

        return new ParseResult(questions, errors);
    }

    /**
     * 结算一个块：把累积到的行校验成一条 ParsedQuestion，失败则记一条错误
     *
     * <p>为什么这样设计：解析过程中每一行都是「先累积、后校验」，因为一条题的信息
     * 分散在多行里，只有整块收齐才能判断对错。因此校验必须集中在这个结算点，
     * 而不是在逐行解析时零散地判断——那样「选项数够不够」这种跨行规则就没法写。
     * 另一个设计是「任何一项校验失败就 return null」：一个块只报一条错，
     * 报最根本的那个（题型 → 题干 → 选项 → 标签 → 答案 → 解析），
     * 否则管理员会看到同一道题刷出五六条互相矛盾的提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① null 直接返回：调用方用「current = flush(...)」来清空当前块，
     *     恒返回 null 让这个赋值语句本身就表达了「结算完毕」；</li>
     *     <li>② 空块直接丢弃：连续空行会反复触发 flush，不能把它们记成错误；</li>
     *     <li>③ 缺题型行先报：题型决定后续所有规则（选项数下限、正确项个数），
     *     题型不明时报别的都是在猜；</li>
     *     <li>④ 题干拼好再判空与长度：题干可以多行，必须先 join 才知道真实长度；</li>
     *     <li>⑤ 选项数按题型取不同下限：单选与多选的规则不同，写死一个值必然误伤；</li>
     *     <li>⑥ 标签连续性用「实际 vs 标准序列」比较：A、B、D 这种跳号在界面上
     *     会显示断档，用户会以为渲染坏了，而且正确答案 "ABD" 在复盘时也对不上；</li>
     *     <li>⑦ 取正确答案时显式「答案：」优先于行尾星号：显式声明优先于隐式标记，
     *     因为星号也可能只是题干里的强调符号（虽然只在选项行里认它）；</li>
     *     <li>⑧ 正确答案必须都在选项范围内：否则会存下一条永远判不对的题；</li>
     *     <li>⑨ 解析可空，但超长要拦：超长解析会被数据库截断，截断位置在句中，
     *     用户看到的解析会莫名其妙断掉；</li>
     *     <li>⑩ 全部通过才加入结果：这一步放在最后，保证 questions 里只有完整合法的题。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只做「语法 + 字段级」校验，不做「单选必须恰好 1 个正确项」
     * 这类语义校验。因此解析通过不代表能入库——调用方还要走一遍与新增共用的校验。
     * 若在这里也加一遍语义规则，规则就有两份，改一处漏一处时会出现
     * 「预览通过、落库失败」的割裂体验。
     *
     * @param block 待结算的块，可为 null（表示没有进行中的块）
     * @param questions 成功解析出的题目会被追加到这里（就地修改）
     * @param errors 校验失败时追加一条带行号的错误（就地修改）
     * @return 恒为 null，调用方用它清空 current，语义比返回 boolean 更清楚
     */
    private static Builder flush(Builder block, List<ParsedQuestion> questions, List<LineError> errors) {
        // ① 没有进行中的块：直接返回，赋值语句本身就表达了「已结算」
        if (block == null) {
            return null;
        }
        // ② 空块（连续空行）直接丢弃，不报错
        if (block.type == null && block.stemLines.isEmpty() && block.options.isEmpty()) {
            return null;
        }

        // ③ 题型决定后面所有规则，它不明就先报它
        if (block.type == null) {
            errors.add(new LineError(block.startLine, "题目缺少题型行（应写【单选】或【多选】）"));
            return null;
        }

        // ④ 题干是多行拼起来的，必须 join 之后才知道真实内容与长度
        String stem = String.join(" ", block.stemLines).strip();
        if (stem.isEmpty()) {
            errors.add(new LineError(block.startLine, "题干为空"));
            return null;
        }
        if (stem.length() > QuizConstant.STEM_MAX_LENGTH) {
            errors.add(new LineError(block.startLine,
                    "题干超过 " + QuizConstant.STEM_MAX_LENGTH + " 个字符"));
            return null;
        }

        // ⑤ 选项数下限按题型区分：单选与多选的要求不同，写死一个值必然误伤
        int minOptions = block.type == QuizQuestionTypeEnum.SINGLE
                ? QuizConstant.OPTION_MIN_SINGLE
                : QuizConstant.OPTION_MIN_MULTIPLE;
        if (block.options.size() < minOptions) {
            errors.add(new LineError(block.startLine,
                    block.type.getDesc() + "题至少需要 " + minOptions + " 个选项"));
            return null;
        }
        if (block.options.size() > QuizConstant.OPTION_MAX) {
            errors.add(new LineError(block.startLine,
                    "选项不能超过 " + QuizConstant.OPTION_MAX + " 个"));
            return null;
        }

        // ⑥ 标签必须从 A 开始连续：A、B、D 这种跳号会在界面上显示断档，
        // 用户会以为渲染坏了；而且正确答案 "ABD" 很难在复盘时对上
        List<String> labels = block.options.stream().map(QuizOptionItem::getLabel).toList();
        List<String> expected = QuizLabelUtils.sequentialLabels(block.options.size());
        if (!labels.equals(expected)) {
            errors.add(new LineError(block.startLine,
                    "选项标签必须从 A 开始连续，实际为 " + String.join("、", labels)));
            return null;
        }

        // ⑦ 正确答案：显式「答案：」优先于行尾星号
        String correct;
        if (block.explicitAnswer != null && !block.explicitAnswer.isEmpty()) {
            correct = block.explicitAnswer;
        } else {
            // 星号集合是 LinkedHashSet（输入顺序），拼成串后再靠 normalize 的约定保持可比
            correct = String.join("", block.starLabels);
        }
        if (correct.isEmpty()) {
            errors.add(new LineError(block.startLine, "缺少正确答案（行尾标 * 或写「答案：B」）"));
            return null;
        }
        // ⑧ 正确项必须都属于已有选项：否则会存下一条永远判不对的题
        for (char c : correct.toCharArray()) {
            if (!labels.contains(String.valueOf(c))) {
                errors.add(new LineError(block.startLine, "正确答案 " + c + " 不在选项范围内"));
                return null;
            }
        }

        // ⑨ 解析允许为空（null），但超长必须拦：数据库截断会断在句子中间
        String analysis = block.analysisLines.isEmpty() ? null : String.join("\n", block.analysisLines).strip();
        if (analysis != null && analysis.length() > QuizConstant.ANALYSIS_MAX_LENGTH) {
            errors.add(new LineError(block.startLine,
                    "解析超过 " + QuizConstant.ANALYSIS_MAX_LENGTH + " 个字符"));
            return null;
        }

        // ⑩ 全部通过才加入结果，保证 questions 里只有完整合法的题
        questions.add(new ParsedQuestion(block.type, stem, block.options, correct, analysis, block.startLine));
        return null;
    }

    /**
     * 题型文案 → 枚举（"单项选择题" 这类长写法也认）
     *
     * <p>为什么这样设计：粘贴文本里的题型写法不统一，短到「多选」、长到「多项选择题」
     * 都会出现。用「以『多』开头或包含『多项』」来判断，比枚举出所有写法更耐改——
     * 新增一种说法时不必动代码。反过来，只要不是多选就归为单选，
     * 因为题型正则里已经把可识别的字样限定在这两组之内，这里不需要再兜底。
     *
     * <p>容易踩的坑：判断顺序必须是「先判多选」，若先判单选再 else 成多选，
     * 「多项选择题」会被 startWith("单") 之外的逻辑漏掉。本方法只被题型行调用，
     * 因此传入的字符串一定是正则已经筛过的，不必考虑 null。
     *
     * @param name 题型行捕获到的题型文案，如「单选」「多选」「单项选择题」
     * @return 对应的题型枚举；除多选外一律返回单选
     */
    private static QuizQuestionTypeEnum typeNameToType(String name) {
        return name.startsWith("多") || name.contains("多项")
                ? QuizQuestionTypeEnum.MULTIPLE
                : QuizQuestionTypeEnum.SINGLE;
    }

    /**
     * 块构建器（可变，只在解析过程中存活）
     *
     * <p>为什么这样设计：解析是「逐行累积」的过程——题干可能两行、选项必然多行、
     * 解析也可能换行，一条题的信息分散在不定数量的行里。用不可变结构会让每一行都
     * 产生一个新对象，代码反而更难读。因此这里用可变对象承载「正在拼装的那道题」，
     * 块结算后立即丢弃，不会有并发共享的机会。
     *
     * <p>容易踩的坑：type 允许为 null。缺题型行时占位块就是用 null 构造的，
     * 它的作用只是让后续行有地方可放，避免每一行都报一次「缺少题型行」。
     * 因此任何使用 type 的地方都必须先判空（flush 的第一步就是这么做的）。
     */
    private static final class Builder {

        private final int startLine;

        private final QuizQuestionTypeEnum type;

        private final List<String> stemLines = new ArrayList<>();

        private final List<QuizOptionItem> options = new ArrayList<>();

        /** 行尾星号标出的正确项（保持输入顺序，最终会被排序成升序串） */
        private final Set<String> starLabels = new LinkedHashSet<>();

        private final List<String> analysisLines = new ArrayList<>();

        /** 显式「答案：」行（优先于星号） */
        private String explicitAnswer;

        private Builder(int startLine, QuizQuestionTypeEnum type) {
            this.startLine = startLine;
            this.type = type;
        }

        private void addOption(String label, String content, boolean correct) {
            QuizOptionItem option = new QuizOptionItem();
            // 标签在调用方已转大写，这里只负责原样收下，顺序即输入顺序
            option.setLabel(label);
            option.setContent(content);
            options.add(option);
            // 行尾星号单独记一份：结算时「答案：」行优先于它，两者不能混在同一个容器里
            if (correct) {
                starLabels.add(label);
            }
        }
    }

    private QuizImportParser() {
    }
}
