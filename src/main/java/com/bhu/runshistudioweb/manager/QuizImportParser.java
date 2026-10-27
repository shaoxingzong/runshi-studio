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
 * 用「粘贴文本」而不是 Excel：题库来源几乎都是从文档、网页里复制一段题目，粘贴少两步，
 * 而且失败信息可以精确到行号。
 *
 * 一条题一个块，块之间空行分隔（空行不是必需的，遇到下一个题型行也会开新块）。
 * 答案支持行尾 * 与独立「答案：ABD」行，同时存在时以「答案：」为准；只负责语法，
 * 语义校验由 QuizQuestionServiceImpl 负责。
 *
 * @author shaoshing
 */
public final class QuizImportParser {

    /**
     * 题型行：【单选】/ [多选]，允许行首空白
     *
     * 全角与半角方括号都认：从不同来源复制过来时两者都会出现。
     */
    private static final Pattern TYPE_LINE = Pattern.compile(
            "^\\s*[【\\[]\\s*(单选|多选|单项选择题|多项选择题)\\s*[】\\]]\\s*(.*)$");

    /**
     * 选项行：A. / A、/ A． / A: / A）
     *
     * 捕获三段：标签、正文、行尾星号（星号表示这是正确项）。分隔符全角半角都认。
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
     * 用返回值而不是抛异常表达「部分块有错」：管理员从文档里粘贴一大段题，
     * 夹杂几行格式不对的内容是正常输入。用异常的话一次只能看到第一个错，
     * 改掉、再导、再撞下一个，十处错误就要来回十次。
     *
     * 两个列表原样保存引用、不做防御性拷贝，因此可能同时非空——
     * 判断有没有错必须走 {@link #hasError()}，不能拿「questions 是不是空的」来代替。
     *
     * @param questions 成功解析出的题目，按原文顺序；不会为 null，可能为空列表
     * @param errors 逐行错误，行号对应粘贴文本的物理行（1 基）；不会为 null，可能为空列表
     */
    public record ParseResult(List<ParsedQuestion> questions, List<LineError> errors) {

        /**
         * 本批解析里是否有任何错误
         *
         * 封装成方法而不是让调用方各自写 !errors().isEmpty()，是为了让「有错就整批不落库」
         * 这条规则只有一个表达方式。
         *
         * 坑：questions 非空不代表没有错误——部分块成功、部分失败时两个列表会同时非空。
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
     * 只承载「从文本里读出来的东西」，不含 id、状态、难度这些由导入接口补齐的字段。
     *
     * 坑：correctLabels 只保证「非空且都在选项范围内」，没有做「单选恰好 1 个」的语义校验。
     * 调用方必须再走一遍与新增共用的校验，不能直接转成实体入库。
     *
     * @param type 题型（由题型行决定，非 null）
     * @param stem 题干（多行已用空格拼成一行）
     * @param options 选项列表，标签从 A 开始连续
     * @param correctLabels 正确答案标签串，可能未排序
     * @param analysis 解析文本，可为 null
     * @param startLine 该题块在原文里的起始行号（1 基）
     */
    public record ParsedQuestion(QuizQuestionTypeEnum type, String stem, List<QuizOptionItem> options,
                                 String correctLabels, String analysis, int startLine) {
    }

    /**
     * 单条错误：行号 + 原因
     *
     * 只给「有 3 条错误」对管理员没有帮助，他需要知道第几行、错在哪，前端才能逐条渲染。
     *
     * 坑：行号为 0 表示「错误不属于某一行」（例如整批题量超限），前端要单独处理，
     * 否则会显示「第 0 行」。
     *
     * @param line 出错的行号（1 基）；0 表示整批级别的错误
     * @param message 面向管理员的错误原因，已是可直接展示的中文文案
     */
    public record LineError(int line, String message) {
    }

    /**
     * 解析粘贴进来的题目文本，拆成「可落库的题目」与「逐行错误」
     *
     * 坑一：每行必须先 strip。行首缩进常来自复制粘贴，不先去掉的话题型行与选项行的正则都会失配。
     * 坑二：块之间没有空行时靠「下一个题型行」分隔，因此题干里若出现形如「【单选】」的文字，
     * 会被误判成新题的开头——这类内容只能靠管理员改写。
     * 坑三：分支判断顺序不能换。题型行要在选项行之前；答案行与解析行若落到「其它行」分支，
     * 会被当成题干。
     *
     * @param text 待解析的整段文本，可为 null；单批题量由调用方另行限制
     * @return 解析结果，永不为 null；questions 与 errors 都可能为空，也可能同时非空
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
     * 校验集中在这个结算点：一条题的信息分散在多行里，只有整块收齐才能判断「选项数够不够」。
     *
     * 任何一项校验失败就 return null，一个块只报最根本的那条错，否则管理员会看到同一道题
     * 刷出五六条互相矛盾的提示。
     *
     * @param block 待结算的块，可为 null（表示没有进行中的块）
     * @param questions 成功解析出的题目会被追加到这里（就地修改）
     * @param errors 校验失败时追加一条带行号的错误（就地修改）
     * @return 恒为 null，调用方用它清空 current
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
     * 题型文案 → 枚举（「单项选择题」这类长写法也认）
     *
     * 用「以『多』开头或包含『多项』」判断，比枚举出所有写法更耐改。
     * 坑：必须先判多选；若先判单选再 else 成多选，「多项选择题」会漏掉。
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
     * 解析是逐行累积的过程，一条题的信息分散在不定数量的行里；用不可变结构会让每一行
     * 都产生一个新对象，代码反而更难读。块结算后立即丢弃，不会有并发共享的机会。
     *
     * 坑：type 允许为 null。缺题型行时占位块就是用 null 构造的，它的作用只是让后续行
     * 有地方可放，避免每一行都报一次「缺少题型行」——因此使用 type 的地方都必须先判空。
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
