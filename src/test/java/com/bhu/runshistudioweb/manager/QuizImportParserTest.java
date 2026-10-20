package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.model.dto.quiz.QuizOptionItem;
import com.bhu.runshistudioweb.model.enums.QuizQuestionTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题目导入解析器验收测试（纯单测：不启 Spring、不连数据库）
 *
 * author: shaoshing
 *
 * <p>解析器是本模块唯一「面向不规整输入」的组件：粘贴过来的文本
 * 全角半角混杂、答案有的用星号有的用「答案：」、块之间可能没有空行。
 * 这些边界全在这里覆盖——它们的失败表现为「导入报错但看不出哪错了」，
 * 比业务逻辑出错更让人烦躁。
 */
class QuizImportParserTest {

    /** 一条标准单选题（行尾星号标正确项） */
    private static final String SINGLE_STAR = """
            【单选】物质的唯一特性是？
            A. 运动性
            B. 客观实在性*
            C. 可知性
            D. 矛盾性
            解析：客观实在性是物质的唯一特性；运动是物质的根本属性。
            """;

    /** 一条标准多选题（独立的「答案：」行） */
    private static final String MULTIPLE_ANSWER_LINE = """
            【多选】下列属于唯物辩证法基本规律的有？
            A. 对立统一规律
            B. 质量互变规律
            C. 否定之否定规律
            D. 因果律
            答案：ABC
            解析：因果律属于认识论范畴，不是辩证法的基本规律。
            """;

    // ==================== 正常解析 ====================

    @Test
    @DisplayName("单选题：星号标正确项，解析被正确归位")
    void parseSingleWithStar() {
        QuizImportParser.ParseResult result = QuizImportParser.parse(SINGLE_STAR);

        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        assertEquals(1, result.questions().size());

        QuizImportParser.ParsedQuestion question = result.questions().get(0);
        assertEquals(QuizQuestionTypeEnum.SINGLE, question.type());
        assertEquals("物质的唯一特性是？", question.stem());
        assertEquals("B", question.correctLabels());
        assertEquals(4, question.options().size());
        assertEquals("客观实在性", question.options().get(1).getContent(), "星号必须从选项正文里剥掉");
        assertTrue(question.analysis().startsWith("客观实在性是物质的唯一特性"));
    }

    @Test
    @DisplayName("多选题：独立「答案：」行")
    void parseMultipleWithAnswerLine() {
        QuizImportParser.ParseResult result = QuizImportParser.parse(MULTIPLE_ANSWER_LINE);

        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        QuizImportParser.ParsedQuestion question = result.questions().get(0);
        assertEquals(QuizQuestionTypeEnum.MULTIPLE, question.type());
        assertEquals("ABC", question.correctLabels());
        assertEquals(4, question.options().size());
    }

    @Test
    @DisplayName("「答案：」优先于行尾星号（显式声明优先于隐式标记）")
    void explicitAnswerWinsOverStar() {
        String text = """
                【单选】题干
                A. 甲*
                B. 乙
                答案：B
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        assertEquals("B", result.questions().get(0).correctLabels(),
                "同时存在时必须采信「答案：」行");
    }

    @Test
    @DisplayName("多题连续：块之间没有空行也能正确切分")
    void parseMultipleBlocksWithoutBlankLine() {
        String text = """
                【单选】第一题
                A. 甲
                B. 乙*
                【单选】第二题
                A. 丙
                B. 丁*
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        assertEquals(2, result.questions().size());
        assertEquals("第一题", result.questions().get(0).stem());
        assertEquals("第二题", result.questions().get(1).stem());
    }

    @Test
    @DisplayName("题干可以多行（题型行之后、第一个选项之前的全部文本）")
    void parseMultiLineStem() {
        String text = """
                【单选】第一行题干
                第二行题干
                A. 甲
                B. 乙*
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        assertEquals("第一行题干 第二行题干", result.questions().get(0).stem());
    }

    @Test
    @DisplayName("题型行后面直接跟题干（【单选】题干…）")
    void parseTypeLineWithInlineStem() {
        String text = """
                【单选】题干就在题型行里
                A. 甲
                B. 乙*
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        assertEquals("题干就在题型行里", result.questions().get(0).stem());
    }

    // ==================== 格式容错 ====================

    @Test
    @DisplayName("半角方括号、长题型名、各种选项分隔符都认")
    void parseFormatTolerance() {
        String text = """
                [单项选择题] 题干
                A、甲
                B．乙*
                C）丙
                D：丁
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        QuizImportParser.ParsedQuestion question = result.questions().get(0);
        assertEquals(QuizQuestionTypeEnum.SINGLE, question.type());
        assertEquals(4, question.options().size());
        assertEquals("乙", question.options().get(1).getContent());
    }

    @Test
    @DisplayName("正确答案的大小写与分隔符被规范化")
    void parseNormalizesAnswer() {
        String text = """
                【多选】题干
                A. 甲
                B. 乙
                C. 丙
                答案：c,a
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertFalse(result.hasError(), "不应有错误：" + result.errors());
        assertEquals("AC", result.questions().get(0).correctLabels());
    }

    // ==================== 错误定位 ====================

    @Test
    @DisplayName("缺少题型行：报错并带行号")
    void errorMissingTypeLine() {
        String text = """
                题干没有题型行
                A. 甲
                B. 乙*
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertTrue(result.hasError());
        assertEquals(1, result.errors().get(0).line(), "行号必须指向第一行");
        assertTrue(result.errors().get(0).message().contains("题型"));
    }

    @Test
    @DisplayName("选项标签跳号：报错（界面上会显示断档）")
    void errorSkippedLabel() {
        String text = """
                【单选】题干
                A. 甲
                B. 乙
                D. 丁*
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertTrue(result.hasError());
        assertTrue(result.errors().get(0).message().contains("连续"));
    }

    @Test
    @DisplayName("缺少正确答案：报错")
    void errorMissingAnswer() {
        String text = """
                【单选】题干
                A. 甲
                B. 乙
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertTrue(result.hasError());
        assertTrue(result.errors().get(0).message().contains("正确答案"));
    }

    @Test
    @DisplayName("正确答案不在选项内：报错")
    void errorAnswerOutOfRange() {
        String text = """
                【单选】题干
                A. 甲
                B. 乙
                答案：C
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertTrue(result.hasError());
        assertTrue(result.errors().get(0).message().contains("不在选项范围"));
    }

    @Test
    @DisplayName("单选题选项不足 2 个 / 多选题不足 3 个：报错")
    void errorTooFewOptions() {
        QuizImportParser.ParseResult single = QuizImportParser.parse("""
                【单选】题干
                A. 甲*
                """);
        assertTrue(single.hasError());

        QuizImportParser.ParseResult multiple = QuizImportParser.parse("""
                【多选】题干
                A. 甲*
                B. 乙*
                """);
        assertTrue(multiple.hasError(), "多选题只有 2 个选项等于「全选就得分」");
    }

    @Test
    @DisplayName("题干为空：报错")
    void errorEmptyStem() {
        QuizImportParser.ParseResult result = QuizImportParser.parse("""
                【单选】
                A. 甲
                B. 乙*
                """);
        assertTrue(result.hasError());
        assertTrue(result.errors().get(0).message().contains("题干"));
    }

    @Test
    @DisplayName("多块时错误按块报出，且合法的块仍然被解析出来")
    void errorsArePerBlock() {
        String text = """
                【单选】合法的一题
                A. 甲
                B. 乙*

                【单选】没有答案的一题
                A. 丙
                B. 丁
                """;
        QuizImportParser.ParseResult result = QuizImportParser.parse(text);
        assertTrue(result.hasError());
        assertEquals(1, result.questions().size(), "合法的那一题仍应被解析出来");
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).line() >= 5, "行号应指向第二个块");
    }

    // ==================== 空输入 ====================

    @Test
    @DisplayName("空文本：返回空结果，不报错")
    void parseEmpty() {
        assertFalse(QuizImportParser.parse(null).hasError());
        assertTrue(QuizImportParser.parse(null).questions().isEmpty());
        assertTrue(QuizImportParser.parse("").questions().isEmpty());
        assertTrue(QuizImportParser.parse("   \n\n  ").questions().isEmpty());
        assertFalse(QuizImportParser.parse("   \n\n  ").hasError(), "空白文本不是错误");
    }

    // ==================== 与 Service 的契约 ====================

    @Test
    @DisplayName("解析结果能直接交给 Service 校验（选项标签从 A 连续、正确项属于选项）")
    void parsedResultIsServiceReady() {
        QuizImportParser.ParseResult result = QuizImportParser.parse(SINGLE_STAR + "\n" + MULTIPLE_ANSWER_LINE);
        assertFalse(result.hasError());
        for (QuizImportParser.ParsedQuestion question : result.questions()) {
            List<String> labels = question.options().stream().map(QuizOptionItem::getLabel).toList();
            assertEquals(List.of("A", "B", "C", "D"), labels);
            for (char c : question.correctLabels().toCharArray()) {
                assertTrue(labels.contains(String.valueOf(c)), "正确项必须属于选项");
            }
        }
        // 解析出的解析字段：没有解析的题返回 null 而不是空串（全局约定「空值存 NULL」）
        assertNull(QuizImportParser.parse("""
                【单选】无解析
                A. 甲
                B. 乙*
                """).questions().get(0).analysis());
    }
}
