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
 * @author shaoshing
 */
public final class QuizImportParser {

    /** 题型行：【单选】/ [多选]，允许行首空白 */
    private static final Pattern TYPE_LINE = Pattern.compile(
            "^\\s*[【\\[]\\s*(单选|多选|单项选择题|多项选择题)\\s*[】\\]]\\s*(.*)$");

    /** 选项行：A. / A、/ A． / A: / A） */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s*([A-Fa-f])\\s*[.、．:：)）]\\s*(.+?)\\s*(\\*)?\\s*$");

    /** 答案行：答案：ABD / 正确答案: A */
    private static final Pattern ANSWER_LINE = Pattern.compile(
            "^\\s*(?:正确)?答案\\s*[:：]\\s*([A-Fa-f,\\s，、]+)\\s*$");

    /** 解析行：解析：……（可多行，后续行追加到解析里） */
    private static final Pattern ANALYSIS_LINE = Pattern.compile(
            "^\\s*(?:解析|分析|解答)\\s*[:：]\\s*(.*)$");

    /**
     * 一次解析的完整产物：成功解析出的题目 + 逐行错误，两者一起返回
     *
     * @param questions 成功解析出的题目，按原文顺序；不会为 null，可能为空列表
     * @param errors 逐行错误，行号对应粘贴文本的物理行（1 基）；不会为 null，可能为空列表
     */
    public record ParseResult(List<ParsedQuestion> questions, List<LineError> errors) {

        /**
         * 本批解析里是否有任何错误
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
     * @param line 出错的行号（1 基）；0 表示整批级别的错误
     * @param message 面向管理员的错误原因，已是可直接展示的中文文案
     */
    public record LineError(int line, String message) {
    }

    /**
     * 解析粘贴进来的题目文本，拆成「可落库的题目」与「逐行错误」
     *
     * @param text 待解析的整段文本，可为 null；单批题量由调用方另行限制
     * @return 解析结果，永不为 null；questions 与 errors 都可能为空，也可能同时非空
     */
    public static ParseResult parse(String text) {
        List<ParsedQuestion> questions = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        // 没粘内容不算错，交给调用方提示
        if (text == null || text.isBlank()) {
            return new ParseResult(questions, errors);
        }

        // 按行切分；下面的「行号」一律是 1 基的真实行号，方便管理员对着编辑器改
        List<String> lines = text.lines().toList();
        Builder current = null;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String raw = lines.get(i);
            // 先去缩进：复制粘贴带来的行首空白会让正则全部失配
            String line = raw.strip();

            // 空行：结束当前块（但不报错）
            if (line.isEmpty()) {
                current = flush(current, questions, errors);
                continue;
            }

            // 题型行：开新块（如果上一个是未完成的块，先结算它）
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

            // 没有题型行就开始内容：报错，但继续往下解析（尽量多报几个错）
            if (current == null) {
                errors.add(new LineError(lineNo, "题目缺少题型行（应写【单选】或【多选】）"));
                // 造一个占位块，避免后续每一行都报同一个错
                current = new Builder(lineNo, null);
                current.stemLines.add(line);
                continue;
            }

            // 选项行
            Matcher optionMatcher = OPTION_LINE.matcher(line);
            if (optionMatcher.matches()) {
                String label = optionMatcher.group(1).toUpperCase();
                String content = optionMatcher.group(2).strip();
                boolean correct = optionMatcher.group(3) != null;
                current.addOption(label, content, correct);
                continue;
            }

            // 答案行
            Matcher answerMatcher = ANSWER_LINE.matcher(line);
            if (answerMatcher.matches()) {
                // 显式答案先规范化再存：后面 flush 时直接与选项标签比较
                current.explicitAnswer = QuizLabelUtils.normalize(answerMatcher.group(1));
                continue;
            }

            // 解析行：后续的非空行都追加进解析（解析可以换行）
            Matcher analysisMatcher = ANALYSIS_LINE.matcher(line);
            if (analysisMatcher.matches()) {
                current.analysisLines.add(analysisMatcher.group(1).strip());
                continue;
            }
            if (!current.analysisLines.isEmpty() && !current.options.isEmpty()) {
                // 已经在解析段里：这一行是解析的续行
                current.analysisLines.add(line);
                continue;
            }

            // 其它行：还没遇到选项时算题干（题干可以换行），已经遇到选项就算无法识别
            if (current.options.isEmpty()) {
                current.stemLines.add(line);
            } else {
                // 选项已出现又冒出无法识别的行：多半是漏了「答案：」前缀，必须报出来
                errors.add(new LineError(lineNo, "无法识别的行（应为选项、答案或解析）"));
            }
        }
        // 最后一块后面没有空行，必须在这里补一次结算
        flush(current, questions, errors);

        return new ParseResult(questions, errors);
    }

    /** 结算一个块：把累积到的行校验成一条 ParsedQuestion，失败则记一条错误 */
    private static Builder flush(Builder block, List<ParsedQuestion> questions, List<LineError> errors) {
        // 没有进行中的块：直接返回，赋值语句本身就表达了「已结算」
        if (block == null) {
            return null;
        }
        // 空块（连续空行）直接丢弃，不报错
        if (block.type == null && block.stemLines.isEmpty() && block.options.isEmpty()) {
            return null;
        }

        // 题型决定后面所有规则，它不明就先报它
        if (block.type == null) {
            errors.add(new LineError(block.startLine, "题目缺少题型行（应写【单选】或【多选】）"));
            return null;
        }

        // 题干是多行拼起来的，必须 join 之后才知道真实内容与长度
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

        // 选项数下限按题型区分：单选与多选的要求不同，写死一个值必然误伤
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

        List<String> labels = block.options.stream().map(QuizOptionItem::getLabel).toList();
        List<String> expected = QuizLabelUtils.sequentialLabels(block.options.size());
        if (!labels.equals(expected)) {
            errors.add(new LineError(block.startLine,
                    "选项标签必须从 A 开始连续，实际为 " + String.join("、", labels)));
            return null;
        }

        // 正确答案：显式「答案：」优先于行尾星号
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
        // 正确项必须都属于已有选项：否则会存下一条永远判不对的题
        for (char c : correct.toCharArray()) {
            if (!labels.contains(String.valueOf(c))) {
                errors.add(new LineError(block.startLine, "正确答案 " + c + " 不在选项范围内"));
                return null;
            }
        }

        // 解析允许为空（null），但超长必须拦：数据库截断会断在句子中间
        String analysis = block.analysisLines.isEmpty() ? null : String.join("\n", block.analysisLines).strip();
        if (analysis != null && analysis.length() > QuizConstant.ANALYSIS_MAX_LENGTH) {
            errors.add(new LineError(block.startLine,
                    "解析超过 " + QuizConstant.ANALYSIS_MAX_LENGTH + " 个字符"));
            return null;
        }

        // 全部通过才加入结果，保证 questions 里只有完整合法的题
        questions.add(new ParsedQuestion(block.type, stem, block.options, correct, analysis, block.startLine));
        return null;
    }

    /** 题型文案 → 枚举（「单项选择题」这类长写法也认） */
    private static QuizQuestionTypeEnum typeNameToType(String name) {
        return name.startsWith("多") || name.contains("多项")
                ? QuizQuestionTypeEnum.MULTIPLE
                : QuizQuestionTypeEnum.SINGLE;
    }

    /** 块构建器（可变，只在解析过程中存活） */
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
