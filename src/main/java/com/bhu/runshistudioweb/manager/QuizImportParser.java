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
 * 题目批量导入的文本解析器（<b>纯函数，不碰数据库</b>）
 *
 * author: shaoshing
 *
 * <p><b>为什么是「粘贴文本」而不是 Excel / 文件上传</b>：
 * 题库的来源几乎都是从文档、网页、PDF 里复制一段题目，粘贴比
 * 「下载模板 → 填 → 上传」少两步；而且文本解析的失败信息可以精确到行号，
 * 比「文件第 3 个 sheet 第 12 行有问题」直观得多。
 *
 * <p><b>支持的格式</b>（一条题一个块，块之间空行分隔；空行不是必需的，
 * 遇到下一个「题型行」也会开新块）：
 * <pre>
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
 * </pre>
 *
 * <p><b>两种答案写法，同时存在时以「答案：」为准</b>：
 * <ul>
 *     <li>行尾 {@code *}：贴真题时最常见；</li>
 *     <li>独立 {@code 答案：ABD} 行：从教材/题库导出时最常见。</li>
 * </ul>
 * 为什么以「答案：」为准：它是显式声明，而 {@code *} 可能只是题干里的强调符号
 * （虽然我们只在选项行里认它）。显式优先于隐式，是这类解析的通用纪律。
 *
 * <p><b>解析器只负责「语法」，不负责「语义校验」</b>：
 * 「单选题有 2 个正确项」这类跨字段规则由 {@code QuizQuestionServiceImpl} 校验，
 * 因为新增/修改/导入三条入口必须共用同一套规则。这里只保证
 * 「能解析出来的字段是合法的」（标签连续、选项数在范围内、有正确答案）。
 *
 * <p>本类不依赖 Spring、不写库，因此 {@code QuizImportParserTest} 可以用
 * 极低成本覆盖全部边界。
 */
public final class QuizImportParser {

    /**
     * 题型行：{@code 【单选】} / {@code [多选]}，允许行首空白
     *
     * <p>同时认全角方括号与半角方括号：从不同来源复制过来时两者都会出现。
     */
    private static final Pattern TYPE_LINE = Pattern.compile(
            "^\\s*[【\\[]\\s*(单选|多选|单项选择题|多项选择题)\\s*[】\\]]\\s*(.*)$");

    /**
     * 选项行：{@code A.} / {@code A、} / {@code A．} / {@code A:} / {@code A）}
     *
     * <p>捕获三段：标签、正文、行尾星号（星号表示「这是正确项」）。
     * 分隔符全角半角都认——这是粘贴文本最常见的差异来源。
     */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s*([A-Fa-f])\\s*[.、．:：)）]\\s*(.+?)\\s*(\\*)?\\s*$");

    /**
     * 答案行：{@code 答案：ABD} / {@code 正确答案: A}
     */
    private static final Pattern ANSWER_LINE = Pattern.compile(
            "^\\s*(?:正确)?答案\\s*[:：]\\s*([A-Fa-f,\\s，、]+)\\s*$");

    /**
     * 解析行：{@code 解析：……}（可多行，后续行追加到解析里）
     */
    private static final Pattern ANALYSIS_LINE = Pattern.compile(
            "^\\s*(?:解析|分析|解答)\\s*[:：]\\s*(.*)$");

    /**
     * 解析结果：成功解析出的题目 + 逐行错误
     *
     * <p>做成 record 而不是返回 {@code List} + 抛异常：
     * 「部分块有错」是正常输入，不是异常。用返回值表达能让调用方
     * 一次性把所有错误展示给管理员，而不是改一个错再撞下一个。
     */
    public record ParseResult(List<ParsedQuestion> questions, List<LineError> errors) {

        /** 是否整批都合法 */
        public boolean hasError() {
            return !errors.isEmpty();
        }
    }

    /** 单条解析出的题目（尚未落库） */
    public record ParsedQuestion(QuizQuestionTypeEnum type, String stem, List<QuizOptionItem> options,
                                 String correctLabels, String analysis, int startLine) {
    }

    /** 单条错误：行号 + 原因 */
    public record LineError(int line, String message) {
    }

    /**
     * 解析粘贴的文本
     *
     * @param text 待解析文本，可为 null（返回空结果）
     * @return 解析结果；永不为 null
     */
    public static ParseResult parse(String text) {
        List<ParsedQuestion> questions = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new ParseResult(questions, errors);
        }

        // 用 LinkedHashMap 保留选项的输入顺序（TreeSet 会按标签排，
        // 但选项行本来就是 A/B/C/D 顺序，保留原序能发现「跳号」这类错误）
        List<String> lines = text.lines().toList();
        Builder current = null;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String raw = lines.get(i);
            String line = raw.strip();

            // 空行：结束当前块（但不报错）
            if (line.isEmpty()) {
                current = flush(current, questions, errors);
                continue;
            }

            // ① 题型行：开新块（如果上一个是未完成的块，先结算它）
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

            // ② 没有题型行就开始内容：报错，但继续往下解析（尽量多报几个错）
            if (current == null) {
                errors.add(new LineError(lineNo, "题目缺少题型行（应写【单选】或【多选】）"));
                // 造一个占位块，避免后续每一行都报同一个错
                current = new Builder(lineNo, null);
                current.stemLines.add(line);
                continue;
            }

            // ③ 选项行
            Matcher optionMatcher = OPTION_LINE.matcher(line);
            if (optionMatcher.matches()) {
                String label = optionMatcher.group(1).toUpperCase();
                String content = optionMatcher.group(2).strip();
                boolean correct = optionMatcher.group(3) != null;
                current.addOption(label, content, correct);
                continue;
            }

            // ④ 答案行
            Matcher answerMatcher = ANSWER_LINE.matcher(line);
            if (answerMatcher.matches()) {
                current.explicitAnswer = QuizLabelUtils.normalize(answerMatcher.group(1));
                continue;
            }

            // ⑤ 解析行：后续的非空行都追加进解析（解析可以换行）
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

            // ⑥ 其它行：在选项开始之前算题干（题干可以换行），之后算无法识别
            if (current.options.isEmpty()) {
                current.stemLines.add(line);
            } else {
                errors.add(new LineError(lineNo, "无法识别的行（应为选项、答案或解析）"));
            }
        }
        flush(current, questions, errors);

        return new ParseResult(questions, errors);
    }

    /**
     * 结算一个块：校验并转成 {@link ParsedQuestion}
     *
     * @return 恒为 null（调用方用它清空 current，语义更清楚）
     */
    private static Builder flush(Builder block, List<ParsedQuestion> questions, List<LineError> errors) {
        if (block == null) {
            return null;
        }
        // 空块（连续空行）直接丢弃，不报错
        if (block.type == null && block.stemLines.isEmpty() && block.options.isEmpty()) {
            return null;
        }

        if (block.type == null) {
            errors.add(new LineError(block.startLine, "题目缺少题型行（应写【单选】或【多选】）"));
            return null;
        }

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

        // 标签必须从 A 开始连续：A、B、D 这种跳号会在界面上显示断档，
        // 用户会以为渲染坏了；而且正确答案 "ABD" 很难在复盘时对上
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
            correct = String.join("", block.starLabels);
        }
        if (correct.isEmpty()) {
            errors.add(new LineError(block.startLine, "缺少正确答案（行尾标 * 或写「答案：B」）"));
            return null;
        }
        // 正确项必须都属于已有选项
        for (char c : correct.toCharArray()) {
            if (!labels.contains(String.valueOf(c))) {
                errors.add(new LineError(block.startLine, "正确答案 " + c + " 不在选项范围内"));
                return null;
            }
        }

        String analysis = block.analysisLines.isEmpty() ? null : String.join("\n", block.analysisLines).strip();
        if (analysis != null && analysis.length() > QuizConstant.ANALYSIS_MAX_LENGTH) {
            errors.add(new LineError(block.startLine,
                    "解析超过 " + QuizConstant.ANALYSIS_MAX_LENGTH + " 个字符"));
            return null;
        }

        questions.add(new ParsedQuestion(block.type, stem, block.options, correct, analysis, block.startLine));
        return null;
    }

    /** 题型文案 → 枚举（"单项选择题" 这类长写法也认） */
    private static QuizQuestionTypeEnum typeNameToType(String name) {
        return name.startsWith("多") || name.contains("多项")
                ? QuizQuestionTypeEnum.MULTIPLE
                : QuizQuestionTypeEnum.SINGLE;
    }

    /**
     * 块构建器（可变，只在解析过程中存活）
     *
     * <p>为什么用可变对象而不是 record：解析是「逐行累积」的过程，
     * 用不可变结构会让每一步都产生新对象，代码反而更难读。
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
            option.setLabel(label);
            option.setContent(content);
            options.add(option);
            if (correct) {
                starLabels.add(label);
            }
        }
    }

    private QuizImportParser() {
    }
}
